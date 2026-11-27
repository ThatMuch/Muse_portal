package com.portalmuse.core

import com.portalmuse.core.ReplyGate.Verdict.DROP
import com.portalmuse.core.ReplyGate.Verdict.HOLD
import com.portalmuse.core.ReplyGate.Verdict.PLAY
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Portage des cas de linux/tests/test_voice_gate.py. */
class ReplyGateTest {
    private fun audio(id: String) = JSONObject().put("type", "continuous_audio_video.delta").put("response_id", id)
    private fun speech(item: String = "current") = JSONObject().put("type", "input_audio_buffer.speech_started").put("item_id", item)
    private fun turn(item: String = "current", submission: String = "turn") = JSONObject()
        .put("type", "hatch.voice.turn_started").put("item_id", item).put("submission_message_id", submission)

    @Test fun interruptedIdsNeverReopenAndPendingPrefixIsConfirmed() {
        val gate = ReplyGate()
        assertEquals(PLAY, gate.audio(audio("old")))
        gate.interrupt()
        assertEquals(DROP, gate.audio(audio("old")))
        assertEquals(DROP, gate.audio(audio("before-speech")))
        gate.event(speech())
        assertEquals(HOLD, gate.audio(audio("pending")))
        gate.event(turn())
        assertEquals(PLAY, gate.audio(audio("pending")))
        assertEquals(DROP, gate.audio(audio("old")))
        assertEquals(DROP, gate.audio(audio("before-speech")))
    }

    @Test fun continuationAndForeignLineageAreSeparated() {
        val gate = ReplyGate()
        gate.event(speech())
        gate.event(turn())
        assertEquals(PLAY, gate.audio(audio("own")))
        assertEquals(PLAY, gate.audio(audio("continuation")))
        assertEquals("un identifiant remplacé ne rejoue pas", DROP, gate.audio(audio("own")))
        gate.takeFlush()
        gate.event(turn("older", "late"))
        assertTrue(gate.blocked && gate.takeFlush().playing)
        assertEquals(DROP, gate.audio(audio("continuation")))
        assertEquals(DROP, gate.audio(audio("foreign")))
        gate.event(turn("current", "alias"))
        assertEquals(PLAY, gate.audio(audio("fresh")))
        assertEquals(DROP, gate.audio(audio("foreign")))
    }

    @Test fun foreignTurnDropsUnconfirmedPrefix() {
        val gate = ReplyGate()
        gate.event(speech())
        assertEquals(HOLD, gate.audio(audio("pending")))
        gate.event(turn("older", "late"))
        assertFalse(gate.waiting)
        assertEquals(DROP, gate.audio(audio("pending")))
    }

    @Test fun audioWithoutIdentityPlaysOnlyBeforeAnyInterruption() {
        val gate = ReplyGate()
        val anonymous = JSONObject().put("type", "response.audio.delta")
        assertEquals(PLAY, gate.audio(anonymous))
        gate.interrupt()
        assertEquals(DROP, gate.audio(anonymous))
    }

    @Test fun identitiesAreBounded() {
        val gate = ReplyGate()
        repeat(128) { gate.audio(audio("r$it")) }
        assertThrows(IllegalStateException::class.java) { gate.audio(audio("trop")) }
    }
}
