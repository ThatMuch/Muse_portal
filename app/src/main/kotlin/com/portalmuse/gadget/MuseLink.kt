package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.portalmuse.core.AppCommands
import com.portalmuse.core.ApprovedDevices
import com.portalmuse.core.AssistantCommands
import com.portalmuse.core.BluetoothCommands
import com.portalmuse.core.CameraCommands
import com.portalmuse.core.CommandSet
import com.portalmuse.core.ConnectionState
import com.portalmuse.core.DeviceCommands
import com.portalmuse.core.DeviceDescription
import com.portalmuse.core.DisplayCommands
import com.portalmuse.core.FeedCommands
import com.portalmuse.core.HomeCommands
import com.portalmuse.core.LinkSession
import com.portalmuse.core.MuseChat
import com.portalmuse.core.MuseConnection
import com.portalmuse.core.OkHttpFrameSocket
import com.portalmuse.core.PairingRecord
import com.portalmuse.core.PairingStore
import com.portalmuse.core.SessionFactory
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val DISPLAY_NAME = "Portal Muse"
private const val MODEL_ID = "portalmuse-portal-10"
private const val MODEL_NAME = "Portal 10 inch (DIY client)"
private const val AVATAR_REFRESH_MS = 30 * 60_000L

/** State of the Muse session, for the "Information" screen and the notification. */
object MuseStatus {
    @Volatile
    var text = tr("not started", "pas démarrée")
}

/** The current session, to chat from the screen. */
object MuseSession {
    @Volatile
    private var current: Pair<LinkSession, String>? = null

    fun attach(session: LinkSession, nodeId: String) {
        current = session to nodeId
    }

    fun clear() {
        current = null
    }

    /** The open session, if there is one. */
    fun live(): LinkSession? = current?.first?.takeIf { it.isOpen }

    /** `null` if no session is open. [sideChat]: a separate conversation. */
    fun chat(sideChat: String? = null): MuseChat? = current?.takeIf { it.first.isOpen }?.let {
        MuseChat(it.first, it.second, log = { line -> Log.i(TAG, "conversation: $line") }, sideChat = sideChat)
    }
}

/** The permanent connection to Muse: a dedicated thread, woken by network changes. */
class MuseLink(context: Context, private val onStatus: (String) -> Unit, private val onRevoked: () -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val secrets = SecretStore(this.context)
    // Several threads: a pending question or loading pictures does not delay the rest.
    private val commandExecutor = Executors.newFixedThreadPool(4)
    private var connection: MuseConnection? = null
    private var revoked = false
    private var network: Network? = null

    /** The avatar can change every day: checked at connection, then every 30 minutes. */
    private val refreshAvatar = object : Runnable {
        override fun run() {
            MuseSession.live()?.let { session -> thread(name = "muse-avatar") { AvatarStore.sync(context, session) } }
            main.postDelayed(this, AVATAR_REFRESH_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            // Network change: the old session is dead without knowing it.
            if (network != null && network != available) connection?.drop()
            network = available
            connection?.wake()
        }

        override fun onLost(lost: Network) {
            Log.i(TAG, "network lost")
            network = null
            connection?.drop()
        }
    }

    fun start() {
        val api = try {
            MuseHttp.api(context)
        } catch (e: Exception) {
            Log.e(TAG, "TLS unavailable: ${e.javaClass.simpleName}")
            return publish(tr("TLS unavailable on this device", "TLS indisponible sur cet appareil"))
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        val guide = context.assets.open("guide.md").bufferedReader().use { it.readText() }
        val sessions = SessionFactory { url, bearer, nodeId, onRegistered ->
            val client = try {
                MuseHttp.client
            } catch (e: Exception) {
                throw IOException("TLS unavailable")
            }
            val socket = OkHttpFrameSocket.connect(client, url, bearer, MuseHttp.userAgent(context))
            // Rebuilt for each session: the declaration follows the current permissions.
            val screen = ScreenBridge(context)
            val onScreen = if (Settings(context).screenAllowed) {
                arrayOf(
                    DisplayCommands(screen),
                    AssistantCommands(screen) { Clock.minuteOfDay() to System.currentTimeMillis() },
                )
            } else {
                emptyArray()
            }
            val settings = Settings(context)
            val bluetooth: List<CommandSet> = if (settings.bluetoothAllowed) {
                val approved = object : ApprovedDevices {
                    override fun contains(address: String) = address in settings.bluetoothDevices
                    override fun add(address: String, name: String?) = settings.rememberBluetoothDevice(address, name)
                }
                listOf(BluetoothCommands(BleCentral.radio(context), approved, screen::ask))
            } else {
                emptyList()
            }
            val camera = if (settings.cameraAllowed && settings.screenAllowed) listOf(CameraCommands(screen)) else emptyList()
            val apps = if (settings.screenAllowed) listOf(AppCommands(screen), FeedCommands(screen)) else emptyList()
            val extra = (listOf<CommandSet>(HomeCommands(screen)) + apps + bluetooth + camera).toTypedArray()
            val commands = DeviceCommands({ health(version) }, guide, *onScreen, *extra, capabilities = board())
            lateinit var session: LinkSession
            session = LinkSession(
                socket, DeviceDescription(nodeId, DISPLAY_NAME, version, MODEL_ID, MODEL_NAME),
                commands, commandExecutor,
                onRegistered = {
                    MuseSession.attach(session, nodeId)
                    onRegistered()
                    // Off the receiving thread, which must stay free to read the answer.
                    thread(name = "muse-name") {
                        val crash = CrashRecorder.pending(context)
                        if (crash != null && Settings(context).crashReports && session.report(crash.message())) {
                            CrashRecorder.sent(context)
                        }
                        MuseSession.chat()?.museName()?.let { Settings(context).museName = it }
                    }
                    main.removeCallbacks(refreshAvatar)
                    main.post(refreshAvatar)
                },
                log = { Log.i(TAG, it) },
            )
            session
        }
        val store = object : PairingStore {
            override fun load(): PairingRecord? = secrets.pairing
            override fun replace(expected: PairingRecord, updated: PairingRecord?) =
                secrets.replacePairing(expected, updated)
        }
        val created = MuseConnection(
            store, api, sessions, sdkToken = { secrets.sdkToken },
            onState = ::onState, log = { Log.i(TAG, it) },
        )
        connection = created
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        thread(name = "muse-link") { created.run() }
    }

    /** The pairing has just been redone or erased. */
    fun pairingChanged() {
        revoked = false
        connection?.drop()
        connection?.wake()
    }

    fun stop() {
        connection?.let {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            it.stop()
        }
        connection = null
        main.removeCallbacks(refreshAvatar)
        BleCentral.closeAll()
        MuseSession.clear()
        commandExecutor.shutdown()
    }

    private fun onState(state: ConnectionState) {
        if (state != ConnectionState.Connected) ScreenBridge.cancelQuestion()
        val text = when (state) {
            ConnectionState.NotPaired -> if (revoked) tr(
                "pairing removed by Muse",
                "appairage retiré par Muse",
            ) else tr("not paired", "non appairé")
            ConnectionState.Connecting -> tr("connecting", "connexion en cours")
            ConnectionState.Connected -> {
                revoked = false
                tr(
                    "connected since ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}",
                    "connectée depuis ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}",
                )
            }
            is ConnectionState.Waiting -> tr(
                "retrying in ${state.delayMillis / 1000} s (${state.reason})",
                "nouvel essai dans ${state.delayMillis / 1000} s (${state.reason})",
            )
            ConnectionState.Revoked -> {
                revoked = true
                AvatarStore.clear(context)
                FrameStore.clear(context)
                AppStore.clear(context)
                FeedStore.clear(context)
                Settings(context).forgetChatSession()
                BleCentral.closeAll()
                Settings(context).forgetBluetoothDevices()
                Settings(context).customShortcuts = emptyList()
                main.post(onRevoked)
                tr("pairing removed by Muse", "appairage retiré par Muse")
            }
        }
        publish(text)
    }

    private fun publish(text: String) {
        MuseStatus.text = text
        main.post { onStatus(text) }
    }

    /** The description of the screen, added to the guide so that Muse fits pages and pictures. */
    private fun board(): JSONObject {
        val screen = context.resources.displayMetrics
        return JSONObject().put(
            "board",
            JSONObject().put("name", "Meta Portal 10 inch").put("width", screen.widthPixels)
                .put("height", screen.heightPixels).put("touch", true).put("rotates", true),
        )
    }

    /** `device.health`: the state of the machine, without names, addresses or displayed content. */
    private fun health(version: String): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val disk = StatFs(context.filesDir.path)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val online = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        fun gigabytes(bytes: Long) = Math.round(bytes / 1e8) / 10.0
        return JSONObject()
            .put("version", version)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
            .put("memory_mb", JSONObject().put("total", memory.totalMem shr 20).put("available", memory.availMem shr 20))
            .put("disk_gb", JSONObject().put("total", gigabytes(disk.totalBytes)).put("free", gigabytes(disk.availableBytes)))
            .put("network_online", online)
            .put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            .put("dashboard_configured", Settings(context).dashboardUrl != Settings.WELCOME_URL)
    }
}
