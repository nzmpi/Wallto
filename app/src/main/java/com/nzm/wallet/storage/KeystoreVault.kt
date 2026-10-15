package com.nzm.wallet.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException

class KeyLostException : Exception("Wallet key missing")

object KeystoreVault {
    private const val ALIAS = "wallet_master_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
            .setBlockModes(BLOCK_MODE_GCM)
            .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun existingKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = try { ks.getKey(ALIAS, null) } catch (e: UnrecoverableKeyException) { null }
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