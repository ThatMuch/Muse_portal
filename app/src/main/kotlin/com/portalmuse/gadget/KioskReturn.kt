package com.portalmuse.gadget

import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.util.Log
import com.portalmuse.core.RestartGuard

private const val TAG = "PortalMuse"

/**
 * Rouvre le kiosque quand l'application a disparu pendant qu'il était à l'écran : plantage,
 * processus tué ou mise à jour. Quitter le kiosque ou passer à l'écran du Portal ne le fait pas
 * revenir. Demande l'autorisation « Afficher par-dessus les autres applications ».
 */
object KioskReturn {
    private fun prefs(context: Context) = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** Écrit tout de suite : un plantage peut suivre. */
    fun inFront(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean("front", shown).commit()
    }

    /** [afterUpdate] : une mise à jour n'est pas une panne et ne compte pas dans la limite. */
    fun reopen(context: Context, afterUpdate: Boolean = false) {
        val prefs = prefs(context)
        if (!prefs.getBoolean("front", false)) return
        if (!SystemSettings.canDrawOverlays(context)) return
        if (!afterUpdate) {
            val history = prefs.getString("reopened", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            val next = RestartGuard.allow(history, System.currentTimeMillis())
            if (next == null) {
                Log.w(TAG, "kiosque : trop de réouvertures, on s'arrête là")
                return
            }
            prefs.edit().putString("reopened", next.joinToString(",")).commit()
        }
        Log.i(TAG, "kiosque : réouverture")
        context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
