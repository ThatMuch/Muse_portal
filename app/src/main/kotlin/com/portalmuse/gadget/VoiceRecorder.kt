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
/** 100 ms of 16 bit mono sound. */
private const val BLOCK_BYTES = SAMPLE_RATE / 10 * 2
private const val MAX_BLOCKS = 300 // 30 seconds
private const val SILENCE_BLOCKS = 20 // 2 seconds after speech
private const val NO_SPEECH_BLOCKS = 80 // 8 seconds without hearing anything
private const val MIN_SPEECH_LEVEL = 400.0
private const val DIGITAL_SILENCE = 10.0
/** Ceiling of the threshold: someone speaking from the first block skews the background measurement. */
private const val MAX_SPEECH_THRESHOLD = 1_500.0

/**
 * Records a voice note, only between [start] and the stop. Listening stops by itself after a silence that
 * follows speech.
 */
class VoiceRecorder(
    private val onLevel: (Float) -> Unit,
    private val onDone: (Result) -> Unit,
) {
    sealed class Result {
        class Audio(val pcm: ByteArray, val sampleRate: Int) : Result()
        /** Nothing but zeros: microphone muted on the Portal, or denied by the system. */
        object Muted : Result()
        object NothingHeard : Result()
        object Failed : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var finish = false
    @Volatile private var cancelled = false

    /** Ends listening and sends what was heard. */
    fun finish() {
        finish = true
    }

    fun cancel() {
        cancelled = true
    }

    /** Only call once the microphone permission is granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        thread(name = "voice") {
            val result = try {
                record()
            } catch (e: RuntimeException) {
                Log.e(TAG, "microphone failed: ${e.javaClass.simpleName}")
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
                // The background noise is the quietest block heard so far. The Portal's first blocks are
                // digital silence, not the noise of the room.
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
            // Levels only, never the sound: useful to tune the thresholds.
            Log.i(TAG, "listening: ${blocks / 10.0} s, max level ${loudest.toInt()}, floor " +
                "${floor.toInt()}, speech $spoke")
            if (loudest == 0.0) return Result.Muted
            // A touch on "Send" is authoritative, even if detection recognized nothing.
            if (!spoke && !finish) return Result.NothingHeard
            return Result.Audio(audio.toByteArray(), SAMPLE_RATE)
        } finally {
            recorder.release()
        }
    }

    /** Root mean square of the block. */
    private fun level(block: ByteArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length - 1 step 2) {
            val sample = ((block[i + 1].toInt() shl 8) or (block[i].toInt() and 0xFF)).toDouble()
            sum += sample * sample
        }
        return sqrt(sum / maxOf(1, length / 2))
    }
}
