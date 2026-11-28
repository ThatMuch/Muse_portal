// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/pairing.py
package com.portalmuse.core

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

private const val PAIRING_VERSION = 5
private const val PAIRING_MODEL = "hatch_link"
private const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
private const val POLICY_BUTTON = "confirm_press"
private const val POLICY_APP = "confirm_app"
private const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
private const val AUTH_COMMUNITY = "none"
private const val BUTTON_CONFIRM_TIMEOUT_S = 60
private const val RECORD_LABEL = "hatch-link ble setup v1"
private const val SESSION_ID_LABEL = "hatch-link session id v1"

private const val CLIENT_FINISHED_TIMEOUT_MS = 60_000L
private const val CONFIRMED_TIMEOUT_MS = 120_000L
private const val PROVISIONING_TIMEOUT_MS = 120_000L

private const val NONCE_BYTES = 16
private const val SESSION_ID_BYTES = 16
private const val TAG_BYTES = 16
private const val MAX_B64_CHARS = 4096
private const val MAX_CIPHERTEXT_B64_CHARS = 16384
private const val TO_DEVICE = 0
private const val FROM_DEVICE = 1

/** A handshake step failed; [status] is the error to send back to the phone. */
class PairingError(val status: String) : Exception(status) {
    companion object {
        const val INVALID_HELLO = "error_pairing_invalid_hello"
        const val DECRYPT = "error_pairing_decrypt"
    }
}

enum class PairingState { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

/** Pure functions of the v5 protocol. */
object Pairing {
    private val B64URL = Regex("[A-Za-z0-9_-]+")
    private val DECIMAL = Regex("[0-9]+")

    fun b64urlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** base64url without padding, which rejects everything the firmware rejects. */
    fun b64urlDecode(text: Any?, maxChars: Int = MAX_B64_CHARS): ByteArray {
        require(text is String && text.length in 1..maxChars) { "invalid base64url length" }
        require(text.length % 4 != 1 && B64URL.matches(text)) { "invalid base64url" }
        return Base64.getUrlDecoder().decode(text)
    }

    /** Unsigned 64 bit decimal counter. */
    fun parseCounter(text: Any?): Long {
        require(text is String && DECIMAL.matches(text)) { "invalid counter" }
        return java.lang.Long.parseUnsignedLong(text)
    }

    /** Canonical v5 transcript; its SHA-256 is the `transcript_hash`. */
    fun buildTranscript(
        community: Boolean,
        authEpoch: Int,
        policy: String,
        deviceId: String,
        nodeId: String,
        mac: String,
        firmwareVersion: String,
        mobilePub: String,
        devicePub: String,
        mobileNonce: String,
        deviceNonce: String,
    ): String {
        val button = policy == POLICY_BUTTON
        require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: $policy" }
        require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid authentication epoch" }
        val fields = listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
        require(fields.all { it.isNotEmpty() }) { "transcript fields cannot be empty" }
        return listOf(
            "hatch-link-pairing-v$PAIRING_VERSION",
            "version=$PAIRING_VERSION",
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=$deviceId",
            "node_id=$nodeId",
            "mac=$mac",
            "model=$PAIRING_MODEL",
            "firmware_version=$firmwareVersion",
            "selected_cipher_suite=$SUITE",
            "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
            "pairing_auth_epoch=$authEpoch",
            "pairing_policy=$policy",
            "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
            "mobile_pub=$mobilePub",
            "device_pub=$devicePub",
            "mobile_nonce=$mobileNonce",
            "device_nonce=$deviceNonce",
        ).joinToString("\n")
    }

    fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        mobileNonce: ByteArray,
        deviceNonce: ByteArray,
        transcriptHash: ByteArray,
    ): SessionKeys {
        val salt = Primitives.sha256(mobileNonce, deviceNonce, transcriptHash)
        val secret = Primitives.hkdf(ecdhSecret, salt, RECORD_LABEL.toByteArray(), 32)
        return SessionKeys(
            mobileTx = Primitives.hkdfExpand(secret, "mobile->device".toByteArray(), 32),
            mobileRx = Primitives.hkdfExpand(secret, "device->mobile".toByteArray(), 32),
            sessionId = Primitives.sha256(SESSION_ID_LABEL.toByteArray(), transcriptHash, ecdhSecret)
                .copyOf(SESSION_ID_BYTES),
        )
    }

    internal fun recordNonce(direction: Int, counter: Long): ByteArray = ByteArray(12).also { nonce ->
        nonce[0] = direction.toByte()
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    }

    internal fun recordAad(sessionId: String, direction: Int, counter: Long): ByteArray {
        val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
        return "$RECORD_LABEL|$sessionId|$arrow|${java.lang.Long.toUnsignedString(counter)}".toByteArray()
    }
}

/**
 * Device side pairing state, community mode only (`pairing_auth: none`, policy `confirm_app`). Protects against
 * passive listening, not against an active man in the middle.
 *
 * Methods that move the handshake forward return a non-zero generation: deferred work keeps it and checks it
 * before acting.
 */
class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    /** Monotonic clock in milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val generateKey: () -> P256KeyPair = P256::generate,
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
) {
    private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
    private var generation = 0
    private var current = PairingState.IDLE
    private var deadline = 0L
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var sessionId = ""
    private var rxCounter = 0L
    private var txCounter = 0L

    /** Pairing fields of the `get_device_info` response. */
    fun deviceInfo(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("mac", mac)
        .put("model", PAIRING_MODEL)
        .put("pairing_protocol", PAIRING_VERSION)
        .put("pairing_auth", AUTH_COMMUNITY)
        .put("pairing_auth_epoch", 0)
        .put("pairing_policy", POLICY_APP)

    val state: PairingState
        @Synchronized get() {
            expire()
            return current
        }

    val confirmed: Boolean
        get() = state.let { it == PairingState.READY || it == PairingState.PROVISIONING }

    @Synchronized
    fun isCurrent(value: Int): Boolean = value != 0 && value == generation

    @Synchronized
    fun reset() {
        generation++
        clear()
    }

    /** Opens a session from `pairing_client_hello`; returns `pairing_ready`. */
    @Synchronized
    fun handleHello(message: JSONObject): JSONObject {
        val version = message.opt("version")
        if (version !is Number || version.toDouble() != PAIRING_VERSION.toDouble() ||
            message.opt("pairing_auth") != AUTH_COMMUNITY || message.opt("pairing_policy") != POLICY_APP
        ) {
            throw PairingError(PairingError.INVALID_HELLO)
        }
        reset()
        try {
            val mobilePub = Pairing.b64urlDecode(message.opt("mobile_pub"))
            val mobileNonce = Pairing.b64urlDecode(message.opt("mobile_nonce"))
            require(mobileNonce.size == NONCE_BYTES) { "invalid nonce" }
            P256.decodePoint(mobilePub)

            val deviceKey = generateKey()
            val deviceNonce = randomBytes(NONCE_BYTES)
            val devicePub = Pairing.b64urlEncode(deviceKey.publicPoint)
            val transcript = Pairing.buildTranscript(
                community = true,
                authEpoch = 0,
                policy = POLICY_APP,
                deviceId = deviceId,
                nodeId = nodeId,
                mac = mac,
                firmwareVersion = firmwareVersion,
                mobilePub = Pairing.b64urlEncode(mobilePub),
                devicePub = devicePub,
                mobileNonce = Pairing.b64urlEncode(mobileNonce),
                deviceNonce = Pairing.b64urlEncode(deviceNonce),
            )
            val transcriptHash = Primitives.sha256(transcript.toByteArray())
            val secret = P256.ecdh(deviceKey.privateKey, mobilePub)
            val keys = Pairing.deriveSessionKeys(secret, mobileNonce, deviceNonce, transcriptHash)

            rxKey = keys.mobileTx
            txKey = keys.mobileRx
            sessionId = Pairing.b64urlEncode(keys.sessionId)
            rxCounter = 0
            txCounter = 0
            current = PairingState.WAIT_CLIENT_FINISHED
            deadline = clock() + CLIENT_FINISHED_TIMEOUT_MS
            return JSONObject()
                .put("type", "pairing_ready")
                .put("version", PAIRING_VERSION)
                .put("device_id", deviceId)
                .put("node_id", nodeId)
                .put("mac", mac)
                .put("model", PAIRING_MODEL)
                .put("firmware_version", firmwareVersion)
                .put("pairing_auth", AUTH_COMMUNITY)
                .put("pairing_auth_epoch", 0)
                .put("pairing_policy", POLICY_APP)
                .put("device_pub", devicePub)
                .put("device_nonce", Pairing.b64urlEncode(deviceNonce))
                .put("transcript_hash", Pairing.b64urlEncode(transcriptHash))
                .put("session_id", sessionId)
        } catch (e: IllegalArgumentException) {
            reset()
            throw PairingError(PairingError.INVALID_HELLO)
        } catch (e: GeneralSecurityException) {
            reset()
            throw PairingError(PairingError.INVALID_HELLO)
        }
    }

    /** Decrypts a phone to device record. Any failure erases the session. */
    @Synchronized
    fun decrypt(envelope: JSONObject): String {
        val key = rxKey
        if (expire() || current == PairingState.IDLE || key == null) fail()
        try {
            require(envelope.opt("session_id") == sessionId) { "other session" }
            val counter = Pairing.parseCounter(envelope.opt("counter"))
            require(counter == rxCounter) { "unexpected counter" }
            val ciphertext = Pairing.b64urlDecode(envelope.opt("ciphertext"), MAX_CIPHERTEXT_B64_CHARS)
            val tag = Pairing.b64urlDecode(envelope.opt("tag"))
            require(tag.size == TAG_BYTES) { "invalid tag" }
            val plain = Primitives.aesGcm(
                false, key, Pairing.recordNonce(TO_DEVICE, counter),
                Pairing.recordAad(sessionId, TO_DEVICE, counter), ciphertext + tag,
            )
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
            rxCounter++
            return text
        } catch (e: IllegalArgumentException) {
            fail()
        } catch (e: GeneralSecurityException) {
            fail()
        } catch (e: java.nio.charset.CharacterCodingException) {
            fail()
        }
    }

    /**
     * Confirms the session after the first record, which must be exactly
     * `{"action":"pairing_client_finished"}`. Returns the generation, or 0.
     */
    @Synchronized
    fun handleClientFinished(command: JSONObject): Int {
        val ok = command.length() == 1 && command.opt("action") == "pairing_client_finished" &&
            !expire() && current == PairingState.WAIT_CLIENT_FINISHED && rxCounter == 1L
        if (!ok) {
            reset()
            return 0
        }
        generation++
        current = PairingState.READY
        deadline = clock() + CONFIRMED_TIMEOUT_MS
        return generation
    }

    /** Moves to provisioning from a confirmed session; returns its generation, or 0. */
    @Synchronized
    fun markProvisioning(): Int {
        if (!expire() && current == PairingState.READY) {
            generation++
            current = PairingState.PROVISIONING
            deadline = clock() + PROVISIONING_TIMEOUT_MS
        }
        return if (current == PairingState.PROVISIONING) generation else 0
    }

    @Synchronized
    fun extendProvisioning(value: Int): Boolean =
        provisioning(value).also { if (it) deadline = clock() + PROVISIONING_TIMEOUT_MS }

    /** Runs [commit] under the lock if the session is still valid. No network or BLE calls. */
    @Synchronized
    fun commitProvisioning(value: Int, commit: () -> Boolean): Boolean = provisioning(value) && commit()

    /** Seals a device to phone record; `null` without a session or if stale. */
    @Synchronized
    fun encryptJson(plaintext: String, value: Int = 0): JSONObject? {
        if ((value != 0 && value != generation) || expire() || current == PairingState.IDLE) return null
        val key = txKey ?: return null
        val counter = txCounter
        val sealed = Primitives.aesGcm(
            true, key, Pairing.recordNonce(FROM_DEVICE, counter),
            Pairing.recordAad(sessionId, FROM_DEVICE, counter), plaintext.toByteArray(),
        )
        txCounter++
        return JSONObject()
            .put("type", "pairing_encrypted")
            .put("session_id", sessionId)
            .put("counter", counter.toString())
            .put("ciphertext", Pairing.b64urlEncode(sealed.copyOfRange(0, sealed.size - TAG_BYTES)))
            .put("tag", Pairing.b64urlEncode(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
    }

    fun encryptStatus(status: String, value: Int = 0): JSONObject? {
        val message = JSONObject().put("type", "status").put("status", status)
        // The SDK token only travels encrypted, with pairing_confirmed.
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") message.put("sdk_token", sdkToken)
        return encryptJson(message.toString(), value)
    }

    private fun fail(): Nothing {
        reset()
        throw PairingError(PairingError.DECRYPT)
    }

    private fun provisioning(value: Int) =
        value != 0 && value == generation && !expire() && current == PairingState.PROVISIONING

    private fun clear() {
        current = PairingState.IDLE
        deadline = 0
        rxKey = null
        txKey = null
        sessionId = ""
        rxCounter = 0
        txCounter = 0
    }

    /** Erases the keys on expiry but keeps the generation. */
    private fun expire(): Boolean {
        if (current == PairingState.IDLE || clock() <= deadline) return false
        clear()
        return true
    }
}
