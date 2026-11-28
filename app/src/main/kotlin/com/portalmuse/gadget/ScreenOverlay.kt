package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import com.portalmuse.core.LoadedPicture
import kotlin.math.abs

private const val SLIDE_MS = 8_000L

/**
 * What Muse shows: pictures as a slideshow (touch left or right, or swipe), or a page. Can always be closed
 * with a touch on "Close".
 */
class ScreenOverlay(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var pictures = emptyList<LoadedPicture>()
    private var index = 0
    private var page: WebView? = null
    private val advance = Runnable { step(1) }
    private val dismiss = Runnable { close() }

    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title = chip(24f)
    private val caption = chip(22f)
    private val counter = chip(18f)
    private val content = FrameLayout(activity)

    private val gestures = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (event.x < image.width / 3f) step(-1) else if (event.x > image.width * 2 / 3f) step(1)
                return true
            }

            override fun onFling(first: MotionEvent?, second: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy)) step(if (vx < 0) 1 else -1)
                return true
            }
        },
    )

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        visibility = View.GONE
        addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(title, placed(Gravity.TOP or Gravity.START))
        addView(caption, placed(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(counter, placed(Gravity.BOTTOM or Gravity.END))
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr(
                "Close",
                "Fermer",
            )).apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(24), dp(24), dp(24), 0)
            },
        )
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    /** Keeps the buttons of an ongoing call or conversation visible at the bottom. */
    var bottomRoom = false
        set(value) {
            field = value
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = if (value) dp(112) else 0
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    fun showPictures(shown: List<LoadedPicture>, heading: String?, durationMillis: Long) {
        reset()
        pictures = shown
        index = 0
        content.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        image.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        open(heading, durationMillis)
        render()
    }

    fun showPage(html: String, heading: String?, durationMillis: Long) {
        reset()
        val web = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            // A page written remotely: no script, network, files or navigation.
            settings.javaScriptEnabled = false
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            isLongClickable = false
            setOnLongClickListener { true }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
        }
        page = web
        content.addView(
            web,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(100) },
        )
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        open(heading, durationMillis)
    }

    fun close() {
        if (!isOpen) return
        reset()
        panel.visibility = View.GONE
        onVisibility()
    }

    private fun open(heading: String?, durationMillis: Long) {
        show(title, heading)
        panel.visibility = View.VISIBLE
        main.postDelayed(dismiss, durationMillis)
        onVisibility()
    }

    private fun reset() {
        main.removeCallbacks(advance)
        main.removeCallbacks(dismiss)
        pictures = emptyList()
        image.setImageDrawable(null)
        content.removeAllViews()
        page?.destroy()
        page = null
        show(caption, null)
        show(counter, null)
    }

    private fun step(direction: Int) {
        if (pictures.isEmpty()) return
        index = (index + direction + pictures.size) % pictures.size
        render()
    }

    private fun render() {
        val picture = pictures[index]
        image.setImageBitmap(picture.image as Bitmap)
        show(caption, picture.caption)
        show(counter, if (pictures.size > 1) "${index + 1} / ${pictures.size}" else null)
        main.removeCallbacks(advance)
        if (pictures.size > 1) main.postDelayed(advance, SLIDE_MS)
    }

    private fun show(view: android.widget.TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int) = Ui.dp(activity, value)

    private fun chip(size: Float) = Ui.label(activity, size).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xB0000000.toInt())
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
        maxWidth = dp(900)
        visibility = View.GONE
    }

    private fun placed(position: Int) = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, position).apply {
        setMargins(dp(24), dp(32), dp(24), dp(16))
    }
}
