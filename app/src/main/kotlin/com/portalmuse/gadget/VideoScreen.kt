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
/** La taille annoncée au départ ; le flux donne ensuite la sienne. */
private const val START_WIDTH = 448
private const val START_HEIGHT = 768

/**
 * La vidéo de Muse pendant un appel : décodage H.264 matériel dans une vue qui garde les
 * proportions. [push] reçoit des images complètes depuis n'importe quel fil.
 */
class VideoScreen(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    /** L'image retouchée, posée par-dessus la vidéo brute. */
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

    /** Appelé quand une image clé est nécessaire pour reprendre. */
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
        // Rien à l'écran avant la première image retouchée : pas d'éclair blanc.
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
            // Le décodeur n'a pas suivi : repartir d'une image clé.
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
                // La vidéo ne termine jamais l'appel : le son continue.
                if (running) Log.w(TAG, "vidéo : décodeur en échec (${e.javaClass.simpleName})")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "vidéo : aucun décodeur")
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
                if (frames++ == 0) Log.i(TAG, "vidéo : première image affichée")
            }
        }
    }

    private fun resize(format: MediaFormat) {
        fun value(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        val left = value("crop-left")
        val width = if (left != null) value("crop-right")!! - left + 1 else value(MediaFormat.KEY_WIDTH) ?: return
        val top = value("crop-top")
        val height = if (top != null) value("crop-bottom")!! - top + 1 else value(MediaFormat.KEY_HEIGHT) ?: return
        Log.i(TAG, "vidéo : $width x $height")
        post { fit(width, height) }
    }

    private fun fit(videoWidth: Int, videoHeight: Int) {
        if (width == 0 || height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        // En paysage Muse se tient à droite et le texte à gauche ; en portrait il est au centre.
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
        // Avant la première image : les proportions habituelles des appels de Muse.
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
     * Chaque image décodée est relue, son fond clair noirci hors du fil principal, puis affichée.
     * Si le calcul n'a pas fini, l'image est sautée.
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
