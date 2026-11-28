package com.portalmuse.gadget

import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.util.Log
import com.portalmuse.core.RestartGuard

private const val TAG = "PortalMuse"

/**
 * Reopens the kiosk when the app disappeared while it was on screen: crash, killed process or update. Leaving
 * the kiosk or switching to the Portal's screen does not bring it back. Needs the "Display over other apps"
 * permission.
 */
object KioskReturn {
    private fun prefs(context: Context) = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** Written at once: a crash may follow. */
    fun inFront(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean("front", shown).commit()
    }

    /** Brings the kiosk to the front for something that cannot wait, such as an alarm. */
    fun show(context: Context) {
        if (!SystemSettings.canDrawOverlays(context)) return
        context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** [afterUpdate]: an update is not a failure and does not count toward the limit. */
    fun reopen(context: Context, afterUpdate: Boolean = false) {
        val prefs = prefs(context)
        if (!prefs.getBoolean("front", false)) return
        if (!SystemSettings.canDrawOverlays(context)) return
        if (!afterUpdate) {
            val history = prefs.getString("reopened", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            val next = RestartGuard.allow(history, System.currentTimeMillis())
            if (next == null) {
                Log.w(TAG, "kiosk: too many reopenings, stopping there")
                return
            }
            prefs.edit().putString("reopened", next.joinToString(",")).commit()
        }
        Log.i(TAG, "kiosk: reopening")
        context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
