package com.portalmuse.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    private var result: PhotoResult = PhotoResult.Declined
    private val reasons = mutableListOf<String?>()
    private val commands = CameraCommands(object : CameraHost {
        override fun photo(reason: String?) = result.also { reasons.add(reason) }
    })

    private fun capture(params: JSONObject = JSONObject()) = commands.run("camera.capture", params)!!

    @Test fun onlyOneCommandAndItSaysTheOwnerDecides() {
        assertEquals(setOf("camera.capture"), commands.specs().keys)
        assertTrue("only if they tap" in commands.specs().getValue("camera.capture").getString("description"))
        assertNull(commands.run("camera.stream", JSONObject()))
    }

    @Test fun aSentPhotoUsesTheSdkShape() {
        result = PhotoResult.Taken(byteArrayOf(1, 2, 3))
        val payload = capture(JSONObject().put("reason", "r".repeat(300))).getJSONObject("payload")
        assertEquals("jpeg-base64", payload.getString("format"))
        assertEquals("AQID", payload.getString("data_base64"))
        assertEquals(200, reasons.single()!!.length)
    }

    @Test fun refusalAndFailureAreSaidWithoutAnyPicture() {
        assertTrue(capture().getString("error").startsWith("declined"))
        result = PhotoResult.Unavailable("camera busy")
        assertEquals("camera_capture_failed: camera busy", capture().getString("error"))
        assertNull(reasons.first())
    }
}
