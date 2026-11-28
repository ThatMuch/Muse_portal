package com.portalmuse.gadget

import com.portalmuse.core.tr
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
private const val CHUNK = 320 // 20 ms, like the firmware

/**
 * Listens for "Hey Muse" on the device while it runs: sound goes through the detector and is then forgotten.
 * Nothing is recorded or sent before the word. At the first wake, it stops and hands the still open microphone
 * to [onWake], which must close it.
 */
class WakeListener(
    private val sensitivity: Int,
    private val onWake: (WakeMic) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    @Volatile private var stopped = false

    /** Only call once the microphone permission is granted. */
    fun start() {
        worker = thread(name = "wake-word") {
            val problem = try {
                listen()
            } catch (e: RuntimeException) {
                Log.e(TAG, "listening failed: ${e.javaClass.simpleName}")
                tr("the microphone is unavailable", "le micro est indisponible")
            }
            if (problem != null) main.post { if (!stopped) onFailure(problem) }
        }
    }

    /** Releases the microphone before returning: a call or voice note can take it right away. */
    fun stop() {
        stopped = true
        worker?.join(1_000)
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(): String? {
        val engine = WakeWordEngine.create(sensitivity) ?: return tr(
            "the detector does not start",
            "le détecteur ne démarre pas",
        )
        engine.use {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return tr("the microphone is unavailable", "le micro est indisponible")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CHUNK * 2 * 25),
            )
            var handedOver = false
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return tr(
                    "the microphone is unavailable",
                    "le micro est indisponible",
                )
                recorder.startRecording()
                Log.i(TAG, "“Hey Muse” listening started")
                val samples = ShortArray(CHUNK)
                while (!stopped) {
                    val read = recorder.read(samples, 0, samples.size)
                    if (read <= 0) return tr("the microphone is unavailable", "le micro est indisponible")
                    if (engine.feed(samples, read)) {
                        Log.i(TAG, "“Hey Muse” heard")
                        val mic = WakeMic(recorder)
                        handedOver = true
                        main.post { if (!stopped) onWake(mic) else mic.close() }
                        return null
                    }
                    if (!engine.healthy) return tr("the detector stopped", "le détecteur s'est arrêté")
                }
                return null
            } finally {
                if (!handedOver) recorder.release()
            }
        }
    }
}
