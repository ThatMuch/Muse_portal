// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Sources : linux/src/musegadget/identity.py, server_hosts.py, ble_setup.py
package com.portalmuse.core

import java.security.SecureRandom

private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

/**
 * Identité persistante : une valeur aléatoire en forme d'adresse MAC, administrée localement,
 * créée une fois. Elle n'est lue sur aucune interface réseau.
 */
data class Identity(val mac: String) {
    init {
        require(MAC.matches(mac)) { "identité invalide" }
    }

    val suffix: String get() = mac.replace(":", "").takeLast(6)

    /** Comme le client Linux par défaut. L'identifiant stocké à l'appairage ne se renomme jamais. */
    val nodeId: String get() = "homelink-$suffix"
    val deviceId: String get() = "hatch-link:$mac"

    /** Sans séparateur : l'app compare la suite du nom avec la fin de l'identifiant. */
    val bleName: String get() = "MuseGadget" + suffix.uppercase()

    companion object {
        fun generate(
            randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
        ): Identity {
            val octets = randomBytes(6)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locale
            return Identity(octets.joinToString(":") { "%02x".format(it) })
        }
    }
}
