package com.portalmuse.core

import org.json.JSONObject
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun unb64(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)
fun b64(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

fun resource(name: String): JSONObject = JSONObject(
    checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "ressource absente : $name" }
        .bufferedReader().readText(),
)

fun pairingVectors(): List<JSONObject> {
    val array = resource("link_pairing_v5.json").getJSONArray("vectors")
    return (0 until array.length()).map { array.getJSONObject(it) }
}

fun pairingVector(name: String): JSONObject = pairingVectors().single { it.getString("name") == name }

class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke() = now
}

fun makeDevice(v: JSONObject, clock: FakeClock = FakeClock(), sdkToken: String? = null) = PairingSession(
    nodeId = v.getString("node_id"),
    deviceId = v.getString("device_id"),
    mac = v.getString("mac"),
    firmwareVersion = v.getString("firmware_version"),
    sdkToken = sdkToken,
    clock = clock,
    generateKey = {
        P256KeyPair(
            P256.privateKeyFromScalar(BigInteger(v.getString("device_private_scalar_hex"), 16)),
            unb64(v.getString("device_pub")),
        )
    },
    randomBytes = { unb64(v.getString("device_nonce")) },
)

fun hello(v: JSONObject): JSONObject = JSONObject()
    .put("action", "pairing_client_hello")
    .put("version", 5)
    .put("pairing_auth", "none")
    .put("pairing_policy", "confirm_app")
    .put("mobile_pub", v.getString("mobile_pub"))
    .put("mobile_nonce", v.getString("mobile_nonce"))

fun clientFinishedRecord(v: JSONObject): JSONObject = JSONObject()
    .put("action", "pairing_encrypted")
    .put("session_id", v.getString("session_id"))
    .put("counter", "0")
    .put("ciphertext", v.getString("client_finished_ciphertext"))
    .put("tag", v.getString("client_finished_tag"))

/** Phone side of the record layer, written from the contract and not from the tested code. */
class Mobile(private val v: JSONObject) {
    var txCounter = 0L

    private fun gcm(mode: Int, keyField: String, direction: Int, counter: Long, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = direction.toByte()
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        val arrow = if (direction == 0) "m2d" else "d2m"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(hex(v.getString(keyField)), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("hatch-link ble setup v1|${v.getString("session_id")}|$arrow|$counter".toByteArray())
        return cipher.doFinal(data)
    }

    fun seal(command: JSONObject, counter: Long = txCounter++): JSONObject {
        val sealed = gcm(Cipher.ENCRYPT_MODE, "mobile_tx_key_hex", 0, counter, command.toString().toByteArray())
        return JSONObject()
            .put("action", "pairing_encrypted")
            .put("session_id", v.getString("session_id"))
            .put("counter", counter.toString())
            .put("ciphertext", b64(sealed.copyOfRange(0, sealed.size - 16)))
            .put("tag", b64(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    fun open(envelope: JSONObject): JSONObject {
        check(envelope.getString("type") == "pairing_encrypted")
        check(envelope.getString("session_id") == v.getString("session_id"))
        val data = unb64(envelope.getString("ciphertext")) + unb64(envelope.getString("tag"))
        val plain = gcm(Cipher.DECRYPT_MODE, "mobile_rx_key_hex", 1, envelope.getString("counter").toLong(), data)
        return JSONObject(String(plain))
    }
}
