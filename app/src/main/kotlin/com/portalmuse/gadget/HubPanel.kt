package com.portalmuse.gadget

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

private const val IDLE_CLOSE_MS = 60_000L

/** « Plus » : ce qu'on ouvre moins souvent, en grandes tuiles. */
class HubPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    class Tile(val icon: Int, val label: String, val run: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { close() }
    private val row = LinearLayout(activity).apply { gravity = Gravity.CENTER }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        addView(row, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, "Fermer").apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(40), dp(40), 0) },
        )
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun open(tiles: List<Tile>) {
        row.removeAllViews()
        for (tile in tiles) {
            val view = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = tile.label
                addView(Ui.round(activity, tile.icon, Ui.SURFACE, tile.label).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(40), dp(40), dp(40), dp(40))
                    isClickable = false
                    isFocusable = false
                }, LinearLayout.LayoutParams(dp(160), dp(160)))
                addView(Ui.label(activity, 24f).apply {
                    text = tile.label
                    gravity = Gravity.CENTER
                }, Ui.spaced(activity, top = 20))
                setOnClickListener {
                    close()
                    tile.run()
                }
            }
            row.addView(view, LinearLayout.LayoutParams(dp(240), WRAP_CONTENT))
        }
        panel.visibility = View.VISIBLE
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_CLOSE_MS)
        onVisibility()
    }

    fun close() {
        if (!isOpen) return
        main.removeCallbacks(idle)
        panel.visibility = View.GONE
        onVisibility()
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
