package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_APP_CHARS = 200_000

/** A running timer. */
data class TimerInfo(val id: Int, val label: String?, val remainingSeconds: Long)

/** The device's timers and volume. */
interface HomeHost {
    /** `null` if there are already too many. */
    fun startTimer(durationMillis: Long, label: String?): TimerInfo?
    fun timers(): List<TimerInfo>
    /** [id] null: all of them. Returns the number removed. */
    fun cancelTimers(id: Int?): Int
    /** Media volume, in percent. */
    fun volume(): Int
    fun setVolume(percent: Int): Int
}

class HomeCommands(private val host: HomeHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        START to Specs.of(
            "Start a kitchen timer on the device. It rings there when it ends, also during no conversation.",
            JSONObject().put("duration_s", Specs.field("integer", "5 to 86400 seconds.")),
            JSONObject().put("label", Specs.field("string", "What it is for, in the person's language.")),
        ),
        LIST to Specs.of("List running timers with their remaining seconds.", JSONObject(), JSONObject()),
        CANCEL to Specs.of(
            "Cancel a timer, or all of them without id.", JSONObject(),
            JSONObject().put("id", Specs.field("integer", "From timer.start or timer.list.")),
        ),
        VOLUME to Specs.of(
            "Read or change the loudness of the device speaker. Without parameters it only reports it.",
            JSONObject(),
            JSONObject()
                .put("percent", Specs.field("integer", "Set it, 0 to 100."))
                .put("change", Specs.field("integer", "Or raise or lower it, -100 to 100 points.")),
        ),
    )

    override fun run(command: String, params: JSONObject): JSONObject? = when (command) {
        START -> start(params)
        LIST -> Specs.ok(JSONObject().put("timers", JSONArray(host.timers().map(::describe))))
        CANCEL -> {
            val id = (params.opt("id") as? Number)?.toInt()
            val removed = host.cancelTimers(id)
            if (removed == 0 && id != null) Specs.error("no such timer") else Specs.ok(JSONObject().put("cancelled", removed))
        }
        VOLUME -> volume(params)
        else -> null
    }

    private fun describe(timer: TimerInfo) =
        JSONObject().put("id", timer.id).putOpt("label", timer.label).put("remaining_s", timer.remainingSeconds)

    private fun start(params: JSONObject): JSONObject {
        val seconds = (params.opt("duration_s") as? Number)?.toLong()
        if (seconds == null || seconds !in 5..86_400) return Specs.error("duration_s must be 5-86400")
        val timer = host.startTimer(seconds * 1000, Specs.text(params, "label", 40))
            ?: return Specs.error("too many timers running; cancel one first")
        return Specs.ok(describe(timer))
    }

    private fun volume(params: JSONObject): JSONObject {
        val percent = (params.opt("percent") as? Number)?.toInt()
        val change = (params.opt("change") as? Number)?.toInt()
        if (percent != null && change != null) return Specs.error("give percent or change, not both")
        if (percent != null && percent !in 0..100) return Specs.error("percent must be 0-100")
        val target = percent ?: change?.let { (host.volume() + it.coerceIn(-100, 100)).coerceIn(0, 100) }
        return Specs.ok(JSONObject().put("percent", if (target == null) host.volume() else host.setVolume(target)))
    }

    private companion object {
        const val START = "timer.start"
        const val LIST = "timer.list"
        const val CANCEL = "timer.cancel"
        const val VOLUME = "audio.volume"
    }
}

/** A small app kept on the device. */
data class AppInfo(val name: String, val title: String)

interface AppHost {
    fun isAvailable(): Boolean
    /** False if there is no room left. */
    fun save(app: AppInfo, html: String): Boolean
    fun list(): List<AppInfo>
    fun open(name: String): Boolean
    fun remove(name: String): Boolean
}

/** The small touch apps that Muse writes for its owner. */
class AppCommands(private val host: AppHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> {
        val named = { JSONObject().put("name", Specs.field("string", "1-32 of a-z, 0-9 and hyphen.")) }
        return linkedMapOf(
            SAVE to Specs.of(
                "Create or replace a small touch app on the device (a shopping list, a game, a counter), kept " +
                    "on its home screen. One self-contained HTML document with inline script and style: no " +
                    "network, no external files, no camera or microphone. localStorage keeps its data. Large " +
                    "touch targets, light text on black, 1280 x 800. When the person asks for an app from this device " +
                    "or for it, build it with this command, not as a file elsewhere.",
                named().put("html", Specs.field("string", "The whole document, up to $MAX_APP_CHARS characters.")),
                JSONObject()
                    .put("title", Specs.field("string", "Name shown to the person."))
                    .put("open", Specs.field("boolean", "Show it right away. Default true.")),
            ),
            OPEN to Specs.of("Show a saved app on the screen.", named(), JSONObject()),
            LIST to Specs.of("List saved apps.", JSONObject(), JSONObject()),
            REMOVE to Specs.of("Delete a saved app and its data.", named(), JSONObject()),
        )
    }

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command == LIST) {
            val apps = host.list().map { JSONObject().put("name", it.name).put("title", it.title) }
            return Specs.ok(JSONObject().put("apps", JSONArray(apps)))
        }
        if (command !in listOf(SAVE, OPEN, REMOVE)) return null
        val name = (params.opt("name") as? String)?.takeIf { NAME.matches(it) }
            ?: return Specs.error("name must be 1-32 of a-z, 0-9 and hyphen")
        return when (command) {
            OPEN -> if (!host.isAvailable()) Specs.unavailable()
            else if (host.open(name)) Specs.ok(JSONObject().put("shown", true)) else Specs.error("no such app")
            REMOVE -> if (host.remove(name)) Specs.ok(JSONObject().put("removed", true)) else Specs.error("no such app")
            else -> save(name, params)
        }
    }

    private fun save(name: String, params: JSONObject): JSONObject {
        val html = params.opt("html") as? String
        if (html.isNullOrBlank() || html.length > MAX_APP_CHARS) {
            return Specs.error("html must be 1 to $MAX_APP_CHARS characters")
        }
        if (!host.save(AppInfo(name, Specs.text(params, "title", 24) ?: name), html)) {
            return Specs.error("no room for another app; remove one first")
        }
        val shown = params.opt("open") != false && host.isAvailable() && host.open(name)
        return Specs.ok(JSONObject().put("saved", name).put("shown", shown))
    }

    private companion object {
        const val SAVE = "apps.save"
        const val OPEN = "apps.open"
        const val LIST = "apps.list"
        const val REMOVE = "apps.remove"
        val NAME = Regex("[a-z0-9-]{1,32}")
    }
}

/**
 * The frame of an app written remotely: inline script only, no network access. WebRTC, which the content policy
 * does not cover, is removed first.
 */
object AppPage {
    private const val HEAD = "<!doctype html><meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; " +
        "style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; " +
        "frame-src 'none'; form-action 'none'; base-uri 'none'\">" +
        "<script>for (const n of ['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','WebTransport'," +
        "'WebSocket']) { try { Object.defineProperty(window, n, { value: undefined }); } catch (e) {} }</script>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html{background:#000;color:#fff;font-family:sans-serif;font-size:22px}</style>"

    fun wrap(html: String) = HEAD + html
}
