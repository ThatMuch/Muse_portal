package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * A wake-up alarm. Without [days] it rings once, at the next [hour]:[minute]. [station]: the radio it plays,
 * `null` for beeps.
 */
data class Alarm(
    val id: Int, val hour: Int, val minute: Int, val days: Set<DayOfWeek>, val label: String?, val station: String?,
) {
    val time get() = "%02d:%02d".format(hour, minute)

    /** The first ring strictly after [now], in its time zone. */
    fun next(now: ZonedDateTime): ZonedDateTime = (0L..7L).asSequence()
        .map { now.toLocalDate().plusDays(it).atTime(hour, minute).atZone(now.zone) }
        .first { it.isAfter(now) && (days.isEmpty() || it.dayOfWeek in days) }

    companion object {
        private val NAMES = DayOfWeek.values().associateBy { it.name.take(3).lowercase() }

        fun day(name: String): DayOfWeek? = NAMES[name.trim().lowercase()]
        fun dayName(day: DayOfWeek) = day.name.take(3).lowercase()
    }
}

interface AlarmHost {
    /** [alarm] has no identifier yet. `null` if there are already too many. */
    fun addAlarm(alarm: Alarm): Alarm?
    fun alarms(): List<Alarm>

    /** [id] null: all of them. Returns the number removed. */
    fun cancelAlarms(id: Int?): Int
}

class AlarmCommands(private val host: AlarmHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        SET to Specs.of(
            "Set a wake-up alarm that rings on the device, with a radio station or beeps. It rings even if the " +
                "device is offline at that time. Use it for \"wake me up at 7\"; use timer.start for durations.",
            JSONObject().put("time", Specs.field("string", "Local time, HH:MM.")),
            JSONObject()
                .put("days", Specs.field("array", "Repeat on these days: mon, tue, wed, thu, fri, sat, sun. Omit to ring once."))
                .put("label", Specs.field("string", "What it is for, in the person's language."))
                .put("station", Specs.field("string", "Radio to wake up with: " + Stations.ALL.joinToString { it.id } + ".")),
        ),
        LIST to Specs.of("List the alarms.", JSONObject(), JSONObject()),
        CANCEL to Specs.of(
            "Remove an alarm, or all of them without id.", JSONObject(),
            JSONObject().put("id", Specs.field("integer", "From alarm.set or alarm.list.")),
        ),
    )

    override fun run(command: String, params: JSONObject): JSONObject? = when (command) {
        SET -> set(params)
        LIST -> Specs.ok(JSONObject().put("alarms", JSONArray(host.alarms().map(::describe))))
        CANCEL -> {
            val id = (params.opt("id") as? Number)?.toInt()
            val removed = host.cancelAlarms(id)
            if (removed == 0 && id != null) Specs.error("no such alarm") else Specs.ok(JSONObject().put("cancelled", removed))
        }
        else -> null
    }

    private fun set(params: JSONObject): JSONObject {
        val minute = (params.opt("time") as? String)?.let(NightSettings::parseTime)
            ?: return Specs.error("time must be HH:MM")
        val days = LinkedHashSet<DayOfWeek>()
        if (params.has("days")) {
            val list = params.opt("days") as? JSONArray ?: return Specs.error("days must be an array")
            for (i in 0 until list.length()) {
                days.add((list.opt(i) as? String)?.let(Alarm::day) ?: return Specs.error("unknown day"))
            }
        }
        val station = Specs.text(params, "station", 40)
        if (station != null && Stations.byId(station) == null) return Specs.error("unknown station")
        val alarm = Alarm(0, minute / 60, minute % 60, days, Specs.text(params, "label", 40), station)
        val saved = host.addAlarm(alarm) ?: return Specs.error("too many alarms; cancel one first")
        return Specs.ok(describe(saved))
    }

    private fun describe(alarm: Alarm) = JSONObject().put("id", alarm.id).put("time", alarm.time)
        .put("days", JSONArray(alarm.days.map(Alarm::dayName))).putOpt("label", alarm.label)
        .putOpt("station", alarm.station)

    private companion object {
        const val SET = "alarm.set"
        const val LIST = "alarm.list"
        const val CANCEL = "alarm.cancel"
    }
}
