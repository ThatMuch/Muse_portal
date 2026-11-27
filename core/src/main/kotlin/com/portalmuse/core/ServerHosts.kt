// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/server_hosts.py
package com.portalmuse.core

/**
 * Les seuls serveurs auxquels l'appareil envoie ses jetons : ceux que l'app Muse transmet,
 * à l'identique. Rien n'est configurable. Une valeur vide désigne la valeur par défaut.
 */
object ServerHosts {
    val API_URLS = listOf("https://api.muse.ai", "https://hatch-api.meta.ai")
    const val NOISE_HOST = "hatch.metaaivm.com"

    fun apiAllowed(url: String): Boolean = url.isEmpty() || url in API_URLS
    fun noiseAllowed(host: String): Boolean = host.isEmpty() || host == NOISE_HOST
}
