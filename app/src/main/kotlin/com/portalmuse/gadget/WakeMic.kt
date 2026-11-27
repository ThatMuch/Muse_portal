package com.portalmuse.gadget

import android.media.AudioRecord
import com.portalmuse.core.PreRoll
import com.portalmuse.core.Upsampler
import java.util.concurrent.atomic.AtomicBoolean

/** 40 ms à 16 kHz, soit un morceau d'appel une fois passé à 24 kHz. */
private const val INPUT_BYTES = 640 * 2
/** Au plus 15 s gardées pendant la connexion. */
private const val KEPT_FRAMES = 15_000 / 40

/**
 * Le micro de l'écoute, resté ouvert après « Hey Muse » : l'appel reprend la capture sans coupure,
 * y compris ce qui est dit pendant la connexion. Le son reste en mémoire et ne part que si l'appel
 * s'ouvre ; [close] l'oublie et libère le micro.
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

    /** L'écart minimal entre deux morceaux pendant le rattrapage. */
    fun pace(millis: Long) {
        preRoll.minGapMillis = millis
    }

    fun stop() = preRoll.stop()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preRoll.stop()
        // L'arrêt débloque la lecture en cours ; le micro n'est libéré qu'ensuite.
        runCatching { recorder.stop() }
        preRoll.join(1_000)
        recorder.release()
    }
}
