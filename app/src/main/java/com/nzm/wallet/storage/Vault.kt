package com.nzm.wallet.storage

import android.content.Context
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.SecureRandom
import org.web3j.crypto.Credentials
import com.nzm.wallet.core.WalletCore
import com.nzm.wallet.utils.Helpers.getPrefs

enum class SecretType(val tag: Byte) {
    MNEMONIC(1), PRIVATE_KEY(2);
    companion object { fun of(tag: Byte) = entries.first { it.tag == tag } }
}

/**
 * One encrypted file per wallet: noBackupFilesDir/wallets/<id>.bin
 * One shared biometric Keystore key (KeystoreVault). Same blob layout as before.
 * Old single-wallet file wallet.bin is moved to wallets/w1.bin on first start.
 */
class Vault(context: Context) {
    // noBackupFilesDir: excluded from cloud backup and device transfer
    private val dir = File(context.noBackupFilesDir, "wallets").apply { mkdirs() }
    private val prefs = context.getPrefs("wallet_prefs")

    init {
        val legacy = File(context.noBackupFilesDir, "wallet.bin")
        val target = File(dir, "$LEGACY_ID.bin")
        if (legacy.exists() && !target.exists()) legacy.renameTo(target)
    }

    private fun fileOf(id: String): File {
        require(id.matches(ID_RE)) { "Bad wallet id" }
        return File(dir, "$id.bin")
    }

    /** All wallet ids that have a secret on disk. */
    fun ids(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.map { it.name.removeSuffix(".bin") }?.filter { it.matches(ID_RE) }?.sorted()
            ?: emptyList()

    fun exists() = ids().isNotEmpty()
    fun exists(id: String) = fileOf(id).exists()

    fun newId(): String {
        val b = ByteArray(4).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Wallet used by every call that has no explicit id. Falls back to the first wallet. */
    var activeId: String?
        get() = prefs.getString(ACTIVE_KEY, null)?.takeIf { it.matches(ID_RE) && fileOf(it).exists() }
            ?: ids().firstOrNull()
        set(v) {
            require(v == null || exists(v)) { "No such wallet" }
            prefs.edit { if (v == null) remove(ACTIVE_KEY) else putString(ACTIVE_KEY, v) }
        }

    private fun requireActive(): String = activeId ?: error("No wallet")

    /** One biometric prompt. Credentials live only inside block. Secret bytes wiped after. */
    suspend fun <T> withCredentials(activity: FragmentActivity, id: String, block: suspend (Credentials) -> T): T {
        val (type, bytes) = load(activity, id)
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

    /** Active wallet. */
    suspend fun <T> withCredentials(activity: FragmentActivity, block: suspend (Credentials) -> T): T =
        withCredentials(activity, requireActive(), block)

    /** secret = UTF-8 bytes of mnemonic, or hex private key. Caller wipes it after. */
    suspend fun save(activity: FragmentActivity, id: String, type: SecretType, secret: ByteArray) {
        val file = fileOf(id)
        val cipher = WalletCore.authenticate(activity, KeystoreVault.encryptCipher(), "Save wallet")
        cipher.updateAAD(byteArrayOf(type.tag))
        val ct = cipher.doFinal(secret)
        val iv = cipher.iv
        // layout: [version=1][type][ivLen][iv][ciphertext]
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(byteArrayOf(1, type.tag, iv.size.toByte()) + iv + ct)
        if (!tmp.renameTo(file)) { tmp.delete(); error("Could not save wallet") }
    }

    /** New wallet: creates id, saves, makes it active, returns id. */
    suspend fun save(activity: FragmentActivity, type: SecretType, secret: ByteArray): String {
        val id = newId()
        save(activity, id, type, secret)
        activeId = id
        return id
    }

    /** Caller wipes the returned ByteArray after use. */
    suspend fun load(activity: FragmentActivity, id: String): Pair<SecretType, ByteArray> {
        val blob = fileOf(id).readBytes()
        require(blob[0] == 1.toByte()) { "Unknown vault version" }
        val type = SecretType.of(blob[1])
        val ivLen = blob[2].toInt()
        val iv = blob.copyOfRange(3, 3 + ivLen)
        val ct = blob.copyOfRange(3 + ivLen, blob.size)
        val cipher = WalletCore.authenticate(activity, KeystoreVault.decryptCipher(iv), "Unlock wallet")
        cipher.updateAAD(byteArrayOf(type.tag))
        return type to cipher.doFinal(ct)
    }

    /** Active wallet. */
    suspend fun load(activity: FragmentActivity): Pair<SecretType, ByteArray> = load(activity, requireActive())

    /** Delete one wallet. Last one gone: Keystore key goes too. Caller must biometric-confirm first. */
    fun delete(id: String) {
        fileOf(id).delete()
        if (activeId == null || prefs.getString(ACTIVE_KEY, null) == id) {
            prefs.edit { remove(ACTIVE_KEY) }
        }
        if (!exists()) KeystoreVault.deleteKey()
    }

    /** Wipe everything: all wallets and the key. */
    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit { remove(ACTIVE_KEY) }
        KeystoreVault.deleteKey()
    }

    private companion object {
        const val LEGACY_ID = "w1"
        const val ACTIVE_KEY = "active_wallet"
        val ID_RE = Regex("[a-z0-9]{2,16}")
    }
}
