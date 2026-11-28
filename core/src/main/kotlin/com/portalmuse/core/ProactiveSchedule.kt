package com.portalmuse.core

import java.time.ZonedDateTime

/** When the device asks its Muse, on its own, what deserves a place on the screen: a few fixed times a day. */
object ProactiveSchedule {
    val HOURS = listOf(7, 12, 17, 20)

    /** [lastDone]: the last update that went through, in milliseconds; 0 if there never was one. */
    fun isDue(lastDone: Long, now: ZonedDateTime): Boolean {
        val today = now.toLocalDate()
        val latest = listOf(today.minusDays(1), today).flatMap { day -> HOURS.map { day.atTime(it, 0).atZone(now.zone) } }
            .last { !it.isAfter(now) }
        // A clock set back must not block the updates.
        return lastDone < latest.toInstant().toEpochMilli() || lastDone > now.toInstant().toEpochMilli()
    }
}
