package com.portalmuse.gadget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.util.Log

private const val TAG = "PortalMuse"

/**
 * Relance l'app après un redémarrage du Portal ou une mise à jour. La session Muse repart
 * toujours. Android 10 n'autorise l'ouverture de l'écran depuis l'arrière-plan que si
 * l'utilisateur a accordé « Afficher par-dessus les autres applications ».
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val booted = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!booted && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, if (booted) "démarrage du Portal" else "application mise à jour")
        // Un redémarrage pendant un appairage laisserait le nom Bluetooth provisoire.
        if (!PairingStatus.open) GadgetService.restoreBluetoothName(context)
        context.startForegroundService(Intent(context, GadgetService::class.java).setAction(GadgetService.ACTION_CONNECT))
        // Après une mise à jour, le kiosque ne revient que s'il était à l'écran.
        if (!booted) return KioskReturn.reopen(context, afterUpdate = true)
        if (SystemSettings.canDrawOverlays(context)) {
            context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Log.w(TAG, "écran au démarrage non autorisé : seule la session Muse est relancée")
        }
    }
}
