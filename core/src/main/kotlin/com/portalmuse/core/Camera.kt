package com.portalmuse.core

import org.json.JSONObject
import java.util.Base64

sealed class PhotoResult {
    class Taken(val jpeg: ByteArray) : PhotoResult()
    /** Declined, or left unanswered. */
    object Declined : PhotoResult()
    class Unavailable(val reason: String) : PhotoResult()
}

interface CameraHost {
    /** Blocks: the owner sees the live picture and decides to take it and then send it. */
    fun photo(reason: String?): PhotoResult
}

/** A photo at Muse's request, never taken or sent without two touches on screen. */
class CameraCommands(private val host: CameraHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        CAPTURE to Specs.of(
            "Ask the owner for one photo from the device camera, when they want you to look at something. The " +
                "screen shows a live view and your reason; a picture is returned only if they tap to take it and " +
                "then to send it. Returns JPEG as base64. Do not ask repeatedly after a refusal.",
            JSONObject(),
            JSONObject().put("reason", Specs.field("string", "One short sentence shown to the owner, in their language.")),
        ).put("timeout_ms", 200_000),
    )

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command != CAPTURE) return null
        return when (val result = host.photo(Specs.text(params, "reason", 200))) {
            is PhotoResult.Taken -> Specs.ok(
                JSONObject().put("format", "jpeg-base64").put("data_base64", Base64.getEncoder().encodeToString(result.jpeg)),
            )
            PhotoResult.Declined -> Specs.error("declined: the owner did not send a photo")
            is PhotoResult.Unavailable -> Specs.error("camera_capture_failed: ${result.reason}")
        }
    }

    private companion object {
        const val CAPTURE = "camera.capture"
    }
}
