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
import com.portalmuse.core.Upsampler
import com.portalmuse.core.VideoAudio

private const val RATE = 48_000
private const val FRAME_BYTES = 960 * 2 // 20 ms
/** Après « Hey Muse », le son gardé est rattrapé trois fois plus vite, comme dans le client Linux. */
private const val WAKE_GAP_MS = 13L
private const val HALF_GAP_MS = 6L
/** La capture se fait comme pour l'appel vocal, puis passe à 48 kHz, comme le client Linux. */
private const val CAPTURE_RATE = 24_000
private const val CAPTURE_BYTES = FRAME_BYTES / 2

/**
 * Micro, haut-parleur et Opus d'un appel vidéo. L'autorisation du micro doit déjà être
 * accordée. Après « Hey Muse », la capture est celle de [wakeMic], déjà en cours.
 */
@SuppressLint("MissingPermission")
class VideoPhoneAudio(context: Context, private val wakeMic: WakeMic? = null) : VideoAudio {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val speech = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speech)
        .build()
    private val recorder: AudioRecord?
    /** La seconde moitié d'un morceau de 40 ms de [wakeMic]. */
    private var half: ByteArray? = null
    private val track: AudioTrack
    private val opus: OpusCodec
    @Volatile private var capturing = true
    private var previous = 0

    init {
        wakeMic?.pace(WAKE_GAP_MS)
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
        opus = OpusCodec()
        manager.requestAudioFocus(focus)
        recorder?.startRecording()
        track.play()
    }

    private fun openRecorder(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "capture à 24 kHz indisponible" }
        val opened = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_BYTES * 8),
        )
        check(opened.state == AudioRecord.STATE_INITIALIZED) { "micro indisponible" }
        return opened
    }

    val silentSpeaker get() = manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    override fun read(): ByteArray? {
        val frame = (if (recorder != null) record(recorder) else fromWake()) ?: return null
        return Upsampler.to48k(frame, previous).also { previous = Upsampler.last(frame) }
    }

    private fun record(recorder: AudioRecord): ByteArray? {
        val frame = ByteArray(CAPTURE_BYTES)
        var filled = 0
        while (capturing && filled < frame.size) {
            val read = recorder.read(frame, filled, frame.size - filled)
            if (read <= 0) return null
            filled += read
        }
        return frame.takeIf { capturing }
    }

    /** Les morceaux de 40 ms de l'écoute, rendus par moitiés de 20 ms. */
    private fun fromWake(): ByteArray? {
        half?.let {
            half = null
            Thread.sleep(HALF_GAP_MS)
            return it
        }
        val frame = wakeMic?.read() ?: return null
        half = frame.copyOfRange(CAPTURE_BYTES, frame.size)
        return frame.copyOf(CAPTURE_BYTES)
    }

    override fun encode(pcm: ByteArray) = opus.encode(pcm)

    override fun decode(opus: ByteArray) = this.opus.decode(opus)

    override fun play(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size)
    }

    override fun stopCapture() {
        capturing = false
        wakeMic?.stop()
    }

    /** Après la fin de l'appel seulement. */
    fun release() {
        capturing = false
        runCatching { recorder?.stop() }
        recorder?.release()
        wakeMic?.close()
        runCatching { track.stop() }
        track.release()
        opus.close()
        manager.abandonAudioFocusRequest(focus)
    }
}
