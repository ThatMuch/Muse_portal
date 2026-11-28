package com.portalmuse.gadget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.util.Log

private const val TAG = "PortalMuse"

/**
 * Starts the app again after a Portal reboot or an update. The Muse session always restarts. Android 10 only
 * lets an app open a screen from the background if the user granted "Display over other apps".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val booted = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!booted && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, if (booted) "Portal startup" else "app updated")
        // A reboot during pairing would leave the temporary Bluetooth name in place.
        if (!PairingStatus.open) GadgetService.restoreBluetoothName(context)
        context.startForegroundService(Intent(context, GadgetService::class.java).setAction(GadgetService.ACTION_CONNECT))
        // After an update, the kiosk only comes back if it was on screen.
        if (!booted) return KioskReturn.reopen(context, afterUpdate = true)
        if (SystemSettings.canDrawOverlays(context)) {
            context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Log.w(TAG, "screen at startup not allowed: only the Muse session is restarted")
        }
    }
}
