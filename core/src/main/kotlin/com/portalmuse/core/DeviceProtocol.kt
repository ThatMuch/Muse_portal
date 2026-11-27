// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Sources : linux/src/musegadget/link_client.py, device_guide.py, protocols/DEVICE.md
package com.portalmuse.core

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024
private const val MAX_GUIDE_BYTES = 4096

/** Messages du flux de contrôle : JSON précédé de sa longueur en u32 petit-boutiste. */
object ControlMessages {
    fun encode(message: JSONObject): ByteArray {
        val data = message.toString().toByteArray()
        return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()
    }
}

/** Un message peut s'étaler sur plusieurs morceaux de corps : on tamponne jusqu'à complétude. */
class LengthPrefixedDecoder(private val log: (String) -> Unit = {}) {
    private var buffer = ByteArray(0)

    fun feed(data: ByteArray): List<JSONObject> {
        buffer = ByteArrayOutputStream().apply { write(buffer); write(data) }.toByteArray()
        val messages = ArrayList<JSONObject>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val length = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || length > MAX_INBOUND_MESSAGE) throw NoiseError("message entrant trop grand")
            if (buffer.size - offset - 4 < length) break
            val raw = String(buffer, offset + 4, length)
            offset += 4 + length
            if (raw.isEmpty()) continue // maintien de connexion
            try {
                messages.add(JSONObject(raw))
            } catch (e: JSONException) {
                log("message de contrôle illisible ignoré ($length octets)")
            } catch (e: StackOverflowError) {
                log("message de contrôle trop imbriqué ignoré")
            }
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return messages
    }
}

/** Ce que l'appareil déclare dans `link.register`. */
class DeviceDescription(
    val nodeId: String,
    private val displayName: String,
    private val version: String,
    private val modelId: String,
    private val modelName: String,
) {
    init {
        require(MODEL_ID.matches(modelId)) { "model_id invalide" }
        require(MODEL_NAME.matches(modelName)) { "model_name invalide" }
    }

    fun registerMessage(requestId: String, commands: JSONObject): JSONObject = JSONObject()
        .put("type", "req")
        .put("id", requestId)
        .put("method", "link.register")
        .put(
            "params",
            JSONObject()
                .put("node_id", nodeId)
                .put("display_name", displayName)
                // Comme le client Linux, seul client à base de noyau Linux du SDK.
                .put("platform", "linux")
                .put("version", version)
                .put("device_family", "gadget")
                .put("model_id", modelId)
                .put("metadata", JSONObject().put("model_name", modelName))
                .put("is_wakeup_supported", false)
                .put("commands_v2", commands),
        )

    private companion object {
        val MODEL_ID = Regex("[a-z0-9-]{1,64}")
        val MODEL_NAME = Regex("[ !#-\\[\\]-~]{1,128}")
    }
}

/**
 * Les commandes exposées à Muse : `device.health`, `device.guide` et, si l'utilisateur l'autorise,
 * les groupes liés à l'écran. Ni shell, ni fichiers, ni caméra, ni micro.
 */
class DeviceCommands(
    private val health: () -> JSONObject,
    private val guide: String,
    private vararg val sets: CommandSet,
    /** La description structurée de l'appareil, jointe au guide (contrat du SDK). */
    private val capabilities: JSONObject? = null,
) {
    init {
        require(guide.toByteArray().size in 1..MAX_GUIDE_BYTES) { "guide vide ou trop long" }
    }

    fun specs(): JSONObject {
        fun spec(description: String) = JSONObject()
            .put("description", description)
            .put("required", JSONObject())
            .put("optional", JSONObject())
        val specs = JSONObject()
            .put(HEALTH, spec("Device status: uptime, memory, disk, network, screen, software version."))
            .put(GUIDE, spec("This device's usage guide (Markdown): what it is, its controls and limits."))
        for (set in sets) set.specs().forEach { (name, value) -> specs.put(name, value) }
        return specs
    }

    fun run(command: String, params: JSONObject = JSONObject()): JSONObject = when (command) {
        HEALTH -> try {
            ok(health())
        } catch (e: RuntimeException) {
            error("command failed")
        }
        GUIDE -> ok(JSONObject().put("format", "markdown").put("guide", guide).putOpt("capabilities", capabilities))
        else -> try {
            sets.firstNotNullOfOrNull { it.run(command, params) } ?: error("unsupported command")
        } catch (e: RuntimeException) {
            error("command failed")
        }
    }

    private fun ok(payload: JSONObject) = JSONObject().put("ok", true).put("payload", payload)
    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    private companion object {
        const val HEALTH = "device.health"
        const val GUIDE = "device.guide"
    }
}
