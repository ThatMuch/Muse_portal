package com.portalmuse.gadget

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Turns the light off: backlight at zero and black screen. Nothing else changes: the kiosk stays in front and
 * the screen "on" for Android, which therefore does not put the Portal to sleep; the Muse session and "Hey
 * Muse" carry on. No permission needed.
 */
class NightDimmer(private val activity: Activity, root: FrameLayout) {
    private val veil = View(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }

    init {
        root.addView(veil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    var dimmed = false
        set(value) {
            if (field == value) return
            field = value
            veil.visibility = if (value) View.VISIBLE else View.GONE
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = if (value) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
}
