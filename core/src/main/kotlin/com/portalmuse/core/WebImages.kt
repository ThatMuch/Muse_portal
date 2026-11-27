package com.portalmuse.core

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val MAX_REDIRECTS = 5

/**
 * Télécharge une image désignée par Muse. Seulement en https, vers des adresses publiques : le
 * Portal est sur le réseau de la maison, et une adresse donnée de l'extérieur ne doit jamais y
 * mener. Chaque redirection est revérifiée.
 */
class WebImages(
    client: OkHttpClient,
    private val userAgent: String,
    private val maxBytes: Long = 12L * 1024 * 1024,
) {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        // La résolution utilisée pour se connecter est celle qui est vérifiée.
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val found = Dns.SYSTEM.lookup(hostname)
                if (found.isEmpty() || !found.all(::isPublic)) throw UnknownHostException("adresse non publique")
                return found
            }
        })
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetch(address: String, log: (String) -> Unit = {}): ByteArray? {
        var url = allowed(address) ?: return refused(log, "adresse refusée")
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val request = Request.Builder().url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/webp,image/jpeg,image/png,image/gif")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val next = response.header("Location")?.let { url.resolve(it) }
                        url = next?.let { allowed(it.toString()) } ?: return refused(log, "redirection refusée")
                        return@use
                    }
                    if (!response.isSuccessful) return refused(log, "HTTP ${response.code}")
                    val body = response.body ?: return refused(log, "réponse vide")
                    val buffer = Buffer()
                    while (body.source().read(buffer, 64 * 1024) != -1L) {
                        if (buffer.size > maxBytes) return refused(log, "image trop grande")
                    }
                    return buffer.readByteArray()
                }
            }
            return refused(log, "trop de redirections")
        } catch (e: IOException) {
            // Le type seulement : le message contient l'adresse.
            return refused(log, e.javaClass.simpleName)
        }
    }

    private fun refused(log: (String) -> Unit, reason: String): ByteArray? {
        log("image du web non chargée : $reason")
        return null
    }

    companion object {
        private val DOTTED_OR_NUMERIC = Regex("[0-9.]+|0[xX][0-9a-fA-F.]+")

        /** L'adresse si elle est acceptable : https, nom de domaine, port normal, sans identifiants. */
        fun allowed(address: String): HttpUrl? = address.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                ':' !in it.host && !DOTTED_OR_NUMERIC.matches(it.host)
        }

        fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val bytes = address.address.map { it.toInt() and 0xFF }
            return when (address) {
                is Inet4Address -> !(
                    bytes[0] == 0 || bytes[0] >= 224 || (bytes[0] == 100 && bytes[1] in 64..127) ||
                        (bytes[0] == 192 && bytes[1] == 0) || (bytes[0] == 198 && bytes[1] in 18..19)
                    )
                // Seulement l'unicast global, sans les plages qui enveloppent une adresse IPv4.
                is Inet6Address -> bytes[0] in 0x20..0x3F &&
                    !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
                    !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] == 0 && bytes[3] == 0 || bytes[2] == 0x0D && bytes[3] == 0xB8))
                else -> false
            }
        }
    }
}
