// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/rtc/join.py
package com.portalmuse.core.rtc

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

private const val AUDIO_TRACK_ASSISTANT = 1
private const val VIDEO_TRACK_ASSISTANT = 2
private const val DTLS_ACTIVE = 1
private const val MAX_RESPONSE_BYTES = 16 * 1024
private const val ICE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
private const val DAY_MS = 86_400_000L

/** The media access type announced by the VM, as a numeric JOIN code. */
val ENDPOINT_TYPES = mapOf("META_AI" to 2, "HATCH" to 8)

/** A DTLS identity: mandatory JOIN fields, unused since the SRTP key travels there. */
class DtlsIdentity(val keyPem: String, val certificatePem: String, val fingerprint: String)

/** Everything the client draws at random for a call. */
class JoinSecrets(
    val clientUfrag: String, val serverUfrag: String, val clientPwd: String, val serverPwd: String,
    val srtpKey: ByteArray, val clientFingerprint: String, val serverKeyPem: String, val serverCertPem: String,
    val clientSessionId: String, val transactionId: Int,
)

class JoinOffer(val payload: ByteArray, val secrets: JoinSecrets)

class JoinAnswer(val serverUfrag: String, val ipv4: String, val ipv6: String)

object Join {
    private val random = SecureRandom()

    private fun token(length: Int) = String(CharArray(length) { ICE_CHARS[random.nextInt(ICE_CHARS.length)] })

    private fun pem(label: String, der: ByteArray) =
        "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $label-----\n"

    /** A P-256 key and a self-signed certificate valid for 30 days. */
    fun identity(): DtlsIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=musegadget")
        val now = System.currentTimeMillis()
        val unsigned = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(120, random).add(BigInteger.ONE)))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - DAY_MS)))
            setEndDate(Time(Date(now + 30 * DAY_MS)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(unsigned.getEncoded("DER"))
            sign()
        }
        val certificate = DERSequence(
            ASN1EncodableVector().apply {
                add(unsigned)
                add(algorithm)
                add(DERBitString(signature))
            },
        ).getEncoded("DER")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate)
        return DtlsIdentity(
            pem("PRIVATE KEY", pair.private.encoded), pem("CERTIFICATE", certificate),
            "sha-256 " + digest.joinToString(":") { "%02X".format(it) },
        )
    }

    fun newSecrets(): JoinSecrets {
        val client = identity()
        val server = identity()
        return JoinSecrets(
            token(4), token(16), token(24), token(24),
            ByteArray(30).also(random::nextBytes),
            client.fingerprint, server.keyPem, server.certificatePem,
            (1_000_000_000L + (random.nextLong() ushr 1) % 9_000_000_000L).toString(),
            random.nextInt(Int.MAX_VALUE - 1) + 1,
        )
    }

    /** The session parameters of the call: usage, recipient, entry point and conversation. */
    private fun sessionParams(threadId: String?): ByteArray = ThriftWriter().apply {
        i32(1, 0)
        binary(2, "")
        binary(3, "")
        if (!threadId.isNullOrEmpty()) binary(4, threadId)
        stop()
    }.bytes()

    /** A "media only" request with Muse's voice and video. */
    fun build(edgeHost: String, edgePort: Int, vipType: Int, threadId: String? = null, secrets: JoinSecrets = newSecrets()): JoinOffer {
        val writer = ThriftWriter()
        writer.binary(1, secrets.clientSessionId)
        writer.i32(3, secrets.transactionId)
        writer.stop()
        writer.begin(1) // body.joinRequest
        writer.binary(1, secrets.clientUfrag)
        writer.binary(2, secrets.serverUfrag)
        writer.begin(3)
        writer.binary(1, Base64.getEncoder().encode(secrets.srtpKey))
        writer.binary(2, secrets.clientPwd)
        writer.binary(3, secrets.serverPwd)
        writer.binary(4, secrets.serverKeyPem)
        writer.end()
        writer.binary(4, secrets.clientFingerprint)
        writer.binary(5, secrets.serverCertPem)
        writer.binary(6, "$edgeHost:$edgePort")
        writer.i32(8, DTLS_ACTIVE)
        writer.i32(11, AUDIO_TRACK_ASSISTANT)
        writer.i32(12, VIDEO_TRACK_ASSISTANT)
        writer.i32(13, vipType)
        writer.boolean(14, false)
        writer.binary(16, sessionParams(threadId))
        writer.end()
        writer.stop()
        return JoinOffer(writer.bytes(), secrets)
    }

    fun parseResponse(payload: ByteArray): JoinAnswer {
        require(payload.size <= MAX_RESPONSE_BYTES) { "JOIN response too large" }
        val reader = ThriftReader(payload)
        val header = reader.struct()
        val answer = reader.struct()[2] as? Map<*, *> ?: throw IllegalArgumentException("JOIN response without joinResponse")
        fun text(id: Int): String = when (val value = answer[id]) {
            null -> ""
            is ByteArray -> String(value)
            else -> throw IllegalArgumentException("malformed joinResponse")
        }
        require(text(1).isNotEmpty()) { "JOIN response without server identifier" }
        val status = header[4] as? Long
        require(status == null || status == 200L) { "JOIN refused ($status)" }
        return JoinAnswer(text(1), text(2), text(3))
    }
}
