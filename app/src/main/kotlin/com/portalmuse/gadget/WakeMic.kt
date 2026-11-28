package com.portalmuse.gadget

import android.media.AudioRecord
import com.portalmuse.core.PreRoll
import com.portalmuse.core.Upsampler
import java.util.concurrent.atomic.AtomicBoolean

/** 40 ms at 16 kHz, that is one call chunk once converted to 24 kHz. */
private const val INPUT_BYTES = 640 * 2
/** At most 15 s kept during connection. */
private const val KEPT_FRAMES = 15_000 / 40

/**
 * The listening microphone, left open after "Hey Muse": the call takes over capture without a gap, including
 * what is said while connecting. The sound stays in memory and only leaves if the call opens; [close] forgets
 * it and releases the microphone.
 */
class WakeMic(private val recorder: AudioRecord) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val preRoll = PreRoll(::capture, KEPT_FRAMES)

    private fun capture(): ByteArray? {
        val frame = ByteArray(INPUT_BYTES)
        var filled = 0
        while (filled < frame.size) {
            val read = recorder.read(frame, filled, frame.size - filled)
            if (read <= 0) return null
            filled += read
        }
        return Upsampler.to24k(frame)
    }

    fun read(): ByteArray? = preRoll.read()

    /** The minimum gap between two chunks while catching up. */
    fun pace(millis: Long) {
        preRoll.minGapMillis = millis
    }

    fun stop() = preRoll.stop()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preRoll.stop()
        // Stopping unblocks the ongoing read; the microphone is only released afterwards.
        runCatching { recorder.stop() }
        preRoll.join(1_000)
        recorder.release()
    }
}
