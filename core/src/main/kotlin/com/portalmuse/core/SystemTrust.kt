// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/tls.py
package com.portalmuse.core

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The system trust store, minus the roots the SDK distrusts. The Linux client refuses to connect while the
 * system holds them; since the Portal no longer gets updates, they are removed here from the app's trust
 * anchors.
 */
object SystemTrust {
    /** (SHA-256 of the certificate, SHA-256 of the public key). */
    private val DISTRUSTED = listOf(
        // emSign ECC Root CA - G3 (compromised)
        "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b" to
            "8d417db2dd8bf5e3084d1e3f196d583849d81bdd4c00c70b9d39369e96b8c782",
        // Comodo AAA Services root (compromised)
        "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4" to
            "bd153ed7b0434f6886b17bce8bbe84ed340c7132d702a8f4fa318f756ecbd6f3",
        // emSign Root CA - C1 (withdrawn)
        "125609aa301da0a249b97a8239cb6a34216f44dcac9f3954b14292f2e8c8608f" to
            "b7408b4d2be0238ba37004dd34e276c6019bd2f24c9db7d4980f5f6c359a4bcc",
        // emSign ECC Root CA - C3 (withdrawn)
        "bc4d809b15189d78db3e1d8cf4f9726a795da1643ca5f1358e1ddb0edc0d7eb3" to
            "eabc185c4e82d942b1a5978ba3c0181487d6b3b9974e5c49f72f6d0bd9637150",
    )

    /** The public key also catches any reissue of the same root. */
    fun isDistrusted(certificate: X509Certificate): Boolean {
        val whole = sha256(certificate.encoded)
        val key = sha256(certificate.publicKey.encoded)
        return DISTRUSTED.any { it.first == whole || it.second == key }
    }

    /** [accept] picks the aliases of [source] to consider. Fails if nothing is left. */
    fun trustManager(source: KeyStore, accept: (String) -> Boolean = { true }): X509TrustManager {
        val filtered = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        for (alias in source.aliases()) {
            val certificate = source.getCertificate(alias) as? X509Certificate ?: continue
            if (accept(alias) && !isDistrusted(certificate)) filtered.setCertificateEntry(alias, certificate)
        }
        check(filtered.size() > 0) { "system trust store empty or unreadable" }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(filtered)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
