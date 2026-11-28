package com.portalmuse.gadget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.portalmuse.core.PairingRecord
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "PortalMuse"
private const val KEYSTORE = "AndroidKeyStore"
private const val ALIAS = "portalmuse_secrets"

/**
 * Secrets encrypted with AES-GCM by a non-exportable Android Keystore key. Only the ciphertext is written to
 * private storage. No Google service needed.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    var sdkToken: String?
        get() = read("sdk_token")
        set(value) {
            if (value == null) delete("sdk_token") else write("sdk_token", value)
        }

    val pairing: PairingRecord?
        get() = read("pairing")?.let(PairingRecord::fromJson)

    fun savePairing(record: PairingRecord): Boolean = write("pairing", record.toJson())

    fun deletePairing() = delete("pairing")

    /** Replaces or erases the pairing only if it has not changed in the meantime. */
    fun replacePairing(expected: PairingRecord, updated: PairingRecord?): Boolean = synchronized(LOCK) {
        if (pairing?.refreshToken != expected.refreshToken) false
        else if (updated == null) delete("pairing")
        else savePairing(updated)
    }

    /** A round trip on a meaningless value, for the status screen. */
    fun selfCheck(): Boolean = write("probe", "ok") && read("probe") == "ok" && delete("probe")

    private fun write(name: String, value: String): Boolean = synchronized(LOCK) { seal(name, value) }

    private fun seal(name: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val sealed = encode(cipher.iv) + ":" + encode(cipher.doFinal(value.toByteArray()))
        prefs.edit().putString(name, sealed).commit()
    } catch (e: GeneralSecurityException) {
        Log.e(TAG, "encryption failed: ${e.javaClass.simpleName}")
        false
    }

    private fun read(name: String): String? = synchronized(LOCK) { open(name) }

    private fun open(name: String): String? {
        val parts = prefs.getString(name, null)?.split(":") ?: return null
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD(name.toByteArray())
            String(cipher.doFinal(decode(parts[1])))
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "decryption failed: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun delete(name: String): Boolean = synchronized(LOCK) { prefs.edit().remove(name).commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        /** Shared by all instances: the service and the screen write the same file. */
        val LOCK = Any()
    }

    private fun encode(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
