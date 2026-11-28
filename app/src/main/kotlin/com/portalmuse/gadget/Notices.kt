package com.portalmuse.gadget

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.portalmuse.core.Notice

private const val MAX_NOTICES = 3

/** Muse's banners, at the top of the screen, above everything. A touch removes a banner. */
class Notices(private val activity: Activity, root: FrameLayout, private val onChange: () -> Unit) {
    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        root.addView(
            column,
            // The top of the Portal's screen is taken by the system overlay.
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(56) },
        )
    }

    /** A banner and its automatic removal. */
    private class Timed(val urgent: Boolean, val millis: Long, val expire: Runnable)

    private val cards get() = (0 until column.childCount).map { column.getChildAt(it) }

    /** An urgent banner waits to be seen: the screen must light up. */
    val hasUrgent get() = cards.any { (it.tag as Timed).urgent }

    /**
     * Banners wait while a question is on screen, which they must not hide, or while the light is off, where
     * nobody would see them. Their duration then starts over.
     */
    var held = false
        set(value) {
            if (field == value) return
            field = value
            column.visibility = if (value) View.GONE else View.VISIBLE
            for (card in cards) {
                val timed = card.tag as Timed
                column.removeCallbacks(timed.expire)
                if (!value) column.postDelayed(timed.expire, timed.millis)
            }
        }

    fun show(notice: Notice) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(20))
            val color = if (notice.urgent) Ui.DANGER else Ui.ACCENT
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Ui.SURFACE)
                // A red edge for what is urgent.
                if (notice.urgent) setStroke(dp(2), color)
            }
            elevation = dp(8).toFloat()
            // A colored dot, then the title or Muse's name.
            val heading = SpannableString("●  " + (notice.title ?: Settings(activity).museName ?: "Muse"))
            heading.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            addView(Ui.label(activity, 18f, Ui.MUTED).apply { text = heading })
            addView(Ui.label(activity, 26f).apply { text = notice.message }, Ui.spaced(activity, top = 6))
        }
        card.setOnClickListener { remove(card) }
        while (column.childCount >= MAX_NOTICES) remove(column.getChildAt(0))
        val timed = Timed(notice.urgent, notice.durationMillis) { remove(card) }
        card.tag = timed
        column.addView(card, LinearLayout.LayoutParams(dp(960), WRAP_CONTENT).apply { bottomMargin = dp(12) })
        if (!held) column.postDelayed(timed.expire, timed.millis)
        if (notice.urgent) Chime.alert()
        onChange()
    }

    fun clear() {
        for (card in cards) column.removeCallbacks((card.tag as Timed).expire)
        column.removeAllViews()
    }

    private fun remove(card: View) {
        if (card.parent !== column) return
        column.removeCallbacks((card.tag as Timed).expire)
        column.removeView(card)
        onChange()
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
