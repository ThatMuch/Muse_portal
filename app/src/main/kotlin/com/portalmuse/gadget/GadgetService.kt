package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.portalmuse.core.MuseProvisioning
import com.portalmuse.core.PairingSession
import com.portalmuse.core.ProvisionFailed
import com.portalmuse.core.SdkToken
import com.portalmuse.core.SetupController
import com.portalmuse.core.SetupNetwork
import org.json.JSONObject

private const val TAG = "PortalMuse"
private const val CHANNEL = "gadget"
private const val NOTIFICATION_ID = 1
private const val PAIRING_WINDOW_MS = 10 * 60_000L
/** Laisse au téléphone le temps de recevoir `auth_ok`. */
private const val CLOSE_AFTER_PAIRING_MS = 3_000L

/**
 * Service au premier plan : il garde la session Muse ouverte, et ouvre à la demande la fenêtre
 * d'appairage BLE.
 */
@SuppressLint("MissingPermission")
class GadgetService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var peripheral: BlePeripheral? = null
    private val timeout = Runnable { close("Fenêtre d'appairage expirée.") }
    private var pairing = false

    private lateinit var muse: MuseLink

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(MuseStatus.text))
        muse = MuseLink(
            this,
            onStatus = { status ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            },
            onRevoked = {
                if (!pairing) PairingStatus.publish(false, "Muse a retiré cet appareil. Réappairer depuis le menu.")
            },
        )
        muse.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(MuseStatus.text))
        when (intent?.action) {
            ACTION_START_PAIRING -> if (!pairing) open()
            ACTION_STOP_PAIRING -> close(null)
            ACTION_PAIRING_CHANGED -> muse.pairingChanged()
            // Sans intention : Android relance le service après la disparition du processus.
            null -> KioskReturn.reopen(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        muse.stop()
        release()
        super.onDestroy()
    }

    private fun open() {
        // L'app Muse refuse un gadget sans jeton SDK.
        val sdkToken = SecretStore(this).sdkToken?.takeIf(SdkToken::isValid)
            ?: return close("Saisir d'abord le jeton SDK Muse (menu de maintenance).")
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) return close("Bluetooth désactivé sur le Portal.")
        val settings = Settings(this)
        val name = settings.identity().bleName
        pairing = true
        PairingStatus.publish(true, "Ouverture de l'appairage…")
        // L'app Muse reconnaît le gadget à son nom Bluetooth : on le change le temps de l'appairage.
        if (adapter.name != name) {
            if (settings.savedBluetoothName == null) settings.savedBluetoothName = adapter.name
            if (!adapter.setName(name)) return close("Impossible de changer le nom Bluetooth.")
        }
        main.postDelayed(timeout, PAIRING_WINDOW_MS)
        awaitName(adapter, name, sdkToken, attempts = 30)
    }

    /** Le changement de nom est asynchrone : l'annonce attend qu'il soit effectif. */
    private fun awaitName(adapter: BluetoothAdapter, name: String, sdkToken: String, attempts: Int) {
        if (adapter.name == name) return startPeripheral(name, sdkToken)
        if (attempts == 0) return close("Le nom Bluetooth n'a pas changé.")
        main.postDelayed({ awaitName(adapter, name, sdkToken, attempts - 1) }, 100)
    }

    private fun startPeripheral(name: String, sdkToken: String) {
        val settings = Settings(this)
        val secrets = SecretStore(this)
        val identity = settings.identity()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        val created = BlePeripheral(
            this,
            onState = { state -> PairingStatus.publish(true, "Appairage ouvert : $name ($state)") },
            onFailure = { reason -> main.post { close("Appairage impossible : $reason.") } },
        )
        peripheral = created
        val controller = SetupController(
            pairing = PairingSession(identity.nodeId, identity.deviceId, identity.mac, version, sdkToken),
            identity = identity,
            version = version,
            transport = created,
            network = AndroidNetwork(this),
            provision = { credentials, commit ->
                val api = try {
                    MuseHttp.api(this)
                } catch (e: Exception) {
                    Log.e(TAG, "TLS indisponible : ${e.javaClass.simpleName}")
                    throw ProvisionFailed("auth_failed")
                }
                MuseProvisioning.verifyAndSave(
                    api, credentials, commit, sdkToken, identity.nodeId,
                    nowSeconds = System.currentTimeMillis() / 1000,
                    log = { Log.i(TAG, it) },
                    save = secrets::savePairing,
                )
            },
            onComplete = {
                muse.pairingChanged()
                main.postDelayed({ close("Portal appairé avec Muse.") }, CLOSE_AFTER_PAIRING_MS)
            },
            log = { Log.i(TAG, it) },
        )
        created.start(controller)
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        peripheral?.stop()
        peripheral = null
        restoreBluetoothName(this)
        pairing = false
    }

    private fun close(reason: String?) {
        Log.i(TAG, "appairage fermé${reason?.let { " : $it" } ?: ""}")
        release()
        PairingStatus.publish(false, reason)
    }

    private fun notification(status: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Gadget Muse", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Portal Muse")
            .setContentText("Session Muse : $status")
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START_PAIRING = "com.portalmuse.gadget.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.portalmuse.gadget.STOP_PAIRING"
        const val ACTION_CONNECT = "com.portalmuse.gadget.CONNECT"
        const val ACTION_PAIRING_CHANGED = "com.portalmuse.gadget.PAIRING_CHANGED"

        fun restoreBluetoothName(context: Context) {
            val settings = Settings(context)
            val saved = settings.savedBluetoothName ?: return
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
            if (adapter.isEnabled && adapter.setName(saved)) settings.savedBluetoothName = null
        }
    }
}

/** Le Portal est déjà en ligne : on ne propose que la connexion actuelle. */
private class AndroidNetwork(private val context: Context) : SetupNetwork {
    override fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun currentConnectionEntry(): JSONObject =
        JSONObject().put("ssid", "Use current connection").put("rssi", -40).put("secure", false)
}
