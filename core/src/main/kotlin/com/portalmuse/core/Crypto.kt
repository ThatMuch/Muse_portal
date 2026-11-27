// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
package com.portalmuse.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives partagées : celles de la plateforme (JCA), HKDF de BouncyCastle. */
internal object Primitives {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    /** HKDF-SHA256 (RFC 5869), extraction puis expansion. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        generate(HKDFParameters(ikm, salt, info), length)

    /** Expansion seule, à partir d'une clé pseudo-aléatoire. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray =
        generate(HKDFParameters.skipExtractParameters(prk, info), length)

    private fun generate(parameters: HKDFParameters, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(parameters)
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    /** AES-256-GCM, tag de 16 octets ajouté à la fin du chiffré. */
    fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }
}

/** Paire P-256 ; [publicPoint] est le point non compressé de 65 octets. */
class P256KeyPair(val privateKey: PrivateKey, val publicPoint: ByteArray)

object P256 {
    private const val POINT_BYTES = 65

    private val parameters: ECParameterSpec by lazy {
        val holder = AlgorithmParameters.getInstance("EC")
        holder.init(ECGenParameterSpec("secp256r1"))
        holder.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generate(): P256KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        return P256KeyPair(pair.private, byteArrayOf(4) + fixed(point.affineX) + fixed(point.affineY))
    }

    /** Pour les tests sur vecteurs. */
    fun privateKeyFromScalar(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, parameters))

    /** Décode un point du téléphone et refuse tout point hors de la courbe. */
    fun decodePoint(bytes: ByteArray): ECPublicKey {
        require(bytes.size == POINT_BYTES && bytes[0] == 4.toByte()) { "point P-256 invalide" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, POINT_BYTES))
        val curve = parameters.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p) { "coordonnée hors du corps" }
        val right = x.multiply(x).add(curve.a).multiply(x).add(curve.b).mod(p)
        require(y.multiply(y).mod(p) == right) { "point hors de la courbe" }
        val spec = ECPublicKeySpec(ECPoint(x, y), parameters)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** Secret partagé : la coordonnée x, 32 octets. */
    fun ecdh(privateKey: PrivateKey, peerPoint: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePoint(peerPoint), true)
        return agreement.generateSecret()
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return ByteArray(32 - trimmed.size) + trimmed
    }
}
