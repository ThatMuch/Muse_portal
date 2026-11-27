package com.portalmuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class SecurityTest {
    private fun published(): List<X509Certificate> {
        val stream = javaClass.getResourceAsStream("/distrusted_roots.pem")!!
        val pem = stream.bufferedReader().readText()
        val factory = CertificateFactory.getInstance("X.509")
        return Regex("-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
            .findAll(pem)
            .map { factory.generateCertificate(it.value.byteInputStream()) as X509Certificate }
            .toList()
    }

    private fun systemRoot(): X509Certificate {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        return manager.acceptedIssuers.first { !SystemTrust.isDistrusted(it) }
    }

    private fun store(certificates: List<X509Certificate>) = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        certificates.forEachIndexed { i, c -> setCertificateEntry("system:$i", c) }
    }

    @Test fun everyPublishedRootIsDistrusted() {
        assertEquals(4, published().size)
        assertTrue(published().all(SystemTrust::isDistrusted))
        assertFalse(SystemTrust.isDistrusted(systemRoot()))
    }

    @Test fun trustManagerDropsDistrustedRootsOnly() {
        val good = systemRoot()
        val manager = SystemTrust.trustManager(store(published() + good))
        assertEquals(listOf(good), manager.acceptedIssuers.toList())
    }

    @Test fun anEmptyStoreIsRefused() {
        assertThrows(IllegalStateException::class.java) { SystemTrust.trustManager(store(published())) }
    }

    @Test fun sdkTokenFormat() {
        val valid = "mgst_" + "a".repeat(42) + "A"
        assertTrue(SdkToken.isValid(valid))
        for (bad in listOf("", "mgst_", valid.dropLast(1), valid + "A", "mgst_" + "a".repeat(42) + "B", valid.replace("mgst", "mgsk"), " $valid")) {
            assertFalse(bad, SdkToken.isValid(bad))
        }
        val shown = SdkToken.fingerprint(valid)
        assertTrue(shown, shown.startsWith("48 caractères, sha256 "))
        assertTrue("aaaa" !in shown)
        assertEquals("absent", SdkToken.fingerprint(null))
    }
}
