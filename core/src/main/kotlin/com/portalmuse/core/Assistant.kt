package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_FRAME_PHOTOS = 30
private const val MAX_SHORTCUTS = 4
private const val MINUTES_PER_DAY = 24 * 60

/** A short banner. */
data class Notice(val message: String, val title: String?, val urgent: Boolean, val durationMillis: Long)

/** An approval request on screen. */
data class Question(
    val question: String,
    val details: String?,
    val approveLabel: String,
    val rejectLabel: String,
    val timeoutMillis: Long,
)

/** [BUSY]: another request is already waiting, or the screen is gone. */
enum class AskResult { APPROVED, REJECTED, NO_ANSWER, BUSY }

/** A home button: touching it sends [message] to Muse in the user's name. */
data class Shortcut(val label: String, val message: String) {
    companion object {
        val DEFAULTS = listOf(
            Shortcut(tr(
                "Morning brief",
                "Brief du matin",
            ), tr("Give me the morning brief.", "Donne-moi le brief du matin.")),
            Shortcut(tr(
                "My calendar",
                "Mon agenda",
            ), tr("What is on my calendar today?", "Qu'y a-t-il à mon agenda aujourd'hui ?")),
            Shortcut(tr("Traffic", "Trafic"), tr(
                "What is the traffic on my routes right now?",
                "Quel est le trafic sur mes trajets en ce moment ?",
            )),
        )
    }
}

/**
 * Night mode: fixed hours, which can be overridden until the next change. Times are in minutes since midnight.
 */
data class NightSettings(
    val scheduled: Boolean = true,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    val forced: Boolean? = null,
    val forcedUntil: Long = 0,
) {
    fun isNight(minuteOfDay: Int, nowMillis: Long): Boolean {
        if (forced != null && nowMillis < forcedUntil) return forced
        if (!scheduled || startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /** Without a schedule, the override stays until further notice. */
    fun force(night: Boolean, minuteOfDay: Int, nowMillis: Long): NightSettings {
        if (!scheduled || startMinute == endMinute) return copy(forced = night, forcedUntil = Long.MAX_VALUE)
        fun wait(boundary: Int) = (boundary - minuteOfDay + MINUTES_PER_DAY - 1) % MINUTES_PER_DAY + 1
        val minutes = minOf(wait(startMinute), wait(endMinute))
        return copy(forced = night, forcedUntil = nowMillis + minutes * 60_000L)
    }

    companion object {
        private val TIME = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])")

        fun parseTime(text: String): Int? =
            TIME.matchEntire(text)?.destructured?.let { (hours, minutes) -> hours.toInt() * 60 + minutes.toInt() }

        fun formatTime(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** What the device can do beyond plain display. */
interface AssistantHost {
    fun isAvailable(): Boolean

    /** A photo ready to be saved, in the form [setFrame] expects. */
    fun loadPhoto(source: PictureSource): Any?
    fun notify(notice: Notice): Boolean

    /** Blocks until a touch or the deadline. */
    fun ask(question: Question): AskResult

    /** An empty list restores the original buttons. */
    fun setShortcuts(shortcuts: List<Shortcut>)

    /** Applies [change] and returns the settings with the current state. */
    fun night(change: (NightSettings) -> NightSettings): Pair<NightSettings, Boolean>

    /** Replaces the frame photos; returns how many were saved. */
    fun setFrame(pictures: List<LoadedPicture>, idleMinutes: Int): Int
    fun clearFrame()
}

/** Banners, approvals, shortcuts, night mode and photo frame, as seen by Muse. */
class AssistantCommands(
    private val host: AssistantHost,
    /** (minutes since midnight, time in milliseconds) */
    private val now: () -> Pair<Int, Long>,
) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        NOTIFY to Specs.of(
            "Show a short banner on the home touchscreen for something that matters now, such as a transit " +
                "disruption, a reminder or a deadline today. Do not use it for chit-chat. When the person asks for a " +
                "banner on this device, use this, not their phone.",
            JSONObject().put("message", Specs.field("string", "Up to 240 characters, in the person's language.")),
            JSONObject()
                .put("title", Specs.field("string", "Short heading."))
                .put("urgent", Specs.field("boolean", "Red banner with a sound. Default false."))
                .put("duration_ms", Specs.field("integer", "3000 to 600000. Default 20000.")),
        ),
        ASK to Specs.of(
            "Ask the person to approve or reject something with two large buttons on the home touchscreen, and " +
                "wait for the tap. Use it when a task needs a decision right away. The tap is not " +
                "authenticated: anyone near the screen can answer. Before spending money, sending messages to " +
                "others, deleting or anything hard to undo, also get confirmation on the phone. When the person " +
                "talks to you from this device or asks to be asked here, ask here.",
            JSONObject().put("question", Specs.field("string", "The decision, one sentence, up to 300 characters.")),
            JSONObject()
                .put("details", Specs.field("string", "What exactly will happen, up to 800 characters."))
                .put("approve_label", Specs.field("string", "Default: Approve, in the device's language."))
                .put("reject_label", Specs.field("string", "Default: Reject, in the device's language."))
                .put("timeout_s", Specs.field("integer", "10 to 600. Default 120.")),
        ).put("timeout_ms", ASK_TIMEOUT_MS),
        SHORTCUTS to Specs.of(
            "Set the quick action buttons on the home screen. Tapping one sends its message to you as the " +
                "person, and your reply is shown on screen. An empty list restores the default buttons.",
            JSONObject().put(
                "shortcuts",
                Specs.field("array", "Up to 4 objects with label (up to 24 characters) and message (up to 500)."),
            ),
            JSONObject(),
        ),
        NIGHT to Specs.of(
            "Read or change night mode, which turns the screen light off while the device stays online and " +
                "listening; a touch lights it again. Use enabled to turn it on or off now (until the " +
                "next scheduled change), and start, end, scheduled to change the daily hours. Without " +
                "parameters it only reports the state.",
            JSONObject(),
            JSONObject()
                .put("enabled", Specs.field("boolean", "Turn night mode on or off now."))
                .put("start", Specs.field("string", "Daily start, HH:MM."))
                .put("end", Specs.field("string", "Daily end, HH:MM."))
                .put("scheduled", Specs.field("boolean", "Whether the daily hours apply.")),
        ),
        FRAME to Specs.of(
            "Choose the photos of the idle photo frame: after a while without use, the screen shows them as a " +
                "slideshow until touched. Use the person's own photos when they ask. The list replaces the " +
                "previous one; an empty list turns the frame off.",
            JSONObject().put("images", Specs.field("array", "Up to 30 objects with url (https image file) or path.")),
            JSONObject().put("idle_minutes", Specs.field("integer", "Delay before it starts, 1 to 240. Default 5.")),
        ).put("timeout_ms", FRAME_TIMEOUT_MS),
    )

    override fun run(command: String, params: JSONObject): JSONObject? = when (command) {
        NOTIFY -> notify(params)
        ASK -> ask(params)
        SHORTCUTS -> shortcuts(params)
        NIGHT -> night(params)
        FRAME -> frame(params)
        else -> null
    }

    private fun notify(params: JSONObject): JSONObject {
        val message = Specs.text(params, "message", 240) ?: return Specs.error("message is required")
        if (!host.isAvailable()) return Specs.unavailable()
        val duration = (params.opt("duration_ms") as? Number)?.toLong()?.coerceIn(3_000, 600_000) ?: 20_000
        val notice = Notice(message, Specs.text(params, "title", 60), params.opt("urgent") == true, duration)
        return if (host.notify(notice)) Specs.ok(JSONObject().put("shown", true)) else Specs.unavailable()
    }

    private fun ask(params: JSONObject): JSONObject {
        val question = Specs.text(params, "question", 300) ?: return Specs.error("question is required")
        if (!host.isAvailable()) return Specs.unavailable()
        val seconds = (params.opt("timeout_s") as? Number)?.toLong()?.coerceIn(10, 600) ?: 120
        val result = host.ask(
            Question(
                question, Specs.text(params, "details", 800),
                Specs.text(params, "approve_label", 20) ?: tr("Approve", "Approuver"),
                Specs.text(params, "reject_label", 20) ?: tr("Reject", "Refuser"),
                seconds * 1000,
            ),
        )
        if (result == AskResult.BUSY) return Specs.error("another question is already on screen")
        return Specs.ok(
            JSONObject()
                .put("answer", result.name.lowercase())
                .put("authenticated", false)
                .put("source", "touch on a shared home screen"),
        )
    }

    private fun shortcuts(params: JSONObject): JSONObject {
        val list = params.opt("shortcuts") as? JSONArray ?: return Specs.error("shortcuts must be an array")
        if (list.length() > MAX_SHORTCUTS) return Specs.error("at most $MAX_SHORTCUTS shortcuts")
        val parsed = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index)
            val label = item?.let { Specs.text(it, "label", 24) }
            val message = item?.let { Specs.text(it, "message", 500) }
            if (label == null || message == null) return Specs.error("shortcut ${index + 1} needs label and message")
            Shortcut(label, message)
        }
        host.setShortcuts(parsed)
        return Specs.ok(JSONObject().put("shortcuts", if (parsed.isEmpty()) Shortcut.DEFAULTS.size else parsed.size))
    }

    private fun night(params: JSONObject): JSONObject {
        fun time(key: String): Int? = (params.opt(key) as? String)?.let(NightSettings::parseTime)
        for (key in listOf("start", "end")) {
            if (params.has(key) && time(key) == null) return Specs.error("$key must be HH:MM")
        }
        val (minute, millis) = now()
        val (settings, night) = host.night { current ->
            var next = current
            val scheduled = params.opt("scheduled") as? Boolean
            if (scheduled != null || params.has("start") || params.has("end")) {
                next = next.copy(
                    scheduled = scheduled ?: next.scheduled,
                    startMinute = time("start") ?: next.startMinute,
                    endMinute = time("end") ?: next.endMinute,
                    forced = null,
                )
            }
            (params.opt("enabled") as? Boolean)?.let { next = next.force(it, minute, millis) }
            next
        }
        return Specs.ok(
            JSONObject()
                .put("night", night)
                .put("scheduled", settings.scheduled)
                .put("start", NightSettings.formatTime(settings.startMinute))
                .put("end", NightSettings.formatTime(settings.endMinute)),
        )
    }

    private fun frame(params: JSONObject): JSONObject {
        val list = params.opt("images") as? JSONArray ?: return Specs.error("images must be an array")
        if (list.length() > MAX_FRAME_PHOTOS) return Specs.error("at most $MAX_FRAME_PHOTOS images")
        if (list.length() == 0) {
            host.clearFrame()
            return Specs.ok(JSONObject().put("saved", 0))
        }
        val requests = Pictures.parse(list) ?: return Specs.error("each image needs exactly one of url or path")
        val loaded = Pictures.load(requests.map { it.first }, host::loadPhoto, FRAME_DEADLINE_S)
        val pictures = loaded.mapIndexedNotNull { i, image -> image?.let { LoadedPicture(it, requests[i].second) } }
        if (pictures.isEmpty()) return Specs.error("no image could be loaded")
        val idle = (params.opt("idle_minutes") as? Number)?.toInt()?.coerceIn(1, 240) ?: 5
        val saved = host.setFrame(pictures, idle)
        val failed = loaded.indices.filter { loaded[it] == null }.map { it + 1 }
        return Specs.ok(JSONObject().put("saved", saved).put("failed_items", JSONArray(failed)))
    }

    private companion object {
        const val NOTIFY = "display.notify"
        const val ASK = "display.ask"
        const val SHORTCUTS = "home.set_shortcuts"
        const val NIGHT = "display.night_mode"
        const val FRAME = "frame.set_photos"
        const val ASK_TIMEOUT_MS = 620_000
        const val FRAME_TIMEOUT_MS = 200_000
        const val FRAME_DEADLINE_S = 150L
    }
}
