// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/link_client.py (noise_url)
package com.portalmuse.core

import java.net.URLEncoder

object NoiseUrl {
    /** L'identité du protocole vocal que le client du SDK annonce par défaut. */
    const val VOICE_APP_ID = "hatch-web"

    /** L'hôte Noise n'est jamais autre que celui de l'app Muse. */
    fun of(pairing: PairingRecord, vm: Vm, appId: String? = null): String {
        val host = pairing.noiseHost.takeIf { it.isNotEmpty() && ServerHosts.noiseAllowed(it) } ?: ServerHosts.NOISE_HOST
        val vmId = URLEncoder.encode(vm.id.ifEmpty { vm.name }, "UTF-8").replace("+", "%20")
        return "wss://$host/v1/noise?vm_id=$vmId" + (appId?.let { "&app_id=$it" } ?: "")
    }
}
