package com.portalmuse.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ProactiveScheduleTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.of("Europe/Paris"))

    private fun millis(time: ZonedDateTime) = time.toInstant().toEpochMilli()

    @Test fun `once per slot`() {
        assertTrue(ProactiveSchedule.isDue(0, at(28, 9)))
        assertFalse(ProactiveSchedule.isDue(millis(at(28, 7, 5)), at(28, 11, 59)))
        assertTrue(ProactiveSchedule.isDue(millis(at(28, 7, 5)), at(28, 12)))
    }

    @Test fun `the night counts from the evening slot`() {
        assertFalse(ProactiveSchedule.isDue(millis(at(27, 20, 1)), at(28, 6, 59)))
        assertTrue(ProactiveSchedule.isDue(millis(at(27, 20, 1)), at(28, 7)))
    }

    @Test fun `a clock set back does not block`() {
        assertTrue(ProactiveSchedule.isDue(millis(at(29, 9)), at(28, 9)))
    }
}
