package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val RATE = 16_000
private const val CHUNK = 320 // 20 ms, comme le firmware

/**
 * Écoute « Hey Muse » sur l'appareil, tant qu'elle tourne : le son passe dans le détecteur puis
 * est oublié. Rien n'est enregistré ni envoyé avant le mot. Au premier réveil, elle s'arrête et
 * passe le micro resté ouvert à [onWake], qui doit le fermer.
 */
class WakeListener(
    private val sensitivity: Int,
    private val onWake: (WakeMic) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    @Volatile private var stopped = false

    /** À appeler seulement une fois l'autorisation du micro accordée. */
    fun start() {
        worker = thread(name = "wake-word") {
            val problem = try {
                listen()
            } catch (e: RuntimeException) {
                Log.e(TAG, "écoute en échec : ${e.javaClass.simpleName}")
                "le micro est indisponible"
            }
            if (problem != null) main.post { if (!stopped) onFailure(problem) }
        }
    }

    /** Rend le micro avant de revenir : un appel ou une note vocale peut le prendre aussitôt. */
    fun stop() {
        stopped = true
        worker?.join(1_000)
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(): String? {
        val engine = WakeWordEngine.create(sensitivity) ?: return "le détecteur ne démarre pas"
        engine.use {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return "le micro est indisponible"
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CHUNK * 2 * 25),
            )
            var handedOver = false
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return "le micro est indisponible"
                recorder.startRecording()
                Log.i(TAG, "écoute « Hey Muse » démarrée")
                val samples = ShortArray(CHUNK)
                while (!stopped) {
                    val read = recorder.read(samples, 0, samples.size)
                    if (read <= 0) return "le micro est indisponible"
                    if (engine.feed(samples, read)) {
                        Log.i(TAG, "« Hey Muse » entendu")
                        val mic = WakeMic(recorder)
                        handedOver = true
                        main.post { if (!stopped) onWake(mic) else mic.close() }
                        return null
                    }
                    if (!engine.healthy) return "le détecteur s'est arrêté"
                }
                return null
            } finally {
                if (!handedOver) recorder.release()
            }
        }
    }
}
