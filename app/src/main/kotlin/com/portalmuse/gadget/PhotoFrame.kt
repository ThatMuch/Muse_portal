package com.portalmuse.gadget

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import java.io.File

private const val PHOTO_MS = 20_000L
private const val INFO_MS = 15_000L
private const val FADE_MS = 1_200L
private const val PHOTOS_BETWEEN = 2

/** Something Muse wants noticed: a card, or a suggestion of its feed. */
data class Info(
    val kind: String, val icon: String?, val title: String, val text: String?, val important: Boolean = false,
    val picture: File? = null,
)

/**
 * The idle screen, as long as nobody touches it: the photos and the time, with what Muse has to say every two
 * photos. Without photos, it shows that alone.
 */
class PhotoFrame(private val activity: Activity, root: FrameLayout) {
    private sealed class Slide {
        data class Photo(val file: File, val changed: Long, val caption: String?) : Slide()
        data class News(val info: Info) : Slide()
    }

    private val main = Handler(Looper.getMainLooper())
    private var slides = emptyList<Slide>()
    private var index = 0
    private val next = Runnable { step() }

    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val caption = Ui.label(activity, 22f).apply { shadow(this) }
    private val clock = TextClock(activity).apply {
        format24Hour = "HH:mm"
        format12Hour = "HH:mm"
        textSize = 64f
        setTextColor(Color.WHITE)
        shadow(this)
    }
    private val news = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(120), 0, dp(120), 0)
    }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(news, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        addView(clock, placed(Gravity.BOTTOM or Gravity.START))
        addView(caption, placed(Gravity.BOTTOM or Gravity.END))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    /** No effect if the same slides are already playing. */
    fun open(frame: Frame?, infos: List<Info>) {
        val wanted = arrange(frame?.photos.orEmpty().map { Slide.Photo(it.first, it.first.lastModified(), it.second) }, infos)
        if (wanted.isEmpty()) return close()
        if (isOpen && wanted == slides) return
        slides = wanted
        index = 0
        panel.visibility = View.VISIBLE
        render()
    }

    private fun arrange(photos: List<Slide.Photo>, infos: List<Info>): List<Slide> {
        if (infos.isEmpty()) return photos
        if (photos.isEmpty()) return infos.map { Slide.News(it) }
        // Enough rounds for every photo and every piece of news to come by.
        val rounds = maxOf(infos.size, (photos.size + PHOTOS_BETWEEN - 1) / PHOTOS_BETWEEN)
        return (0 until rounds).flatMap { round ->
            (0 until PHOTOS_BETWEEN).map { photos[(round * PHOTOS_BETWEEN + it) % photos.size] } +
                Slide.News(infos[round % infos.size])
        }
    }

    fun close() {
        if (!isOpen) return
        main.removeCallbacks(next)
        panel.visibility = View.GONE
        image.setImageDrawable(null)
        news.removeAllViews()
        slides = emptyList()
    }

    private fun step() {
        if (slides.isEmpty()) return
        index = (index + 1) % slides.size
        render()
    }

    private fun render() {
        val slide = slides.getOrNull(index) ?: return
        news.removeAllViews()
        val shown: View
        when (slide) {
            is Slide.Photo -> {
                image.setImageBitmap(BitmapFactory.decodeFile(slide.file.path))
                caption.text = slide.caption.orEmpty()
                shown = image
            }
            is Slide.News -> {
                image.setImageDrawable(null)
                caption.text = ""
                write(slide.info)
                shown = news
            }
        }
        shown.alpha = 0f
        shown.animate().alpha(1f).setDuration(FADE_MS).start()
        main.removeCallbacks(next)
        if (slides.size > 1) main.postDelayed(next, if (slide is Slide.Photo) PHOTO_MS else INFO_MS)
    }

    private fun write(info: Info) {
        fun line(text: String, size: Float, color: Int, top: Int) = Ui.label(activity, size, color).apply {
            this.text = text
            gravity = Gravity.CENTER
            maxLines = 3
            news.addView(this, Ui.spaced(activity, top = top))
        }
        info.picture?.let { file ->
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            val view = ImageView(activity).apply {
                setImageBitmap(BitmapFactory.decodeFile(file.path, options))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            news.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, dp(260)))
        }
        line(info.kind, 20f, if (info.important) Ui.DANGER else Ui.ACCENT, 16)
        if (info.picture == null) info.icon?.let { line(it, 72f, Color.WHITE, 8) }
        line(info.title, 44f, Color.WHITE, 8).typeface = Typeface.DEFAULT_BOLD
        info.text?.let { line(it, 28f, Ui.MUTED, 12) }
    }

    private fun shadow(view: android.widget.TextView) {
        view.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        // Without inner padding, the shadow is cut off sharply and draws a rectangle.
        val room = Ui.dp(activity, 16)
        view.setPadding(room, room, room, room)
    }

    private fun placed(position: Int) = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, position).apply {
        val margin = Ui.dp(activity, 16)
        setMargins(margin, margin, margin, margin)
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
