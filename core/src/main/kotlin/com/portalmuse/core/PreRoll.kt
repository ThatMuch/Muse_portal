package com.portalmuse.core

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The microphone captured without waiting for the call: what is said while connecting is kept in memory, then
 * handed out in order as soon as the call asks, a little faster than real time. Beyond [capacity] chunks, the
 * oldest are forgotten.
 *
 * [capture] is blocking and returns `null` when the source stops.
 */
class PreRoll(
    private val capture: () -> ByteArray?,
    capacity: Int,
    /** The minimum gap between two chunks handed out: bounds the catch up rate. */
    @Volatile var minGapMillis: Long = 10,
) {
    private val frames = LinkedBlockingDeque<ByteArray>(capacity)
    @Volatile private var stopped = false
    @Volatile private var finished = false
    private var releasedAt = 0L

    private val worker = thread(name = "pre-roll", isDaemon = true) {
        try {
            while (!stopped) {
                val frame = capture() ?: break
                while (!frames.offerLast(frame)) frames.pollFirst()
            }
        } catch (e: RuntimeException) {
            // The source was closed under our feet: capture is over.
        } finally {
            finished = true
        }
    }

    /** The oldest chunk, waiting for it if needed; `null` after [stop] or at the end of the source. */
    fun read(): ByteArray? {
        val wait = minGapMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releasedAt)
        if (releasedAt != 0L && wait > 0) Thread.sleep(wait)
        while (!stopped) {
            // "finished" is read before the queue: nothing added right before the end is lost.
            val over = finished
            val frame = frames.pollFirst(20, TimeUnit.MILLISECONDS)
            if (frame != null) {
                releasedAt = System.nanoTime()
                return frame
            }
            if (over) return null
        }
        return null
    }

    /** Forgets the kept sound. Does not wait for the end of capture. */
    fun stop() {
        stopped = true
        frames.clear()
    }

    /** Call after [stop], before releasing the source. */
    fun join(millis: Long) = worker.join(millis)
}

/** Rate changes in 16 bit little-endian PCM, by linear interpolation. */
object Upsampler {
    /**
     * 24 kHz to 48 kHz; [previous] is the last sample of the previous chunk. Like the Linux client: the middle
     * of two samples, then the sample.
     */
    fun to48k(input: ByteArray, previous: Int): ByteArray {
        val output = ByteArray(input.size / 2 * 4)
        var before = previous
        for (i in 0 until input.size / 2) {
            val value = last(input, i)
            val middle = (before + value) / 2
            output[4 * i] = middle.toByte()
            output[4 * i + 1] = (middle shr 8).toByte()
            output[4 * i + 2] = value.toByte()
            output[4 * i + 3] = (value shr 8).toByte()
            before = value
        }
        return output
    }

    /** The sample [index]; the last one by default. */
    fun last(input: ByteArray, index: Int = input.size / 2 - 1): Int =
        (input[2 * index].toInt() and 0xff) or (input[2 * index + 1].toInt() shl 8)

    fun to24k(input: ByteArray): ByteArray {
        val count = input.size / 2
        fun sample(i: Int) = (input[2 * i].toInt() and 0xff) or (input[2 * i + 1].toInt() shl 8)
        val output = ByteArray(count * 3 / 2 * 2)
        for (j in 0 until output.size / 2) {
            // Position j × 2/3 in the input, counted in thirds of a sample.
            val position = j * 2
            val i = position / 3
            val before = sample(i)
            val after = sample(minOf(i + 1, count - 1))
            val value = before + (after - before) * (position % 3) / 3
            output[2 * j] = value.toByte()
            output[2 * j + 1] = (value shr 8).toByte()
        }
        return output
    }
}
