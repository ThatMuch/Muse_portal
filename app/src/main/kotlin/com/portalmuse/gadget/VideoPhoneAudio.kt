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
/** After "Hey Muse", the buffered sound is caught up three times faster, as in the Linux client. */
private const val WAKE_GAP_MS = 13L
private const val HALF_GAP_MS = 6L
/** Capture works as for the voice call, then goes to 48 kHz, like the Linux client. */
private const val CAPTURE_RATE = 24_000
private const val CAPTURE_BYTES = FRAME_BYTES / 2

/**
 * Microphone, speaker and Opus of a video call. The microphone permission must already be granted. After "Hey
 * Muse", capture is the one of [wakeMic], already running.
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
    /** The second half of a 40 ms chunk from [wakeMic]. */
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
        check(minimum > 0) { "24 kHz capture unavailable" }
        val opened = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_BYTES * 8),
        )
        check(opened.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
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

    /** The 40 ms chunks from listening, handed out as 20 ms halves. */
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

    /** Only after the end of the call. */
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
