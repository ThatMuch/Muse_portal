package com.portalmuse.gadget

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.io.IOException

private const val TAG = "PortalMuse"

/**
 * The Muse's avatar: the still picture and, on top, the video of the current state, looping and muted. There is
 * no "speaking" video: the picture breathes gently instead. Players only run while the view is [active].
 */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    enum class State { IDLE, WORKING, SPEAKING }

    private val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val video = TextureView(context).apply { alpha = 0f }
    private var files: AvatarFiles? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private val pulse = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f),
    ).apply {
        duration = 450
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }

    var state = State.IDLE
        set(value) {
            field = value
            apply()
        }

    var active = false
        set(value) {
            field = value
            if (value) reload() else apply()
        }

    init {
        addView(still, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(video, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        video.surfaceTextureListener = this
        // The bottom fades into the black of the panel, so the character is not cut off sharply.
        val fade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Ui.PANEL),
            )
        }
        addView(fade, LayoutParams(MATCH_PARENT, Ui.dp(context, 72), Gravity.BOTTOM))
        visibility = View.GONE
    }

    /** Reads the files again; the view hides when there is no avatar. */
    fun reload() {
        files = AvatarStore.current(context)
        val image = files?.image
        visibility = if (image == null) View.GONE else View.VISIBLE
        still.setImageBitmap(image?.let(::decode))
        release()
        apply()
    }

    private fun decode(file: File) = BitmapFactory.Options().let { options ->
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, options)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 1024) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        BitmapFactory.decodeFile(file.path, options)
    }

    private fun apply() {
        if (active && state == State.SPEAKING) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            scaleX = 1f
            scaleY = 1f
        }
        val wanted = if (!active) null else when (state) {
            State.WORKING -> files?.working ?: files?.idle
            else -> files?.idle
        }
        if (wanted == null) return release()
        if (wanted != playing) play(wanted)
    }

    private fun play(file: File) {
        val target = surface ?: return
        release()
        val created = MediaPlayer()
        player = created
        playing = file
        try {
            created.setDataSource(file.path)
            created.setSurface(target)
            created.isLooping = true
            created.setVolume(0f, 0f)
            created.setOnVideoSizeChangedListener { _, width, height -> crop(width, height) }
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) video.alpha = 1f
                false
            }
            created.setOnPreparedListener { it.start() }
            created.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "avatar: unreadable video ($what, $extra)")
                video.alpha = 0f
                true
            }
            created.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "avatar: unreadable video")
            release()
        } catch (e: IllegalStateException) {
            release()
        }
    }

    /** Fills the square without distortion. */
    private fun crop(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || video.width == 0 || video.height == 0) return
        val scale = maxOf(video.width / width.toFloat(), video.height / height.toFloat())
        video.setTransform(
            Matrix().apply {
                setScale(
                    width * scale / video.width, height * scale / video.height,
                    video.width / 2f, video.height / 2f,
                )
            },
        )
    }

    private fun release() {
        player?.release()
        player = null
        playing = null
        video.alpha = 0f
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        apply()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        release()
        pulse.cancel()
        super.onDetachedFromWindow()
    }
}
