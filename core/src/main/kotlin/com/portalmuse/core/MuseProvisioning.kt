// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Sources:
// linux/src/musegadget/pair.py (_verify_and_save), config.py
package com.portalmuse.core

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/** SDK token issued by gadgets.muse.ai: `mgst_` followed by 43 base64url characters. */
object SdkToken {
    private val FORMAT = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")

    fun isValid(token: String): Boolean = FORMAT.matches(token)

    /** What may be shown of a token: never any of its characters. */
    fun fingerprint(token: String?): String {
        if (token.isNullOrEmpty()) return tr("missing", "absent")
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        val prefix = digest.take(4).joinToString("") { "%02x".format(it) }
        return tr("${token.length} characters, sha256 $prefix", "${token.length} caractères, sha256 $prefix")
    }
}

/** The saved pairing. [toJson] contains the tokens: encrypt it before any storage. */
class PairingRecord(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
    /** Seconds since 1970. */
    val accessTokenSavedAt: Long,
    /** The identifier proposed at pairing, the one of the tokens. Never renamed. */
    val nodeId: String,
) {
    fun toJson(): String = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("token_type", "device")
        .put("username", username)
        .put("api_url", apiUrl)
        .put("api_url_v2", apiUrlV2)
        .put("noise_host", noiseHost)
        .put("access_token_saved_at", accessTokenSavedAt)
        .put("node_id", nodeId)
        .toString()

    /** Same pairing, new tokens. */
    fun rotated(accessToken: String, refreshToken: String, nowSeconds: Long) = PairingRecord(
        accessToken, refreshToken, username, apiUrl, apiUrlV2, noiseHost, nowSeconds, nodeId,
    )

    override fun toString() = "PairingRecord(nodeId=$nodeId, tokens hidden)"

    companion object {
        private val NODE_ID = Regex("[a-z]+-[0-9a-f]{6}")

        fun fromJson(text: String): PairingRecord? {
            val data = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            val access = data.optString("access_token")
            val refresh = data.optString("refresh_token")
            val nodeId = data.optString("node_id")
            if (access.isBlank() || refresh.isBlank() || !NODE_ID.matches(nodeId)) return null
            return PairingRecord(
                access, refresh, data.optString("username"), data.optString("api_url"),
                data.optString("api_url_v2"), data.optString("noise_host"),
                data.optLong("access_token_saved_at"), nodeId,
            )
        }
    }
}

object MuseProvisioning {
    /** Checks the tokens with Muse, then saves them under the pairing lock. */
    fun verifyAndSave(
        api: MuseApi,
        credentials: Credentials,
        commit: (() -> Boolean) -> Boolean,
        sdkToken: String?,
        nodeId: String,
        nowSeconds: Long,
        log: (String) -> Unit = {},
        save: (PairingRecord) -> Boolean,
    ) {
        fun https(url: String) = if (url.startsWith("https://")) url else ""
        val apiUrlV2 = https(credentials.apiUrlV2)
        log("checking tokens, SDK token ${SdkToken.fingerprint(sdkToken)}")
        val lookup = api.fetchVms(credentials.accessToken, apiUrlV2, log)
        if (lookup.vms.isEmpty()) {
            log("device token not verified (HTTP ${lookup.status})")
            throw ProvisionFailed("auth_failed")
        }
        val record = PairingRecord(
            credentials.accessToken, credentials.refreshToken, credentials.username,
            https(credentials.apiUrl), apiUrlV2, credentials.noiseHost, nowSeconds, nodeId,
        )
        if (!commit { save(record) }) throw ProvisionFailed("error_storage")
    }
}
