package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    private var saved: List<FeedItem> = emptyList()
    private var opened = false
    private val commands = FeedCommands(object : FeedHost {
        override fun loadPhoto(source: PictureSource): Any? = source.toString().takeIf { "boom" !in it }
        override fun setFeed(items: List<FeedItem>, open: Boolean): Boolean {
            saved = items
            opened = open
            return open
        }
    })

    private fun item(title: String) = JSONObject().put("title", title)
    private fun set(vararg items: JSONObject, open: Boolean = false) =
        commands.run("feed.set", JSONObject().put("items", JSONArray(items.toList())).put("open", open))!!

    @Test fun theFeedIsStoredWithDefaultsAndItsImages() {
        val result = set(
            item("Spectacle").put("text", "Samedi").put("icon", "🎻").put("image_url", "https://a.example/1.jpg"),
            item("Brocante"),
            item("Concert").put("image_path", "boom.jpg").put("action_label", "Réserver").put("action_message", "Réserve"),
            open = true,
        )
        val payload = result.getJSONObject("payload")
        assertEquals(3, payload.getInt("items"))
        assertTrue(payload.getBoolean("shown") && opened)
        assertEquals("[3]", payload.getJSONArray("failed_images").toString())
        assertTrue(saved[0].image != null && saved[1].image == null && saved[2].image == null)
        assertEquals("C'est parti", saved[1].actionLabel)
        assertEquals("C'est parti pour : Brocante", saved[1].actionMessage)
        assertEquals("Réserver", saved[2].actionLabel)
    }

    @Test fun anEmptyListClearsAndNeverOpens() {
        assertEquals(0, set(open = true).getJSONObject("payload").getInt("items"))
        assertFalse(opened)
    }

    @Test fun badFeedsAreRefused() {
        assertFalse(commands.run("feed.set", JSONObject())!!.getBoolean("ok"))
        assertFalse(set(JSONObject()).getBoolean("ok"))
        assertFalse(set(*Array(13) { item("x") }).getBoolean("ok"))
        assertNull(commands.run("feed.get", JSONObject()))
    }
}
