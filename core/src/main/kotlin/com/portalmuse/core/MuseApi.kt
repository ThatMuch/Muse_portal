// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/muse_api.py
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

/** [status] vaut `null` si aucune réponse HTTP n'est arrivée (à réessayer). */
class VmLookup(val vms: List<Vm>, val status: Int?)

class DeviceTokens(val accessToken: String, val refreshToken: String) {
    override fun toString() = "DeviceTokens(<masqué>)"
}

/** [status] 401 : l'appairage n'existe plus. `null` : panne de transport. */
class TokenRefresh(val tokens: DeviceTokens?, val status: Int?)

/** Client de l'API appareil de Muse. [client] porte le magasin de confiance filtré. */
class MuseApi(client: OkHttpClient, private val userAgent: String) {
    // Aucune redirection : les jetons ne vont qu'aux hôtes autorisés.
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
                    log("liste des VM refusée : HTTP ${response.code}")
                    return VmLookup(emptyList(), status)
                }
                val data = JSONObject(response.peekBody(MAX_BODY_BYTES).string())
                if (data.optString("error_title").isNotEmpty() || data.optString("backend_error_code").isNotEmpty()) {
                    log("liste des VM : erreur signalée par l'API")
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
                log("liste des VM : ${vms.size}")
                return VmLookup(vms, status)
            }
        } catch (e: IOException) {
            log("liste des VM injoignable : ${e.javaClass.simpleName}")
        } catch (e: JSONException) {
            log("liste des VM : réponse illisible")
        } catch (e: IllegalArgumentException) {
            // Le message de l'exception pourrait contenir le jeton : ne pas le journaliser.
            log("liste des VM : jeton mal formé")
        }
        return VmLookup(emptyList(), status)
    }

    /**
     * Rotation de la paire de jetons avec le jeton de rafraîchissement. Le jeton d'accès n'est
     * jamais présenté : le serveur pourrait répondre 200 avec des jetons inutilisables.
     */
    fun refresh(
        refreshToken: String, deviceId: String, apiUrlV2: String, sdkToken: String?, log: (String) -> Unit = {},
    ): TokenRefresh {
        var status: Int? = null
        try {
            // Certains jetons arrivent déjà préfixés : le doubler les fait refuser.
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
                    log("renouvellement refusé : HTTP ${response.code}")
                    return TokenRefresh(null, status)
                }
                var data = JSONObject(response.peekBody(MAX_BODY_BYTES).string())
                data = data.optJSONObject("payload") ?: data
                val access = data.optString("access_token")
                val refresh = data.optString("refresh_token")
                if (access.isNotEmpty() && refresh.isNotEmpty()) return TokenRefresh(DeviceTokens(access, refresh), status)
                log("réponse de renouvellement sans jetons")
            }
        } catch (e: IOException) {
            log("renouvellement injoignable : ${e.javaClass.simpleName}")
        } catch (e: JSONException) {
            log("renouvellement : réponse illisible")
        } catch (e: IllegalArgumentException) {
            log("renouvellement : jeton mal formé")
        }
        return TokenRefresh(null, status)
    }

    /** Requête sans jeton : un statut HTTP, quel qu'il soit, prouve que le réseau et TLS passent. */
    fun probe(): String = try {
        http.newCall(Request.Builder().url("$API_BASE/").header("User-Agent", userAgent).head().build())
            .execute().use { "joignable (HTTP ${it.code})" }
    } catch (e: IOException) {
        "injoignable (${e.javaClass.simpleName})"
    }

    companion object {
        const val API_BASE = "https://api.muse.ai"
        private const val TIMEOUT_S = 15L
        private const val MAX_BODY_BYTES = 1L shl 20

        /** `api_url_v2` s'il est autorisé, sinon l'API Muse. `api_url` est ignoré. */
        fun apiRoot(apiUrlV2: String): String =
            apiUrlV2.takeIf { it.isNotEmpty() && ServerHosts.apiAllowed(it) }?.trimEnd('/') ?: API_BASE
    }
}
