// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/ble_setup.py
package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executor

private val SENSITIVE_ACTIONS = setOf(
    "provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth",
)
private val PLAINTEXT_STATUSES = setOf(
    "error_encryption_required", "error_pairing_invalid_hello", "error_pairing_unavailable", "error_pairing_decrypt",
)
private const val DISCONNECT_AFTER_ERROR_MS = 300L

interface SetupTransport {
    /** Notifies the packets in order; blocks until they are queued. */
    fun sendPackets(packets: List<ByteArray>)
    fun mtu(): Int
    fun disconnect(delayMillis: Long)
}

interface SetupNetwork {
    fun isOnline(): Boolean
    fun currentConnectionEntry(): JSONObject
}

class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
) {
    /** Never a token in a log. */
    override fun toString() = "Credentials(<hidden>)"
}

/** Setup did not complete; [status] is what the app learns. */
class ProvisionFailed(val status: String) : Exception(status)

/**
 * Logic of the setup commands behind the GATT characteristics.
 *
 * [handleMessage] handles one message at a time: the transport calls it from a single thread, in arrival order.
 * All sends go through the same lock so that encrypted counters arrive in order. [provision] runs on
 * [provisionExecutor]; it checks the tokens and saves them through `commit`, or throws [ProvisionFailed].
 */
class SetupController(
    private val pairing: PairingSession,
    private val identity: Identity,
    private val version: String,
    private val transport: SetupTransport,
    private val network: SetupNetwork,
    private val provision: (Credentials, commit: (() -> Boolean) -> Boolean) -> Unit,
    private val onComplete: () -> Unit = {},
    private val provisionExecutor: Executor = Executor { Thread(it, "provision").start() },
    private val log: (String) -> Unit = {},
) {
    private val assembler = ChunkAssembler()
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var provisioning = false

    /** Reassembles a write; returns the complete message to pass to [handleMessage]. */
    fun assemble(packet: ByteArray): ByteArray? = assembler.feed(packet)

    fun onDisconnect() {
        log("BLE client disconnected; pairing session erased")
        assembler.reset()
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
    }

    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val command = parse(raw)
        if (command == null) {
            log("invalid JSON command (${raw.size} bytes)")
            sendStatus("error_invalid_command")
            return
        }
        val action = command.opt("action") as? String ?: ""
        log("RX ${action.take(40).ifEmpty { "?" }}${if (decrypted) " (encrypted)" else ""}")
        val blocked = synchronized(stateLock) { plaintextBlocked }
        when {
            !decrypted && action == "pairing_client_hello" -> handleHello(command)
            !decrypted && action == "pairing_encrypted" -> handleRecord(command)
            // Public metadata, answered in clear at any time.
            action == "get_device_info" -> sendJson(deviceInfo())
            !decrypted && blocked -> log("clear command ignored after pairing started")
            !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
            decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed ->
                sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> handleWifiScan()
            decrypted && action == "provision_v2" -> handleProvision(command)
            else -> sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): JSONObject {
        val info = pairing.deviceInfo()
        return info
            .put("type", "device_info")
            .put("node_id", identity.nodeId)
            .put("version", version)
            .put("build_sha", "")
            .put("network_ready", network.isOnline())
    }

    private fun parse(raw: ByteArray): JSONObject? = try {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
        val tokener = JSONTokener(text)
        (tokener.nextValue() as? JSONObject)?.takeIf { tokener.nextClean() == '\u0000' }
    } catch (e: JSONException) {
        null
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private fun handleHello(command: JSONObject) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingError) {
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        sendJson(ready)
    }

    private fun handleRecord(envelope: JSONObject) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingError) {
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        handleMessage(plaintext.toByteArray(), decrypted = true)
    }

    private fun handleClientFinished(command: JSONObject) {
        val generation = pairing.handleClientFinished(command)
        if (generation == 0) {
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        log("pairing confirmed (consent in the app)")
        sendStatus("pairing_confirmed", generation)
    }

    private fun handleWifiScan() {
        val networks = JSONArray()
        if (network.isOnline()) networks.put(network.currentConnectionEntry())
        sendEncryptedJson(JSONObject().put("type", "wifi_scan_result").put("networks", networks))
    }

    private fun handleProvision(command: JSONObject) {
        fun text(key: String) = command.opt(key) as? String ?: ""

        if (command.opt("ssid") !is String || command.opt("password") !is String ||
            text("access_token").isEmpty() || text("refresh_token").isEmpty() || text("token_type") != "device"
        ) {
            sendStatus("error_missing_credentials")
            return
        }
        if (!ServerHosts.apiAllowed(text("api_url")) || !ServerHosts.apiAllowed(text("api_url_v2")) ||
            !ServerHosts.noiseAllowed(text("noise_host"))
        ) {
            log("the setup names a refused server")
            sendStatus("error_invalid_host")
            return
        }
        var generation = 0
        val refusal = synchronized(stateLock) {
            if (provisioning) {
                "error_operation_in_progress"
            } else {
                generation = pairing.markProvisioning()
                provisioning = generation != 0
                if (generation != 0) null else "error_pairing_confirm_required"
            }
        }
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // Wi-Fi fields are ignored on purpose: the device is already online.
        val credentials = Credentials(
            text("access_token"), text("refresh_token"), text("username"),
            text("api_url"), text("api_url_v2"), text("noise_host"),
        )
        provisionExecutor.execute { runProvision(credentials, generation) }
    }

    private fun runProvision(credentials: Credentials, generation: Int) {
        try {
            sendStatus("wifi_connecting", generation)
            if (!network.isOnline()) {
                // Stay in provisioning so that the app can retry.
                pairing.extendProvisioning(generation)
                sendStatus("wifi_failed", generation)
                return
            }
            sendStatus("wifi_connected", generation)
            try {
                provision(credentials) { save -> pairing.commitProvisioning(generation, save) }
            } catch (e: ProvisionFailed) {
                log("provisioning refused: ${e.status}")
                sendStatus(e.status, generation)
                transport.disconnect(500)
                return
            } catch (e: RuntimeException) {
                // The type only: the message could contain a token.
                log("provisioning interrupted: ${e.javaClass.simpleName}")
                sendStatus("error_storage", generation)
                transport.disconnect(500)
                return
            }
            sendStatus("auth_ok", generation)
            log("configuration done")
            onComplete()
        } finally {
            synchronized(stateLock) { provisioning = false }
        }
    }

    private fun sendStatus(status: String, generation: Int = 0) {
        synchronized(txLock) {
            val envelope = pairing.encryptStatus(status, generation)
            if (envelope != null) {
                sendLocked(envelope.toString())
                log("TX encrypted status: $status")
                return
            }
            val blocked = synchronized(stateLock) { plaintextBlocked }
            if (generation != 0 || blocked || status !in PLAINTEXT_STATUSES) {
                log("TX status dropped: $status")
                return
            }
            transport.sendPackets(listOf(status.toByteArray()))
            log("TX status: $status")
        }
    }

    private fun sendJson(message: JSONObject) {
        synchronized(txLock) {
            sendLocked(message.toString())
            log("TX ${message.optString("type")}")
        }
    }

    private fun sendEncryptedJson(message: JSONObject) {
        synchronized(txLock) {
            val envelope = pairing.encryptJson(message.toString())
            if (envelope == null) {
                log("TX ${message.optString("type")} dropped: no session")
                return
            }
            sendLocked(envelope.toString())
            log("TX ${message.optString("type")} (encrypted)")
        }
    }

    private fun sendLocked(text: String) {
        transport.sendPackets(BleFraming.encodeChunks(text.toByteArray(), transport.mtu()))
    }
}
