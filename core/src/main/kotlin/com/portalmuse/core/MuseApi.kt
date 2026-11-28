// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/muse_api.py
package com.portalmuse.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class Vm(val url: String, val authToken: String, val name: String, val id: String, val isDefault: Boolean) {
    override fun toString() = "Vm(id=$id)"
}

/** [status] is `null` if no HTTP response arrived (retry). */
class VmLookup(val vms: List<Vm>, val status: Int?)

class DeviceTokens(val accessToken: String, val refreshToken: String) {
    override fun toString() = "DeviceTokens(<hidden>)"
}

/** [status] 401: the pairing no longer exists. `null`: transport failure. */
class TokenRefresh(val tokens: DeviceTokens?, val status: Int?)

/** Client of Muse's device API. [client] carries the filtered trust store. */
class MuseApi(client: OkHttpClient, private val userAgent: String) {
    // No redirects: tokens only go to the allowed hosts.
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    fun fetchVms(accessToken: String, apiUrlV2: String, log: (String) -> Unit = {}): VmLookup {
        var status: Int? = null
        try {
            val request = Request.Builder()
                .url(apiRoot(apiUrlV2) + "/fetch_vms")
                .header("Authorization", "Bearer $accessToken")
                .header("X-API-Version", "1.0.0")
                .header("User-Agent", userAgent)
                .build()
            http.newCall(request).execute().use { response ->
                status = response.code
                if (!response.isSuccessful) {
                    log("VM list refused: HTTP ${response.code}")
                    return VmLookup(emptyList(), status)
                }
                val data = JSONObject(response.peekBody(MAX_BODY_BYTES).string())
                if (data.optString("error_title").isNotEmpty() || data.optString("backend_error_code").isNotEmpty()) {
                    log("VM list: error reported by the API")
                    return VmLookup(emptyList(), status)
                }
                val list = data.optJSONArray("vm_list") ?: return VmLookup(emptyList(), status)
                val vms = (0 until list.length()).mapNotNull { index ->
                    val entry = list.optJSONObject(index) ?: return@mapNotNull null
                    val url = entry.optString("vm_ws_url").ifEmpty { entry.optString("vm_url") }
                    val token = entry.optString("vm_auth_token")
                    if (url.isEmpty() || token.isEmpty()) return@mapNotNull null
                    Vm(url, token, entry.optString("vm_name"), entry.optString("vm_id"), entry.optBoolean("default"))
                }
                log("VM list: ${vms.size}")
                return VmLookup(vms, status)
            }
        } catch (e: IOException) {
            log("VM list unreachable: ${e.javaClass.simpleName}")
        } catch (e: JSONException) {
            log("VM list: unreadable answer")
        } catch (e: IllegalArgumentException) {
            // The exception's message could contain the token: do not log it.
            log("VM list: malformed token")
        }
        return VmLookup(emptyList(), status)
    }

    /**
     * Rotates the token pair with the refresh token. The access token is never presented: the server could
     * answer 200 with unusable tokens.
     */
    fun refresh(
        refreshToken: String, deviceId: String, apiUrlV2: String, sdkToken: String?, log: (String) -> Unit = {},
    ): TokenRefresh {
        var status: Int? = null
        try {
            // Some tokens arrive already prefixed: doubling it gets them refused.
            val raw = refreshToken.substringAfterLast(':')
            val body = JSONObject().put("device_id", deviceId)
            if (!sdkToken.isNullOrEmpty()) body.put("sdk_token", sdkToken)
            val request = Request.Builder()
                .url(apiRoot(apiUrlV2) + "/device_token/refresh")
                .header("Authorization", "Bearer hatch_refresh:$raw")
                .header("User-Agent", userAgent)
                .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                status = response.code
                if (!response.isSuccessful) {
                    log("refresh refused: HTTP ${response.code}")
                    return TokenRefresh(null, status)
                }
                var data = JSONObject(response.peekBody(MAX_BODY_BYTES).string())
                data = data.optJSONObject("payload") ?: data
                val access = data.optString("access_token")
                val refresh = data.optString("refresh_token")
                if (access.isNotEmpty() && refresh.isNotEmpty()) return TokenRefresh(DeviceTokens(access, refresh), status)
                log("refresh answer without tokens")
            }
        } catch (e: IOException) {
            log("refresh unreachable: ${e.javaClass.simpleName}")
        } catch (e: JSONException) {
            log("refresh: unreadable answer")
        } catch (e: IllegalArgumentException) {
            log("refresh: malformed token")
        }
        return TokenRefresh(null, status)
    }

    /** Request without a token: any HTTP status proves that the network and TLS work. */
    fun probe(): String = try {
        http.newCall(Request.Builder().url("$API_BASE/").header("User-Agent", userAgent).head().build())
            .execute().use { tr("reachable (HTTP ${it.code})", "joignable (HTTP ${it.code})") }
    } catch (e: IOException) {
        tr("unreachable (${e.javaClass.simpleName})", "injoignable (${e.javaClass.simpleName})")
    }

    companion object {
        const val API_BASE = "https://api.muse.ai"
        private const val TIMEOUT_S = 15L
        private const val MAX_BODY_BYTES = 1L shl 20

        /** `api_url_v2` if it is allowed, otherwise the Muse API. `api_url` is ignored. */
        fun apiRoot(apiUrlV2: String): String =
            apiUrlV2.takeIf { it.isNotEmpty() && ServerHosts.apiAllowed(it) }?.trimEnd('/') ?: API_BASE
    }
}
