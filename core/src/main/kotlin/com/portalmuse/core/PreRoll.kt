package com.portalmuse.core

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Le micro capté sans attendre l'appel : ce qui est dit pendant la connexion est gardé en mémoire,
 * puis rendu dans l'ordre dès que l'appel le demande, un peu plus vite que le temps réel.
 * Au-delà de [capacity] morceaux, les plus anciens sont oubliés.
 *
 * [capture] est bloquant et rend `null` quand la source s'arrête.
 */
class PreRoll(
    private val capture: () -> ByteArray?,
    capacity: Int,
    /** L'écart minimal entre deux morceaux rendus : borne le débit de rattrapage. */
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
            // La source a été fermée sous nos pieds : la capture est finie.
        } finally {
            finished = true
        }
    }

    /** Le plus ancien morceau, en l'attendant au besoin ; `null` après [stop] ou à la fin de la source. */
    fun read(): ByteArray? {
        val wait = minGapMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releasedAt)
        if (releasedAt != 0L && wait > 0) Thread.sleep(wait)
        while (!stopped) {
            // « finished » est lu avant la file : rien d'ajouté juste avant la fin n'est perdu.
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

    /** Oublie le son gardé. N'attend pas la fin de la capture. */
    fun stop() {
        stopped = true
        frames.clear()
    }

    /** À appeler après [stop], avant de libérer la source. */
    fun join(millis: Long) = worker.join(millis)
}

/** Des changements de cadence en PCM 16 bits petit-boutiste, par interpolation linéaire. */
object Upsampler {
    /**
     * 24 kHz vers 48 kHz ; [previous] est le dernier échantillon du morceau précédent. Comme le
     * client Linux : le milieu de deux échantillons, puis l'échantillon.
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

    /** L'échantillon [index] ; le dernier par défaut. */
    fun last(input: ByteArray, index: Int = input.size / 2 - 1): Int =
        (input[2 * index].toInt() and 0xff) or (input[2 * index + 1].toInt() shl 8)

    fun to24k(input: ByteArray): ByteArray {
        val count = input.size / 2
        fun sample(i: Int) = (input[2 * i].toInt() and 0xff) or (input[2 * i + 1].toInt() shl 8)
        val output = ByteArray(count * 3 / 2 * 2)
        for (j in 0 until output.size / 2) {
            // La position j × 2/3 dans l'entrée, comptée en tiers d'échantillon.
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
