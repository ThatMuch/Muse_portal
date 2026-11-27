package com.portalmuse.gadget

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val RATE = 48_000
private const val FRAME_BYTES = 960 * 2 // 20 ms
private const val BITRATE = 32_000
private const val FRAME_US = 20_000L
private const val PRE_SKIP = 312

/** L'encodeur et le décodeur Opus d'Android. L'encodeur sert à un fil, le décodeur à un autre. */
class OpusCodec : AutoCloseable {
    private val encoder: MediaCodec
    private val decoder: MediaCodec
    private var sentUs = 0L
    private var receivedUs = 0L

    init {
        val encoding = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        encoder.configure(encoding, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        // Le décodeur d'Android attend l'en-tête Opus et deux durées en nanosecondes.
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".toByteArray())
            .put(1).put(1).putShort(PRE_SKIP.toShort()).putInt(RATE).putShort(0).put(0)
        fun nanoseconds(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value)
        val decoding = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setByteBuffer("csd-0", head.apply { flip() })
            setByteBuffer("csd-1", nanoseconds(PRE_SKIP * 1_000_000_000L / RATE).apply { flip() })
            setByteBuffer("csd-2", nanoseconds(80_000_000L).apply { flip() })
        }
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        decoder.configure(decoding, null, null, 0)
        decoder.start()
    }

    /** 20 ms de PCM 48 kHz mono ; zéro, un ou plusieurs paquets. */
    fun encode(pcm: ByteArray): List<ByteArray> {
        sentUs += FRAME_US
        return pass(encoder, pcm, sentUs)
    }

    fun decode(opus: ByteArray): ByteArray {
        receivedUs += FRAME_US
        val out = ByteArrayOutputStream()
        pass(decoder, opus, receivedUs).forEach(out::write)
        return out.toByteArray()
    }

    /** Donne une entrée au codec et ramasse ce qui est prêt, sans attendre plus de quelques ms. */
    private fun pass(codec: MediaCodec, input: ByteArray, timeUs: Long): List<ByteArray> {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            codec.getInputBuffer(index)!!.apply {
                clear()
                put(input)
            }
            codec.queueInputBuffer(index, 0, input.size, timeUs, 0)
        }
        val results = ArrayList<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var wait = 5_000L
        while (true) {
            val ready = codec.dequeueOutputBuffer(info, wait)
            if (ready == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (ready < 0) break
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buffer = codec.getOutputBuffer(ready)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                results.add(bytes)
            }
            codec.releaseOutputBuffer(ready, false)
            wait = 0
        }
        return results
    }

    override fun close() {
        for (codec in listOf(encoder, decoder)) {
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
