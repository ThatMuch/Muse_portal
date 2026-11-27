package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.portalmuse.core.CallAudio

private const val TAG = "PortalMuse"
private const val RATE = 24_000
private const val FRAME_BYTES = 960 * 2 // 40 ms

/**
 * Micro et haut-parleur d'un appel. L'autorisation du micro doit déjà être accordée. Après
 * « Hey Muse », la capture est celle de [wakeMic], déjà en cours.
 */
@SuppressLint("MissingPermission")
class PhoneAudio(context: Context, private val wakeMic: WakeMic? = null) : CallAudio {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val speech = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speech)
        .build()
    private val recorder: AudioRecord?
    private val track: AudioTrack

    @Volatile private var capturing = true
    private var loudest = 0

    init {
        recorder = if (wakeMic == null) openRecorder() else null
        val minimumOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(speech)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minimumOut, FRAME_BYTES * 5))
            .build()
        manager.requestAudioFocus(focus)
        recorder?.startRecording()
        track.play()
    }

    private fun openRecorder(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "capture à 24 kHz indisponible" }
        val opened = AudioRecord(
            // La même source que les notes vocales, que Muse comprend bien sur ce Portal. Le micro
            // étant coupé pendant que Muse parle, l'annulation d'écho n'est pas nécessaire.
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_BYTES * 8),
        )
        check(opened.state == AudioRecord.STATE_INITIALIZED) { "micro indisponible" }
        return opened
    }

    /** Vrai si le volume des médias est à zéro : on n'entendrait rien. */
    val silentSpeaker get() = manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    override fun read(): ByteArray? {
        val frame = (if (recorder != null) record(recorder) else wakeMic?.read()) ?: return null
        for (i in 1 until frame.size step 2) loudest = maxOf(loudest, Math.abs(frame[i].toInt()) shl 8)
        return frame
    }

    private fun record(recorder: AudioRecord): ByteArray? {
        val frame = ByteArray(FRAME_BYTES)
        var filled = 0
        while (capturing && filled < frame.size) {
            val read = recorder.read(frame, filled, frame.size - filled)
            if (read <= 0) return null
            filled += read
        }
        return frame.takeIf { capturing }
    }

    override fun play(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        if (!capturing) return
        track.pause()
        track.flush()
        track.play()
    }

    override fun stopCapture() {
        capturing = false
        wakeMic?.stop()
    }

    /** Après la fin de l'appel seulement. */
    fun release() {
        capturing = false
        // Niveau seulement, jamais le son.
        Log.i(TAG, "appel : crête du micro $loudest, haut-parleur ${if (silentSpeaker) "muet" else "actif"}")
        runCatching { recorder?.stop() }
        recorder?.release()
        wakeMic?.close()
        runCatching { track.stop() }
        track.release()
        manager.abandonAudioFocusRequest(focus)
    }
}
