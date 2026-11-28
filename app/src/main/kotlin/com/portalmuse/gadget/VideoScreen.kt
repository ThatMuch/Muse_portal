package com.portalmuse.gadget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import com.portalmuse.core.Backdrop
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val MAX_WAITING = 30
/** The size announced at the start; the stream then gives its own. */
private const val START_WIDTH = 448
private const val START_HEIGHT = 768

/**
 * Muse's video during a call: hardware H.264 decoding into a view that keeps the proportions. [push] receives
 * complete pictures from any thread.
 */
class VideoScreen(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    /** The retouched picture, laid over the raw video. */
    private val cleaned = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val painter = Handler(HandlerThread("video-backdrop").apply { start() }.looper)
    private var frames: Array<Bitmap>? = null
    private var backdrop: Backdrop? = null
    private var pixels = IntArray(0)
    private var next = 0
    private var painting = false
    private val waiting = LinkedBlockingQueue<ByteArray>()
    @Volatile private var surface: Surface? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    /** Called when a key frame is needed to resume. */
    var onNeedKeyframe: () -> Unit = {}

    init {
        setBackgroundColor(Color.BLACK)
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        addView(cleaned, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
    }

    fun start() {
        stop()
        running = true
        // Nothing on screen before the first retouched picture: no white flash.
        texture.alpha = 0.001f
        cleaned.setImageDrawable(null)
        surface?.let(::decode)
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        waiting.clear()
    }

    fun push(unit: ByteArray) {
        if (!running) return
        if (waiting.size >= MAX_WAITING) {
            // The decoder fell behind: start again from a key frame.
            waiting.clear()
            onNeedKeyframe()
        }
        waiting.offer(unit)
    }

    private fun decode(target: Surface) {
        worker = thread(name = "video-decoder") {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, START_WIDTH, START_HEIGHT)
                codec.configure(format, target, null, 0)
                codec.start()
                onNeedKeyframe()
                render(codec)
            } catch (e: RuntimeException) {
                // Video never ends the call: sound carries on.
                if (running) Log.w(TAG, "video: decoder failed (${e.javaClass.simpleName})")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "video: no decoder")
            } finally {
                codec?.let {
                    runCatching { it.stop() }
                    it.release()
                }
            }
        }
    }

    private fun render(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var pending: ByteArray? = null
        var frames = 0
        while (running) {
            if (pending == null) pending = waiting.poll(10, TimeUnit.MILLISECONDS)
            pending?.let { unit ->
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    if (unit.size <= buffer.capacity()) {
                        buffer.put(unit)
                        codec.queueInputBuffer(index, 0, unit.size, System.nanoTime() / 1000, 0)
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, 0)
                    }
                    pending = null
                }
            }
            while (true) {
                val ready = codec.dequeueOutputBuffer(info, 0)
                if (ready == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    resize(codec.outputFormat)
                    continue
                }
                if (ready < 0) break
                codec.releaseOutputBuffer(ready, true)
                if (frames++ == 0) Log.i(TAG, "video: first picture shown")
            }
        }
    }

    private fun resize(format: MediaFormat) {
        fun value(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        val left = value("crop-left")
        val width = if (left != null) value("crop-right")!! - left + 1 else value(MediaFormat.KEY_WIDTH) ?: return
        val top = value("crop-top")
        val height = if (top != null) value("crop-bottom")!! - top + 1 else value(MediaFormat.KEY_HEIGHT) ?: return
        Log.i(TAG, "video: $width x $height")
        post { fit(width, height) }
    }

    private fun fit(videoWidth: Int, videoHeight: Int) {
        if (width == 0 || height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        // In landscape Muse stands on the right and the text on the left; in portrait it is centered.
        val place = if (width > height) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        val fitted = LayoutParams((videoWidth * scale).toInt(), (videoHeight * scale).toInt(), place)
        if (width > height) fitted.marginEnd = Ui.dp(context, 96)
        texture.layoutParams = fitted
        cleaned.layoutParams = LayoutParams(fitted)
        if (frames?.firstOrNull()?.let { it.width != videoWidth || it.height != videoHeight } != false && !painting) {
            frames = Array(2) { Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888) }
            backdrop = Backdrop(videoWidth, videoHeight)
            pixels = IntArray(videoWidth * videoHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Before the first picture: the usual proportions of Muse calls.
        if (changed && visibility == View.VISIBLE) post { fit(START_WIDTH, START_HEIGHT) }
    }

    override fun onSurfaceTextureAvailable(available: SurfaceTexture, width: Int, height: Int) {
        val created = Surface(available)
        surface = created
        if (running) decode(created)
    }

    override fun onSurfaceTextureDestroyed(destroyed: SurfaceTexture): Boolean {
        val wasRunning = running
        stop()
        running = wasRunning
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(changed: SurfaceTexture, width: Int, height: Int) = Unit

    /**
     * Each decoded picture is read back, its light background blackened off the main thread, then shown. If the
     * computation is not finished, the picture is skipped.
     */
    override fun onSurfaceTextureUpdated(updated: SurfaceTexture) {
        val buffers = frames
        val cleaner = backdrop
        if (!running || painting) return
        if (buffers == null || cleaner == null) {
            texture.alpha = 1f
            return
        }
        val frame = buffers[next]
        texture.getBitmap(frame)
        painting = true
        painter.post {
            val work = pixels
            frame.getPixels(work, 0, frame.width, 0, 0, frame.width, frame.height)
            if (cleaner.blacken(work)) frame.setPixels(work, 0, frame.width, 0, 0, frame.width, frame.height)
            post {
                if (running) {
                    cleaned.setImageBitmap(frame)
                    cleaned.invalidate()
                    next = 1 - next
                }
                painting = false
            }
        }
    }
}
