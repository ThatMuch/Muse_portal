package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.portalmuse.core.WebLinks
import com.portalmuse.core.tr

private const val IDLE_CLOSE_MS = 30 * 60_000L

/**
 * A web page continued from the phone or computer. It has no bridge to the app and no access to files, camera or
 * microphone, and only goes to public https addresses.
 */
class WebPanel(
    private val activity: Activity,
    root: FrameLayout,
    /** Asks Muse to bring this link to the phone. */
    private val onSend: (String) -> Unit,
    private val onVisibility: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { close() }
    private var web: WebView? = null
    private val content = FrameLayout(activity)
    private val heading = Ui.label(activity, 22f).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        visibility = View.GONE
        addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(104) })
        addView(
            heading,
            FrameLayout.LayoutParams(dp(640), WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(32), dp(48), 0, 0) },
        )
        addView(
            Ui.pill(activity, tr("To my phone", "Sur mon téléphone"), Ui.SURFACE).apply {
                setOnClickListener {
                    val address = web?.url ?: return@setOnClickListener
                    close()
                    onSend(address)
                }
            },
            FrameLayout.LayoutParams(WRAP_CONTENT, dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(32), dp(128), 0) },
        )
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr("Close", "Fermer"))
                .apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(32), dp(40), 0) },
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun open(url: String, title: String?) {
        destroy()
        val created = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.mediaPlaybackRequiresUserGesture = true
            setOnTouchListener { _, _ ->
                touched()
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !WebLinks.allowed(request.url.toString())
            }
        }
        web = created
        content.addView(created, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        heading.text = title ?: Uri.parse(url).host.orEmpty()
        created.loadUrl(url)
        panel.visibility = View.VISIBLE
        touched()
        onVisibility()
    }

    /** True if it went back one page. */
    fun back(): Boolean {
        val page = web ?: return false
        if (!page.canGoBack()) return false
        page.goBack()
        return true
    }

    fun close() {
        if (!isOpen) return
        destroy()
        panel.visibility = View.GONE
        onVisibility()
    }

    private fun touched() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_CLOSE_MS)
    }

    private fun destroy() {
        main.removeCallbacks(idle)
        content.removeAllViews()
        web?.destroy()
        web = null
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
