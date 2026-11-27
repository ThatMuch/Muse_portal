package com.portalmuse.gadget

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout

/** « Minuteur terminé » en grand, tant qu'il sonne. */
class TimerPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val title = Ui.label(activity, 56f).apply { gravity = Gravity.CENTER }
    private val names = Ui.label(activity, 30f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(names, Ui.spaced(activity, top = 16))
            addView(
                Ui.pill(activity, "Arrêter", Ui.ACCENT).apply { setOnClickListener { TimerCenter.stopRinging() } },
                LinearLayout.LayoutParams(Ui.dp(activity, 420), Ui.dp(activity, 96))
                    .apply { topMargin = Ui.dp(activity, 56) },
            )
        }
        addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun refresh() {
        val ringing = TimerCenter.ringing
        val shown = ringing.isNotEmpty()
        title.text = if (ringing.size > 1) "Minuteurs terminés" else "Minuteur terminé"
        names.text = ringing.mapNotNull { it.label }.joinToString("  ·  ")
        if (shown != isOpen) {
            panel.visibility = if (shown) View.VISIBLE else View.GONE
            onVisibility()
        }
    }
}
