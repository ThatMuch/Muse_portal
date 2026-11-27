package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEventsTest {
    @Test fun shortGoodbyesEndTheCall() {
        for (phrase in listOf(
            "Au revoir.", "au revoir !", "Merci, au revoir", "Merci beaucoup Nova, au revoir Nova.",
            "OK bye bye", "Bonne nuit !", "À bientôt", "a plus tard", "Tu peux raccrocher.", "Raccroche",
            "Termine l'appel", "Goodbye", "Ciao !",
        )) {
            assertTrue(phrase, Farewell.matches(phrase, "Nova"))
        }
    }

    @Test fun otherSentencesDoNot() {
        for (phrase in listOf(
            "", "Salut", "Au revoir se dit comment en italien ?", "Rappelle-moi à demain",
            "Je voudrais savoir comment on dit élégamment au revoir", "Ne raccroche pas encore", "Bonsoir",
        )) {
            assertFalse(phrase, Farewell.matches(phrase, "Nova"))
        }
    }

    private val secret = "mon secret"
    private val relayed = JSONObject().put("type", "event").put("event", "hatch.voice.realtime_event")
        .put(
            "payload",
            JSONObject().put("session_id", "abc-123")
                .put("data", JSONObject().put("type", "response.done").put("text", secret).toString())
                .put("items", JSONArray().put(JSONObject().put("name", secret).put("type", secret)))
                .put(secret, 4),
        )

    @Test fun objectsAreFoundEvenInsideAString() {
        assertTrue(VoiceEvents.objects(relayed).any { it.opt("type") == "response.done" })
    }

    @Test fun theShapeNeverCarriesContent() {
        val shape = VoiceEvents.shape(relayed)
        assertTrue(shape, "payload.data{}.type=response.done" in shape)
        assertTrue(shape, "payload.data{}.text:string" in shape)
        assertTrue(shape, "payload.items[].type:string" in shape)
        for (word in listOf("secret", "abc-123")) assertFalse(shape, word in shape)
        assertEquals("", VoiceEvents.shape(JSONObject()))
    }
}
