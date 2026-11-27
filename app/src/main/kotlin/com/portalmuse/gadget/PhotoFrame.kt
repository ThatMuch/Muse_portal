package com.portalmuse.gadget

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock

private const val PHOTO_MS = 20_000L
private const val FADE_MS = 1_200L

/** Le cadre photo : un diaporama et l'heure, tant que personne ne touche l'écran. */
class PhotoFrame(private val activity: Activity, root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var photos: Frame? = null
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

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(clock, placed(Gravity.BOTTOM or Gravity.START))
        addView(caption, placed(Gravity.BOTTOM or Gravity.END))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    /** Sans effet si ces mêmes photos défilent déjà. */
    fun open(frame: Frame) {
        val same = photos?.photos?.map { it.first to it.first.lastModified() } ==
            frame.photos.map { it.first to it.first.lastModified() }
        if (isOpen && same) return
        photos = frame
        index = 0
        panel.visibility = View.VISIBLE
        render()
    }

    fun close() {
        if (!isOpen) return
        main.removeCallbacks(next)
        panel.visibility = View.GONE
        image.setImageDrawable(null)
        photos = null
    }

    private fun step() {
        val count = photos?.photos?.size ?: return
        index = (index + 1) % count
        render()
    }

    private fun render() {
        val shown = photos?.photos ?: return
        val (file, text) = shown[index % shown.size]
        image.setImageBitmap(BitmapFactory.decodeFile(file.path))
        image.alpha = 0f
        image.animate().alpha(1f).setDuration(FADE_MS).start()
        caption.text = text.orEmpty()
        main.removeCallbacks(next)
        main.postDelayed(next, PHOTO_MS)
    }

    private fun shadow(view: android.widget.TextView) {
        view.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        // Sans marge intérieure, l'ombre est coupée net et dessine un rectangle.
        val room = Ui.dp(activity, 16)
        view.setPadding(room, room, room, room)
    }

    private fun placed(position: Int) = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, position).apply {
        val margin = Ui.dp(activity, 16)
        setMargins(margin, margin, margin, margin)
    }
}
