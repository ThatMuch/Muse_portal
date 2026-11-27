// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/noise/noise_xx.py
package com.portalmuse.core

import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256"
private const val DH_BYTES = 32
private const val TAG_BYTES = 16
private const val MIN_MSG2_BYTES = DH_BYTES + (DH_BYTES + TAG_BYTES) + TAG_BYTES
private const val MAX_NONCE = (1L shl 53) - 1

/** La poignée de main ou le transport Noise a été violé. */
class NoiseError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class X25519KeyPair private constructor(internal val privateKey: ByteArray, val publicKey: ByteArray) {
    companion object {
        fun fromPrivate(privateKey: ByteArray): X25519KeyPair {
            require(privateKey.size == DH_BYTES) { "clé X25519 invalide" }
            val publicKey = ByteArray(DH_BYTES)
            BcX25519.generatePublicKey(privateKey, 0, publicKey, 0)
            return X25519KeyPair(privateKey.copyOf(), publicKey)
        }

        fun generate(): X25519KeyPair {
            val key = ByteArray(DH_BYTES)
            BcX25519.generatePrivateKey(SecureRandom(), key)
            return fromPrivate(key)
        }
    }
}

object X25519 {
    /** `calculateAgreement` renvoie false pour un résultat nul : les points d'ordre faible échouent. */
    fun dh(own: X25519KeyPair, peer: ByteArray): ByteArray {
        if (peer.size != DH_BYTES) throw NoiseError("x25519 : longueur de clé publique invalide")
        val shared = ByteArray(DH_BYTES)
        if (!BcX25519.calculateAgreement(own.privateKey, 0, peer, 0, shared, 0)) {
            throw NoiseError("x25519 : clé publique d'ordre faible refusée")
        }
        return shared
    }
}

/** Clé AES-GCM et nonce croissant. Tout échec l'empoisonne. */
class CipherState(key: ByteArray) {
    private val key = key.copyOf()
    private var nonce = 0L
    private var poisoned = false

    init {
        if (key.size != 32) throw NoiseError("la clé AES-GCM doit faire 32 octets")
    }

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray = apply(true, ad, plaintext)

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray = apply(false, ad, ciphertext)

    private fun apply(encrypt: Boolean, ad: ByteArray, input: ByteArray): ByteArray {
        if (poisoned) throw NoiseError("CipherState inutilisable après un échec")
        if (nonce >= MAX_NONCE) {
            poisoned = true
            throw NoiseError("nonce épuisé")
        }
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (56 - 8 * i)).toByte()
        nonce++
        return try {
            Primitives.aesGcm(encrypt, key, iv, ad, input)
        } catch (e: GeneralSecurityException) {
            poisoned = true
            throw NoiseError(if (encrypt) "chiffrement impossible" else "déchiffrement impossible", e)
        }
    }
}

/** SymmetricState de la spécification Noise, section 5.2. */
internal class SymmetricState(prologue: ByteArray) {
    private var chainingKey: ByteArray = PROTOCOL_NAME.toByteArray().copyOf(32)
    private var hash: ByteArray = chainingKey.copyOf()
    private var cipher: CipherState? = null

    init {
        mixHash(prologue)
    }

    fun mixHash(data: ByteArray) {
        hash = Primitives.sha256(hash, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (next, temp) = hkdf2(chainingKey, inputKeyMaterial)
        chainingKey = next
        cipher = CipherState(temp)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val out = cipher?.encryptWithAd(hash, plaintext) ?: plaintext
        mixHash(out)
        return out
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val out = cipher?.decryptWithAd(hash, ciphertext) ?: ciphertext
        mixHash(ciphertext)
        return out
    }

    /** (initiateur vers répondeur, répondeur vers initiateur). */
    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = hkdf2(chainingKey, ByteArray(0))
        return CipherState(first) to CipherState(second)
    }

    fun handshakeHash(): ByteArray = hash.copyOf()

    private fun hkdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(key, input)
        val first = hmac(temp, byteArrayOf(1))
        return first to hmac(temp, first + byteArrayOf(2))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** Initiateur Noise XX. Une instance sert une seule poignée de main. */
class NoiseXXInitiator(
    prologue: ByteArray = ByteArray(0),
    private val generateEphemeral: () -> X25519KeyPair = X25519KeyPair::generate,
    private val generateStatic: () -> X25519KeyPair = X25519KeyPair::generate,
) {
    private enum class Phase { INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

    private val state = SymmetricState(prologue)
    private var phase = Phase.INITIALIZED
    private var ephemeral: X25519KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStatic: ByteArray? = null

    /** `-> e` */
    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray = step(Phase.INITIALIZED, Phase.MSG1_SENT) {
        val key = generateEphemeral().also { ephemeral = it }
        state.mixHash(key.publicKey)
        key.publicKey + state.encryptAndHash(payload)
    }

    /** `<- e, ee, s, es` ; renvoie la charge utile du répondeur. */
    fun readMessage2(message: ByteArray): ByteArray = step(Phase.MSG1_SENT, Phase.MSG2_READ) {
        if (message.size < MIN_MSG2_BYTES) throw NoiseError("message 2 trop court (${message.size})")
        val own = ephemeral ?: throw NoiseError("clé éphémère absente")
        val re = message.copyOfRange(0, DH_BYTES).also { remoteEphemeral = it }
        state.mixHash(re)
        state.mixKey(X25519.dh(own, re))
        val staticEnd = DH_BYTES + DH_BYTES + TAG_BYTES
        val rs = state.decryptAndHash(message.copyOfRange(DH_BYTES, staticEnd)).also { remoteStatic = it }
        state.mixKey(X25519.dh(own, rs))
        state.decryptAndHash(message.copyOfRange(staticEnd, message.size))
    }

    /** `-> s, se` */
    fun writeMessage3(payload: ByteArray = ByteArray(0)): ByteArray = step(Phase.MSG2_READ, Phase.MSG3_SENT) {
        val own = generateStatic()
        val encryptedStatic = state.encryptAndHash(own.publicKey)
        state.mixKey(X25519.dh(own, remoteEphemeral ?: throw NoiseError("clé distante absente")))
        encryptedStatic + state.encryptAndHash(payload)
    }

    /** (chiffrement d'envoi, déchiffrement de réception). */
    fun split(): Pair<CipherState, CipherState> = step(Phase.MSG3_SENT, Phase.SPLIT) {
        ephemeral = null
        state.split()
    }

    fun remoteStaticKey(): ByteArray? = remoteStatic?.copyOf()

    fun handshakeHash(): ByteArray = state.handshakeHash()

    private fun <T> step(expected: Phase, next: Phase, block: () -> T): T {
        if (phase != expected) throw NoiseError("appel hors séquence (phase $phase, attendu $expected)")
        phase = Phase.DEAD
        return block().also { phase = next }
    }
}
