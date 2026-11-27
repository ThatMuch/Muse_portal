package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

private const val TAG = "PortalMuse"
private const val SAMPLE_RATE = 16_000
/** 100 ms de son en 16 bits mono. */
private const val BLOCK_BYTES = SAMPLE_RATE / 10 * 2
private const val MAX_BLOCKS = 300 // 30 secondes
private const val SILENCE_BLOCKS = 20 // 2 secondes après la parole
private const val NO_SPEECH_BLOCKS = 80 // 8 secondes sans rien entendre
private const val MIN_SPEECH_LEVEL = 400.0
private const val DIGITAL_SILENCE = 10.0
/** Plafond du seuil : quelqu'un qui parle dès le premier bloc fausse la mesure du fond. */
private const val MAX_SPEECH_THRESHOLD = 1_500.0

/**
 * Enregistre une note vocale, seulement entre [start] et l'arrêt. L'écoute s'arrête d'elle-même
 * après un silence qui suit la parole.
 */
class VoiceRecorder(
    private val onLevel: (Float) -> Unit,
    private val onDone: (Result) -> Unit,
) {
    sealed class Result {
        class Audio(val pcm: ByteArray, val sampleRate: Int) : Result()
        /** Que des zéros : micro coupé sur le Portal, ou refusé au système. */
        object Muted : Result()
        object NothingHeard : Result()
        object Failed : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var finish = false
    @Volatile private var cancelled = false

    /** Termine l'écoute et envoie ce qui a été entendu. */
    fun finish() {
        finish = true
    }

    fun cancel() {
        cancelled = true
    }

    /** À appeler seulement une fois l'autorisation du micro accordée. */
    @SuppressLint("MissingPermission")
    fun start() {
        thread(name = "voice") {
            val result = try {
                record()
            } catch (e: RuntimeException) {
                Log.e(TAG, "micro en échec : ${e.javaClass.simpleName}")
                Result.Failed
            }
            main.post { if (!cancelled) onDone(result) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(): Result {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return Result.Failed
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, BLOCK_BYTES * 4),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return Result.Failed
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Result.Failed
            val audio = ByteArrayOutputStream()
            val block = ByteArray(BLOCK_BYTES)
            var floor = Double.MAX_VALUE
            var loudest = 0.0
            var spoke = false
            var quiet = 0
            var blocks = 0
            while (!finish && !cancelled && blocks < MAX_BLOCKS) {
                val read = recorder.read(block, 0, block.size)
                if (read <= 0) return Result.Failed
                audio.write(block, 0, read)
                blocks++
                val level = level(block, read)
                loudest = maxOf(loudest, level)
                // Le bruit de fond est le bloc le plus calme entendu jusqu'ici.
                // Les premiers blocs du Portal sont un silence numérique, pas le bruit de la pièce.
                if (level > DIGITAL_SILENCE) floor = minOf(floor, level)
                main.post { if (!cancelled) onLevel((level / 4000).toFloat().coerceIn(0f, 1f)) }
                if (level > (floor * 3).coerceIn(MIN_SPEECH_LEVEL, MAX_SPEECH_THRESHOLD)) {
                    spoke = true
                    quiet = 0
                } else {
                    quiet++
                }
                if (spoke && quiet >= SILENCE_BLOCKS) break
                if (!spoke && blocks >= NO_SPEECH_BLOCKS) break
            }
            // Niveaux seulement, jamais le son : utile pour régler les seuils.
            Log.i(TAG, "écoute : ${blocks / 10.0} s, niveau max ${loudest.toInt()}, fond ${floor.toInt()}, parole $spoke")
            if (loudest == 0.0) return Result.Muted
            // Un toucher sur « Envoyer » fait foi, même si la détection n'a rien reconnu.
            if (!spoke && !finish) return Result.NothingHeard
            return Result.Audio(audio.toByteArray(), SAMPLE_RATE)
        } finally {
            recorder.release()
        }
    }

    /** Valeur efficace du bloc. */
    private fun level(block: ByteArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length - 1 step 2) {
            val sample = ((block[i + 1].toInt() shl 8) or (block[i].toInt() and 0xFF)).toDouble()
            sum += sample * sample
        }
        return sqrt(sum / maxOf(1, length / 2))
    }
}
