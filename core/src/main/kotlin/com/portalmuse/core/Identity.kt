// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Sources:
// linux/src/musegadget/identity.py, server_hosts.py, ble_setup.py
package com.portalmuse.core

import java.security.SecureRandom

private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

/**
 * Persistent identity: a random value shaped like a MAC address, locally administered, created once. It is not
 * read from any network interface.
 */
data class Identity(val mac: String) {
    init {
        require(MAC.matches(mac)) { "invalid identity" }
    }

    val suffix: String get() = mac.replace(":", "").takeLast(6)

    /** Like the default Linux client. The identifier stored at pairing is never renamed. */
    val nodeId: String get() = "homelink-$suffix"
    val deviceId: String get() = "hatch-link:$mac"

    /** Without separator: the app compares the rest of the name with the end of the identifier. */
    val bleName: String get() = "MuseGadget" + suffix.uppercase()

    companion object {
        fun generate(
            randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
        ): Identity {
            val octets = randomBytes(6)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, local
            return Identity(octets.joinToString(":") { "%02x".format(it) })
        }
    }
}
