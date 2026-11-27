package com.portalmuse.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    private class Host : HomeHost, AppHost {
        var level = 70
        val running = mutableListOf<TimerInfo>()
        val apps = linkedMapOf<String, Pair<AppInfo, String>>()
        val opened = mutableListOf<String>()
        var visible = true
        override fun startTimer(durationMillis: Long, label: String?) =
            if (running.size >= 2) null else TimerInfo(running.size + 1, label, durationMillis / 1000).also(running::add)
        override fun timers() = running.toList()
        override fun cancelTimers(id: Int?): Int {
            val before = running.size
            running.removeAll { id == null || it.id == id }
            return before - running.size
        }
        override fun volume() = level
        override fun setVolume(percent: Int) = percent.also { level = it }
        override fun isAvailable() = visible
        override fun save(app: AppInfo, html: String) = (apps.size < 2 || app.name in apps).also { ok ->
            if (ok) apps[app.name] = app to html
        }
        override fun list() = apps.values.map { it.first }
        override fun open(name: String) = (name in apps).also { if (it) opened.add(name) }
        override fun remove(name: String) = apps.remove(name) != null
    }

    private val host = Host()
    private val home = HomeCommands(host)
    private val apps = AppCommands(host)

    private fun JSONObject?.payload(): JSONObject {
        assertTrue(toString(), this!!.getBoolean("ok"))
        return getJSONObject("payload")
    }
    private fun params(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test fun timersStartListAndCancel() {
        assertEquals(setOf("timer.start", "timer.list", "timer.cancel", "audio.volume"), home.specs().keys)
        assertEquals(600, home.run("timer.start", params("duration_s" to 600, "label" to "Pâtes")).payload().getInt("remaining_s"))
        home.run("timer.start", params("duration_s" to 60)).payload()
        assertFalse(home.run("timer.start", params("duration_s" to 60))!!.getBoolean("ok"))
        for (bad in listOf(params(), params("duration_s" to 1), params("duration_s" to 90_000))) {
            assertFalse(home.run("timer.start", bad)!!.getBoolean("ok"))
        }
        assertEquals("Pâtes", home.run("timer.list", params()).payload().getJSONArray("timers").getJSONObject(0).getString("label"))
        assertFalse(home.run("timer.cancel", params("id" to 9))!!.getBoolean("ok"))
        assertEquals(2, home.run("timer.cancel", params()).payload().getInt("cancelled"))
    }

    @Test fun theVolumeIsReadSetOrStepped() {
        assertEquals(70, home.run("audio.volume", params()).payload().getInt("percent"))
        assertEquals(30, home.run("audio.volume", params("percent" to 30)).payload().getInt("percent"))
        assertEquals(100, home.run("audio.volume", params("change" to 90)).payload().getInt("percent"))
        assertFalse(home.run("audio.volume", params("percent" to 101))!!.getBoolean("ok"))
        assertFalse(home.run("audio.volume", params("percent" to 5, "change" to 5))!!.getBoolean("ok"))
        assertNull(home.run("audio.record", params()))
    }

    @Test fun appsAreSavedOpenedAndRemovedWithSafeNames() {
        val saved = apps.run("apps.save", params("name" to "courses", "html" to "<h1>x</h1>", "title" to "Courses")).payload()
        assertTrue(saved.getBoolean("shown"))
        assertEquals(listOf("courses"), host.opened)
        host.visible = false
        assertFalse(apps.run("apps.save", params("name" to "jeu", "html" to "y")).payload().getBoolean("shown"))
        assertFalse(apps.run("apps.open", params("name" to "jeu"))!!.getBoolean("ok"))
        assertFalse(apps.run("apps.save", params("name" to "trois", "html" to "z"))!!.getBoolean("ok"))
        for (name in listOf("", "../secret", "Majuscule", "a".repeat(33))) {
            assertFalse(name, apps.run("apps.save", params("name" to name, "html" to "x"))!!.getBoolean("ok"))
        }
        assertFalse(apps.run("apps.save", params("name" to "gros", "html" to "x".repeat(200_001)))!!.getBoolean("ok"))
        assertEquals(2, apps.run("apps.list", params()).payload().getJSONArray("apps").length())
        apps.run("apps.remove", params("name" to "jeu")).payload()
        assertFalse(apps.run("apps.remove", params("name" to "jeu"))!!.getBoolean("ok"))
        assertNull(apps.run("apps.install", params()))
    }

    @Test fun anAppPageForbidsTheNetworkBeforeItsOwnContent() {
        val page = AppPage.wrap("<script>fetch('https://x')</script>")
        assertTrue(page.indexOf("default-src 'none'") < page.indexOf("fetch("))
        assertTrue(page.indexOf("RTCPeerConnection") < page.indexOf("fetch("))
        assertFalse("connect-src" in page)
    }
}
