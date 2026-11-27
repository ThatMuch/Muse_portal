package com.portalmuse.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReportTest {
    private val secret = "jeton mgst_SECRET https://exemple.test/chemin"

    private fun report(error: Throwable = IllegalStateException(secret)) =
        CrashReport.of(error, "com.portalmuse", 2, "0.19.0", 5321)

    @Test fun theMessageFollowsTheContractAndStaysSmall() {
        val message = report().message()
        assertEquals("link.error_report", message.getString("method"))
        assertFalse(message.has("id"))
        val body = message.getJSONObject("report")
        assertEquals(1, body.getInt("schema_version"))
        assertEquals("crash", body.getString("kind"))
        assertEquals("unhandled_exception", body.getString("reason"))
        assertEquals(2, body.getInt("consecutive_count"))
        assertEquals("IllegalStateException", body.getJSONObject("fault").getString("cause"))
        assertTrue(body.getJSONObject("fault").getString("detail"), "ErrorReportTest.kt:" in body.getJSONObject("fault").getString("detail"))
        assertTrue(message.toString().toByteArray().size < 512)
    }

    @Test fun noExceptionMessageEverLeaves() {
        val wrapped = RuntimeException(secret, IllegalArgumentException(secret))
        val text = report(wrapped).message().toString()
        for (word in listOf("SECRET", "exemple", "jeton")) assertFalse(text, word in text)
        assertEquals("IllegalArgumentException", report(wrapped).cause)
    }

    @Test fun longOrOddTextIsBoundedAndARecordSurvivesStorage() {
        val error = Exception().apply {
            stackTrace = arrayOf(StackTraceElement("com.portalmuse.X", "é\"" + "m".repeat(200), "Fichier.kt", 12))
        }
        val made = CrashReport.of(error, "com.portalmuse", 0, "v".repeat(60), -4)
        assertEquals(95, made.detail!!.length)
        assertFalse('"' in made.detail!! || 'é' in made.detail!!)
        assertEquals(31, made.version.length)
        assertEquals(1, made.consecutive)
        assertEquals(0, made.uptimeSeconds)
        assertTrue(made.message().toString().toByteArray().size < 512)
        assertEquals(made, CrashReport.restore(made.store()))
        assertNull(CrashReport.restore("{"))
        assertNull(CrashReport.restore(JSONObject().put("cause", "X").toString()))
    }
}
