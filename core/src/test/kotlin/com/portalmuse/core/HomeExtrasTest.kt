package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeExtrasTest {
    private val paris = ZoneId.of("Europe/Paris")
    /** A Saturday. */
    private val now = ZonedDateTime.of(2026, 11, 28, 9, 0, 0, 0, paris)

    @Test fun `an alarm rings at its next time`() {
        val once = Alarm(1, 7, 0, emptySet(), null, null)
        assertEquals(now.plusDays(1).withHour(7), once.next(now))
        assertEquals(now.withHour(10), once.copy(hour = 10).next(now))
        val weekdays = once.copy(days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(DayOfWeek.MONDAY, weekdays.next(now).dayOfWeek)
        val saturdays = once.copy(days = setOf(DayOfWeek.SATURDAY))
        assertEquals(now.plusDays(7).withHour(7), saturdays.next(now))
    }

    @Test fun `alarm commands check what they receive`() {
        val saved = mutableListOf<Alarm>()
        val commands = AlarmCommands(object : AlarmHost {
            override fun addAlarm(alarm: Alarm) = alarm.copy(id = 3).also { saved.add(it) }
            override fun alarms() = saved
            override fun cancelAlarms(id: Int?) = 0
        })
        fun set(json: String) = commands.run("alarm.set", JSONObject(json))!!
        assertTrue(set("""{"time":"06:45","days":["mon","tue"],"station":"fip"}""").getBoolean("ok"))
        assertEquals("06:45", saved.single().time)
        assertFalse(set("""{"time":"25:00"}""").getBoolean("ok"))
        assertFalse(set("""{"time":"07:00","days":["someday"]}""").getBoolean("ok"))
        assertFalse(set("""{"time":"07:00","station":"https://example.org/x"}""").getBoolean("ok"))
        assertFalse(commands.run("alarm.cancel", JSONObject("""{"id":9}"""))!!.getBoolean("ok"))
    }

    @Test fun `cards are limited and need a title`() {
        var shown = emptyList<Card>()
        val commands = CardCommands(object : CardHost {
            override fun setCards(cards: List<Card>) {
                shown = cards
            }
        })
        fun set(cards: JSONArray) = commands.run("home.set_cards", JSONObject().put("cards", cards))!!.getBoolean("ok")
        val card = JSONObject().put("title", "Pain").put("text", "à acheter").put("icon", "🥖")
        assertTrue(set(JSONArray().put(card)))
        assertEquals(Card("Pain", "à acheter", "🥖"), shown.single())
        assertFalse(set(JSONArray().put(JSONObject())))
        assertFalse(set(JSONArray().put(card).put(card).put(card).put(card)))
        assertTrue(set(JSONArray()))
        assertTrue(shown.isEmpty())
    }

    @Test fun `only public https pages are opened`() {
        for (good in listOf("https://www.marmiton.org/recettes", "https://fr.wikipedia.org/wiki/Paris?x=1#a")) {
            assertTrue(good, WebLinks.allowed(good))
        }
        for (bad in listOf(
            "http://example.org", "https://192.168.1.1/", "https://127.0.0.1", "https://[::1]/", "https://router/",
            "https://nas.local/", "https://user:pass@example.org/", "https://example.org:8443/", "file:///etc/hosts",
            "javascript:alert(1)", "https://2130706433/",
        )) {
            assertFalse(bad, WebLinks.allowed(bad))
        }
    }

    @Test fun `the radio only plays listed stations`() {
        var played: Station? = null
        val commands = RadioCommands(object : RadioHost {
            override fun playRadio(station: Station) {
                played = station
            }
            override fun stopRadio() = true
            override fun radio() = played
        })
        assertFalse(commands.run("radio.play", JSONObject().put("station", "https://x.example/a.mp3"))!!.getBoolean("ok"))
        assertTrue(commands.run("radio.play", JSONObject().put("station", "fip"))!!.getBoolean("ok"))
        assertEquals("FIP", played?.name)
    }
}
