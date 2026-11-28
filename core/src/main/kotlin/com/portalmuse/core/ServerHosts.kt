// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/server_hosts.py
package com.portalmuse.core

/**
 * The only servers the device sends its tokens to: those the Muse app passes, as is. Nothing is configurable.
 * An empty value means the default.
 */
object ServerHosts {
    val API_URLS = listOf("https://api.muse.ai", "https://hatch-api.meta.ai")
    const val NOISE_HOST = "hatch.metaaivm.com"

    fun apiAllowed(url: String): Boolean = url.isEmpty() || url in API_URLS
    fun noiseAllowed(host: String): Boolean = host.isEmpty() || host == NOISE_HOST
}
