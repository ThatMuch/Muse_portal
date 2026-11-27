package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {
    private class Host(var answer: AskResult = AskResult.APPROVED) : AssistantHost {
        var available = true
        val notices = mutableListOf<Notice>()
        val questions = mutableListOf<Question>()
        var saved: List<Shortcut>? = null
        var night = NightSettings()
        var frame: Pair<Int, Int>? = null
        var cleared = 0
        override fun isAvailable() = available
        override fun loadPhoto(source: PictureSource): Any? = source.takeIf { "boom" !in it.toString() }
        override fun notify(notice: Notice) = true.also { notices.add(notice) }
        override fun ask(question: Question) = answer.also { questions.add(question) }
        override fun setShortcuts(shortcuts: List<Shortcut>) { saved = shortcuts }
        override fun night(change: (NightSettings) -> NightSettings): Pair<NightSettings, Boolean> {
            night = change(night)
            return night to night.isNight(23 * 60 + 30, 0)
        }
        override fun setFrame(pictures: List<LoadedPicture>, idleMinutes: Int) = pictures.size.also { frame = it to idleMinutes }
        override fun clearFrame() { cleared++ }
    }

    private val host = Host()
    private val commands = AssistantCommands(host) { 23 * 60 + 30 to 1_000L }
    private fun run(name: String, params: JSONObject = JSONObject()) = commands.run(name, params)!!
    private fun payload(name: String, params: JSONObject = JSONObject()): JSONObject {
        val result = run(name, params)
        assertTrue(result.toString(), result.getBoolean("ok"))
        return result.getJSONObject("payload")
    }

    @Test fun fiveCommandsAreDeclared() {
        assertEquals(
            setOf("display.notify", "display.ask", "home.set_shortcuts", "display.night_mode", "frame.set_photos"),
            commands.specs().keys,
        )
        assertTrue(commands.specs().getValue("display.ask").getInt("timeout_ms") > 600_000)
        assertTrue("not authenticated" in commands.specs().getValue("display.ask").getString("description"))
        assertNull(commands.run("system.run", JSONObject()))
    }

    @Test fun aNoticeIsShownAndBounded() {
        payload("display.notify", JSONObject().put("message", "m".repeat(500)).put("title", "Trafic").put("urgent", true).put("duration_ms", 1))
        val notice = host.notices.single()
        assertEquals(240, notice.message.length)
        assertEquals("Trafic", notice.title)
        assertTrue(notice.urgent)
        assertEquals(3_000, notice.durationMillis)
        assertFalse(run("display.notify").getBoolean("ok"))
    }

    @Test fun aQuestionReturnsTheTouchedAnswerAndSaysItIsNotAuthenticated() {
        for ((answer, text) in listOf(AskResult.APPROVED to "approved", AskResult.REJECTED to "rejected", AskResult.NO_ANSWER to "no_answer")) {
            host.answer = answer
            val result = payload("display.ask", JSONObject().put("question", "Envoyer ?").put("timeout_s", 5000))
            assertEquals(text, result.getString("answer"))
            assertFalse(result.getBoolean("authenticated"))
        }
        assertEquals(600_000, host.questions.first().timeoutMillis)
        assertEquals("Approuver", host.questions.first().approveLabel)
        host.answer = AskResult.BUSY
        assertFalse(run("display.ask", JSONObject().put("question", "?")).getBoolean("ok"))
        assertFalse(run("display.ask").getBoolean("ok"))
    }

    @Test fun nothingIsAskedOnAHiddenScreen() {
        host.available = false
        for (name in listOf("display.notify", "display.ask")) {
            assertFalse(run(name, JSONObject().put("message", "m").put("question", "q")).getBoolean("ok"))
        }
        assertTrue(host.notices.isEmpty() && host.questions.isEmpty())
    }

    @Test fun shortcutsAreReplacedOrResetAndBounded() {
        fun shortcut(label: String, message: String) = JSONObject().put("label", label).put("message", message)
        payload("home.set_shortcuts", JSONObject().put("shortcuts", JSONArray(listOf(shortcut("Brief", "Donne le brief")))))
        assertEquals(listOf(Shortcut("Brief", "Donne le brief")), host.saved)
        payload("home.set_shortcuts", JSONObject().put("shortcuts", JSONArray()))
        assertEquals(emptyList<Shortcut>(), host.saved)
        val tooMany = JSONArray(List(5) { shortcut("a", "b") })
        for (bad in listOf(JSONObject(), JSONObject().put("shortcuts", tooMany), JSONObject().put("shortcuts", JSONArray(listOf(shortcut("", "x")))))) {
            assertFalse(run("home.set_shortcuts", bad).getBoolean("ok"))
        }
        assertEquals(Shortcut.DEFAULTS.size, 3)
    }

    @Test fun nightModeCanBeForcedAndScheduled() {
        val on = payload("display.night_mode", JSONObject().put("enabled", true))
        assertTrue(on.getBoolean("night"))
        val off = payload("display.night_mode", JSONObject().put("enabled", false))
        assertFalse(off.getBoolean("night"))
        val times = payload("display.night_mode", JSONObject().put("start", "21:15").put("end", "06:45").put("scheduled", true))
        assertEquals("21:15", times.getString("start"))
        assertEquals("06:45", times.getString("end"))
        assertNull("de nouveaux horaires lèvent le forçage", host.night.forced)
        for (bad in listOf("25:00", "7h", "12:60", "")) {
            assertFalse(run("display.night_mode", JSONObject().put("start", bad)).getBoolean("ok"))
        }
        assertTrue(payload("display.night_mode").has("night"))
    }

    @Test fun theScheduleCrossesMidnightAndAForceLastsUntilTheNextChange() {
        val night = NightSettings(scheduled = true, startMinute = 23 * 60, endMinute = 7 * 60)
        for ((minute, expected) in listOf(22 * 60 + 59 to false, 23 * 60 to true, 0 to true, 6 * 60 + 59 to true, 7 * 60 to false, 12 * 60 to false)) {
            assertEquals("$minute", expected, night.isNight(minute, 0))
        }
        val forced = night.force(false, 23 * 60 + 30, nowMillis = 0)
        assertEquals((7 * 60 + 30) * 60_000L, forced.forcedUntil)
        assertFalse(forced.isNight(1 * 60, 1))
        assertTrue(forced.isNight(23 * 60 + 5, forced.forcedUntil + 1))
        assertEquals(Long.MAX_VALUE, night.copy(scheduled = false).force(true, 0, 0).forcedUntil)
        assertFalse(night.copy(scheduled = false).isNight(0, 0))
    }

    @Test fun framePhotosAreLoadedThenStoredOrCleared() {
        val images = JSONArray(listOf(JSONObject().put("url", "https://a.example/1.jpg"), JSONObject().put("path", "boom.jpg")))
        val result = payload("frame.set_photos", JSONObject().put("images", images).put("idle_minutes", 999))
        assertEquals(1, result.getInt("saved"))
        assertEquals("[2]", result.getJSONArray("failed_items").toString())
        assertEquals(1 to 240, host.frame)
        payload("frame.set_photos", JSONObject().put("images", JSONArray()))
        assertEquals(1, host.cleared)
        assertFalse(run("frame.set_photos").getBoolean("ok"))
        assertFalse(run("frame.set_photos", JSONObject().put("images", JSONArray(List(31) { JSONObject().put("path", "a") }))).getBoolean("ok"))
    }
}
