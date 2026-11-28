package com.portalmuse.gadget

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import com.portalmuse.core.tr

/** The ringing alarm: the time in large type, "Snooze" and "Stop". */
class AlarmPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val label = Ui.label(activity, 32f, Ui.MUTED).apply { gravity = Gravity.CENTER }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        val clock = TextClock(activity).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 112f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(clock)
            addView(label)
            val snooze = Ui.pill(activity, tr("9 more minutes", "Encore 9 minutes"), Ui.SURFACE)
                .apply { setOnClickListener { AlarmCenter.snooze() } }
            val stop = Ui.pill(activity, tr("Stop", "Arrêter"), Ui.ACCENT)
                .apply { setOnClickListener { AlarmCenter.stopRinging() } }
            val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER }
            for (button in listOf(snooze, stop)) {
                buttons.addView(
                    button,
                    LinearLayout.LayoutParams(dp(380), dp(104)).apply { setMargins(dp(16), 0, dp(16), 0) },
                )
            }
            addView(buttons, Ui.spaced(activity, top = 48))
        }
        addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun refresh() {
        val alarm = AlarmCenter.ringing
        label.text = alarm?.label.orEmpty()
        if ((alarm != null) != isOpen) {
            panel.visibility = if (alarm != null) View.VISIBLE else View.GONE
            onVisibility()
        }
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
