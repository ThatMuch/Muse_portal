package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import com.portalmuse.core.AppPage

private const val IDLE_CLOSE_MS = 10 * 60_000L

/**
 * The small apps: their list as tiles, and the open one, locked in a view with no network, no files, no
 * microphone or camera, and no bridge to the Portal.
 */
class AppPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var page: WebView? = null
    private val idle = Runnable { close() }
    private val content = FrameLayout(activity)
    private val title = Ui.label(activity, 24f, Ui.MUTED)
    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(112) })
        addView(
            title,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(48), dp(56), 0, 0) },
        )
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr(
                "Close",
                "Fermer",
            )).apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(40), dp(40), 0) },
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
                bottomMargin = if (value) dp(140) else 0
            }
        }

    /** A touch postpones the automatic closing. */
    fun touched() {
        if (!isOpen) return
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_CLOSE_MS)
    }

    fun showList() {
        reset()
        title.text = tr("Apps", "Applications")
        val grid = GridLayout(activity).apply { columnCount = 4 }
        for (app in AppStore.list(activity)) {
            val tile = Ui.pill(activity, app.title, Ui.SURFACE).apply {
                setOnClickListener { open(app.name) }
                setOnLongClickListener {
                    confirmRemoval(app.name, app.title)
                    true
                }
            }
            grid.addView(
                tile,
                GridLayout.LayoutParams().apply {
                    width = dp(270)
                    height = dp(160)
                    setMargins(dp(12), dp(12), dp(12), dp(12))
                },
            )
        }
        val column = FrameLayout(activity).apply {
            setPadding(dp(36), 0, dp(36), dp(24))
            addView(grid)
        }
        content.addView(ScrollView(activity).apply { addView(column) })
        content.addView(
            Ui.label(activity, 18f, Ui.FAINT).apply { text = tr(
                "Hold a tile to delete it",
                "Appui long sur une tuile pour la supprimer",
            ) },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(24) },
        )
        show()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun open(name: String): Boolean {
        val app = AppStore.list(activity).firstOrNull { it.name == name } ?: return false
        val html = AppStore.html(activity, name) ?: return false
        reset()
        title.text = app.title
        val web = WebView(activity).apply {
            setBackgroundColor(Ui.PANEL)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            setOnTouchListener { _, _ ->
                touched()
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
        }
        page = web
        content.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadDataWithBaseURL("${AppStore.origin(name)}/", AppPage.wrap(html), "text/html", "utf-8", null)
        show()
        return true
    }

    fun close() {
        if (!isOpen) return
        reset()
        panel.visibility = View.GONE
        onVisibility()
    }

    private fun show() {
        panel.visibility = View.VISIBLE
        touched()
        onVisibility()
    }

    private fun reset() {
        main.removeCallbacks(idle)
        content.removeAllViews()
        page?.destroy()
        page = null
    }

    private fun confirmRemoval(name: String, label: String) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(tr("Delete “$label”?", "Supprimer « $label » ?")).setMessage(tr(
                "Its data is erased too.",
                "Ses données sont effacées aussi.",
            ))
            .setPositiveButton(tr("Delete", "Supprimer")) { _, _ ->
                AppStore.remove(activity, name)
                if (AppStore.list(activity).isEmpty()) close() else showList()
            }
            .setNegativeButton(tr("Cancel", "Annuler"), null)
            .show()
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
