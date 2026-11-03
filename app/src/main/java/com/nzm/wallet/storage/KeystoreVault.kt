package com.nzm.wallet.storage

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.StrongBoxUnavailableException
import com.nzm.wallet.utils.Constants.PROVIDER
import com.nzm.wallet.utils.Constants.TRANSFORM
import com.nzm.wallet.utils.Helpers.generateSecretKey
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyLostException : Exception("Wallet key missing")

object KeystoreVault {
    private const val ALIAS = "wallet_master_key"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generateSecretKey(ALIAS, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generateSecretKey(ALIAS, strongBox = false)
        }
    }

    private fun existingKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = try {
            ks.getKey(ALIAS, null)
        } catch (_: UnrecoverableKeyException) {
            null
        }
        return key as? SecretKey ?: throw KeyLostException()
    }

    fun isKeyGone(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is KeyLostException || it is KeyPermanentlyInvalidatedException }

    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
        }

    fun deleteKey() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
    }
}