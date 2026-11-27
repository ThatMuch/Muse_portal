package com.portalmuse.gadget

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Éteint la lumière : rétroéclairage à zéro et écran noir. Rien d'autre ne change : le kiosque
 * reste devant et l'écran « allumé » pour Android, qui ne met donc pas le Portal en veille ; la
 * session Muse et « Hey Muse » continuent. Aucune permission requise.
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
