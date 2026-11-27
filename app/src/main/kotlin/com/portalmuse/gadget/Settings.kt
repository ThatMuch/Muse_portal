package com.portalmuse.gadget

import android.content.Context
import com.portalmuse.core.Identity
import com.portalmuse.core.NightSettings
import com.portalmuse.core.Shortcut
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Réglages non secrets, dans le stockage privé de l'app. Les jetons n'ont rien à faire ici. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = prefs.getString("dashboard_url", null) ?: WELCOME_URL
        set(value) = prefs.edit().putString("dashboard_url", value).apply()

    /** Le nom que l'utilisateur a donné à son Muse, une fois lu. */
    var museName: String?
        get() = prefs.getString("muse_name", null)
        set(value) = prefs.edit().putString("muse_name", value).apply()

    /** Écoute « Hey Muse » : désactivée tant que l'utilisateur ne l'a pas choisie. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit().putBoolean("wake_word", value).apply()

    /** Les réponses de la conversation écrite sont lues à voix haute. */
    var spokenReplies: Boolean
        get() = prefs.getBoolean("spoken_replies", true)
        set(value) = prefs.edit().putBoolean("spoken_replies", value).apply()

    /** La sensibilité de « Hey Muse » : 0 basse, 1 normale, 2 haute (réglages du SDK). */
    var wakeSensitivity: Int
        get() = prefs.getInt("wake_sensitivity", 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("wake_sensitivity", value).apply()

    /** Les messages écrits depuis le Portal vont dans une conversation à part. */
    var separateChat: Boolean
        get() = prefs.getBoolean("separate_chat", false)
        set(value) = prefs.edit().putBoolean("separate_chat", value).apply()

    /** L'identifiant de cette conversation, créé une fois ; `null` pour la principale. */
    fun chatSession(): String? {
        if (!separateChat) return null
        return prefs.getString("chat_session", null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString("chat_session", it).apply() }
    }

    fun forgetChatSession() = prefs.edit().remove("chat_session").apply()

    /** Les photos « Regarde ça » envoyées sont aussi rangées dans les fichiers de Muse. */
    var keepPhotos: Boolean
        get() = prefs.getBoolean("keep_photos", true)
        set(value) = prefs.edit().putBoolean("keep_photos", value).apply()

    /** Muse est prévenu après une panne de l'application. */
    var crashReports: Boolean
        get() = prefs.getBoolean("crash_reports", true)
        set(value) = prefs.edit().putBoolean("crash_reports", value).apply()

    /** Les appels montrent Muse en vidéo ; sinon, voix seule. */
    var videoCalls: Boolean
        get() = prefs.getBoolean("video_calls", true)
        set(value) = prefs.edit().putBoolean("video_calls", value).apply()

    /** Le bouton des derniers messages : l'écran est partagé, on peut le retirer. */
    var historyShown: Boolean
        get() = prefs.getBoolean("history_shown", true)
        set(value) = prefs.edit().putBoolean("history_shown", value).apply()

    /** Les photos « Regarde ça » : désactivées tant que l'utilisateur ne les a pas choisies. */
    var cameraAllowed: Boolean
        get() = prefs.getBoolean("camera_allowed", false)
        set(value) = prefs.edit().putBoolean("camera_allowed", value).apply()

    /** La rotation des photos, en degrés, réglée à l'écran. */
    var photoRotation: Int
        get() = prefs.getInt("photo_rotation", 0)
        set(value) = prefs.edit().putInt("photo_rotation", value).apply()

    /** Muse peut utiliser le Bluetooth : désactivé tant que l'utilisateur ne l'a pas choisi. */
    var bluetoothAllowed: Boolean
        get() = prefs.getBoolean("bluetooth_allowed", false)
        set(value) = prefs.edit().putBoolean("bluetooth_allowed", value).apply()

    /** Les objets Bluetooth acceptés à l'écran : adresse vers nom. */
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

    /** Muse peut utiliser l'écran (images, pages, bandeaux, questions, cadre, raccourcis, nuit). */
    var screenAllowed: Boolean
        get() = prefs.getBoolean("screen_allowed", true)
        set(value) = prefs.edit().putBoolean("screen_allowed", value).apply()

    /** Les boutons d'accueil choisis par Muse ; vide pour ceux d'origine. */
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

    /** Le menu et Muse peuvent changer le mode nuit : une modification à la fois. */
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

    /** Nom Bluetooth du Portal avant l'appairage, à restaurer ensuite. */
    var savedBluetoothName: String?
        get() = prefs.getString("saved_bt_name", null)
        set(value) {
            prefs.edit().putString("saved_bt_name", value).commit()
        }

    /** Créée une seule fois, jamais modifiée ensuite. */
    @Synchronized
    fun identity(): Identity {
        prefs.getString("identity_mac", null)?.let { return Identity(it) }
        val identity = Identity.generate()
        check(prefs.edit().putString("identity_mac", identity.mac).commit()) { "identité non enregistrée" }
        return identity
    }

    companion object {
        const val WELCOME_URL = "file:///android_asset/welcome.html"
    }
}
