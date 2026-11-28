// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/link_client.py (noise_url)
package com.portalmuse.core

import java.net.URLEncoder

object NoiseUrl {
    /** The voice protocol identity that the SDK client announces by default. */
    const val VOICE_APP_ID = "hatch-web"

    /** The Noise host is never anything but the one from the Muse app. */
    fun of(pairing: PairingRecord, vm: Vm, appId: String? = null): String {
        val host = pairing.noiseHost.takeIf { it.isNotEmpty() && ServerHosts.noiseAllowed(it) } ?: ServerHosts.NOISE_HOST
        val vmId = URLEncoder.encode(vm.id.ifEmpty { vm.name }, "UTF-8").replace("+", "%20")
        return "wss://$host/v1/noise?vm_id=$vmId" + (appId?.let { "&app_id=$it" } ?: "")
    }
}
