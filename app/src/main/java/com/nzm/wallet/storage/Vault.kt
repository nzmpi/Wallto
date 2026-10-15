package com.nzm.wallet.storage

import android.content.Context
import androidx.fragment.app.FragmentActivity
import java.io.File
import org.web3j.crypto.Credentials
import com.nzm.wallet.crypto.WalletCore

enum class SecretType(val tag: Byte) {
    MNEMONIC(1), PRIVATE_KEY(2);
    companion object { fun of(tag: Byte) = entries.first { it.tag == tag } }
}

class Vault(context: Context) {
    // noBackupFilesDir: excluded from cloud backup and device transfer
    private val file = File(context.noBackupFilesDir, "wallet.bin")

    fun exists() = file.exists()

    /** One biometric prompt. Credentials live only inside block. Secret bytes wiped after. */
    suspend fun <T> withCredentials(activity: FragmentActivity, block: suspend (Credentials) -> T): T {
        val (type, bytes) = load(activity)
        try {
            val creds = when (type) {
                SecretType.MNEMONIC -> WalletCore.deriveCredentials(String(bytes))
                SecretType.PRIVATE_KEY -> WalletCore.credentialsFromPrivateKey(String(bytes))
            }
            return block(creds)
        } finally {
            bytes.fill(0)
        }
    }

    /** secret = UTF-8 bytes of mnemonic, or hex private key. Caller wipes it after. */
    suspend fun save(activity: FragmentActivity, type: SecretType, secret: ByteArray) {
        val cipher = BiometricGate.authenticate(activity, KeystoreVault.encryptCipher(), "Save wallet")
        cipher.updateAAD(byteArrayOf(type.tag))
        val ct = cipher.doFinal(secret)
        val iv = cipher.iv
        // layout: [version=1][type][ivLen][iv][ciphertext]
        file.writeBytes(byteArrayOf(1, type.tag, iv.size.toByte()) + iv + ct)
    }

    /** Caller wipes the returned ByteArray after use. */
    suspend fun load(activity: FragmentActivity): Pair<SecretType, ByteArray> {
        val blob = file.readBytes()
        require(blob[0] == 1.toByte()) { "Unknown vault version" }
        val type = SecretType.of(blob[1])
        val ivLen = blob[2].toInt()
        val iv = blob.copyOfRange(3, 3 + ivLen)
        val ct = blob.copyOfRange(3 + ivLen, blob.size)
        val cipher = BiometricGate.authenticate(activity, KeystoreVault.decryptCipher(iv), "Unlock wallet")
        cipher.updateAAD(byteArrayOf(type.tag))
        return type to cipher.doFinal(ct)
    }

    fun delete() {
        file.delete()
        KeystoreVault.deleteKey()
    }
}