package com.portalmuse.gadget

import android.content.Context
import com.portalmuse.core.Identity
import com.portalmuse.core.NightSettings
import com.portalmuse.core.Shortcut
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Non-secret settings, in the app's private storage. Tokens do not belong here. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = prefs.getString("dashboard_url", null) ?: WELCOME_URL
        set(value) = prefs.edit().putString("dashboard_url", value).apply()

    /** The name the user gave their Muse, once read. */
    var museName: String?
        get() = prefs.getString("muse_name", null)
        set(value) = prefs.edit().putString("muse_name", value).apply()

    /** "Hey Muse" listening: off until the user chooses it. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit().putBoolean("wake_word", value).apply()

    /** Replies in the written conversation are read aloud. */
    var spokenReplies: Boolean
        get() = prefs.getBoolean("spoken_replies", true)
        set(value) = prefs.edit().putBoolean("spoken_replies", value).apply()

    /** "Hey Muse" sensitivity: 0 low, 1 normal, 2 high (the SDK's settings). */
    var wakeSensitivity: Int
        get() = prefs.getInt("wake_sensitivity", 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("wake_sensitivity", value).apply()

    /** Messages written from the Portal go into a separate conversation. */
    var separateChat: Boolean
        get() = prefs.getBoolean("separate_chat", false)
        set(value) = prefs.edit().putBoolean("separate_chat", value).apply()

    /** That conversation's identifier, created once; `null` for the main one. */
    fun chatSession(): String? {
        if (!separateChat) return null
        return prefs.getString("chat_session", null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString("chat_session", it).apply() }
    }

    fun forgetChatSession() = prefs.edit().remove("chat_session").remove("ambient_session").apply()

    /** The conversation of the automatic screen updates, created once. */
    fun ambientSession(): String = prefs.getString("ambient_session", null) ?: java.util.UUID.randomUUID().toString()
        .also { prefs.edit().putString("ambient_session", it).apply() }

    /** The Portal asks Muse for screen updates on its own. */
    var proactive: Boolean
        get() = prefs.getBoolean("proactive", true)
        set(value) = prefs.edit().putBoolean("proactive", value).apply()

    var proactiveDoneAt: Long
        get() = prefs.getLong("proactive_done", 0)
        set(value) = prefs.edit().putLong("proactive_done", value).apply()

    /** "Look at this" photos that are sent are also stored in Muse's files. */
    var keepPhotos: Boolean
        get() = prefs.getBoolean("keep_photos", true)
        set(value) = prefs.edit().putBoolean("keep_photos", value).apply()

    /** Muse is told after the app crashes. */
    var crashReports: Boolean
        get() = prefs.getBoolean("crash_reports", true)
        set(value) = prefs.edit().putBoolean("crash_reports", value).apply()

    /** Calls show Muse in video; otherwise voice only. */
    var videoCalls: Boolean
        get() = prefs.getBoolean("video_calls", true)
        set(value) = prefs.edit().putBoolean("video_calls", value).apply()

    /** The latest messages button: the screen is shared, it can be removed. */
    var historyShown: Boolean
        get() = prefs.getBoolean("history_shown", true)
        set(value) = prefs.edit().putBoolean("history_shown", value).apply()

    /** "Look at this" photos: off until the user chooses them. */
    var cameraAllowed: Boolean
        get() = prefs.getBoolean("camera_allowed", false)
        set(value) = prefs.edit().putBoolean("camera_allowed", value).apply()

    /** Photo rotation, in degrees, set on screen. */
    var photoRotation: Int
        get() = prefs.getInt("photo_rotation", 0)
        set(value) = prefs.edit().putInt("photo_rotation", value).apply()

    /** Muse may use Bluetooth: off until the user chooses it. */
    var bluetoothAllowed: Boolean
        get() = prefs.getBoolean("bluetooth_allowed", false)
        set(value) = prefs.edit().putBoolean("bluetooth_allowed", value).apply()

    /** Bluetooth devices accepted on screen: address to name. */
    val bluetoothDevices: Map<String, String>
        get() = try {
            val stored = JSONObject(prefs.getString("bluetooth_devices", null) ?: "{}")
            stored.keys().asSequence().associateWith { stored.getString(it) }
        } catch (e: JSONException) {
            emptyMap()
        }

    fun rememberBluetoothDevice(address: String, name: String?) = synchronized(Settings) {
        val next = JSONObject(bluetoothDevices + (address to name.orEmpty()))
        prefs.edit().putString("bluetooth_devices", next.toString()).apply()
    }

    fun forgetBluetoothDevices() = prefs.edit().remove("bluetooth_devices").apply()

    /** Muse may use the screen (pictures, pages, banners, questions, frame, shortcuts, night). */
    var screenAllowed: Boolean
        get() = prefs.getBoolean("screen_allowed", true)
        set(value) = prefs.edit().putBoolean("screen_allowed", value).apply()

    /** The home buttons chosen by Muse; empty for the original ones. */
    var customShortcuts: List<Shortcut>
        get() = try {
            val list = JSONArray(prefs.getString("shortcuts", null) ?: "[]")
            (0 until list.length()).map { list.getJSONObject(it) }
                .map { Shortcut(it.getString("label"), it.getString("message")) }
        } catch (e: JSONException) {
            emptyList()
        }
        set(value) {
            val list = JSONArray(value.map { JSONObject().put("label", it.label).put("message", it.message) })
            prefs.edit().putString("shortcuts", list.toString()).apply()
        }

    val shortcuts: List<Shortcut>
        get() = customShortcuts.takeIf { screenAllowed && it.isNotEmpty() } ?: Shortcut.DEFAULTS

    val night: NightSettings
        get() {
            val default = NightSettings()
            val forced = prefs.getInt("night_forced", -1)
            return NightSettings(
                scheduled = prefs.getBoolean("night_scheduled", default.scheduled),
                startMinute = prefs.getInt("night_start", default.startMinute),
                endMinute = prefs.getInt("night_end", default.endMinute),
                forced = if (forced < 0) null else forced == 1,
                forcedUntil = prefs.getLong("night_forced_until", 0),
            )
        }

    /** The menu and Muse can both change night mode: one change at a time. */
    fun changeNight(change: (NightSettings) -> NightSettings): NightSettings = synchronized(Settings) {
        val next = change(night)
        prefs.edit()
            .putBoolean("night_scheduled", next.scheduled)
            .putInt("night_start", next.startMinute)
            .putInt("night_end", next.endMinute)
            .putInt("night_forced", next.forced?.let { if (it) 1 else 0 } ?: -1)
            .putLong("night_forced_until", next.forcedUntil)
            .apply()
        next
    }

    /** The Portal's Bluetooth name before pairing, to restore afterwards. */
    var savedBluetoothName: String?
        get() = prefs.getString("saved_bt_name", null)
        set(value) {
            prefs.edit().putString("saved_bt_name", value).commit()
        }

    /** Created exactly once, never changed afterwards. */
    @Synchronized
    fun identity(): Identity {
        prefs.getString("identity_mac", null)?.let { return Identity(it) }
        val identity = Identity.generate()
        check(prefs.edit().putString("identity_mac", identity.mac).commit()) { "identity not saved" }
        return identity
    }

    companion object {
        const val WELCOME_URL = "file:///android_asset/welcome.html"
    }
}
