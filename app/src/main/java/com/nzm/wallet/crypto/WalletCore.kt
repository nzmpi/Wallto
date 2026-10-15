package com.nzm.wallet.crypto

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom
import org.web3j.crypto.Sign
import java.math.BigInteger

object WalletCore {
    private const val HARDENED = Bip32ECKeyPair.HARDENED_BIT

    /** 12 words = 128 bits of entropy, 24 words = 256 bits. */
    fun generateMnemonic(words: Int = 12): String {
        val entropyBytes = when (words) {
            12 -> 16
            24 -> 32
            else -> throw IllegalArgumentException("Use 12 or 24 words")
        }
        val entropy = ByteArray(entropyBytes)
        SecureRandom().nextBytes(entropy)
        try {
            return MnemonicUtils.generateMnemonic(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    fun normalize(mnemonic: String): String =
        mnemonic.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

    /** Checks the word list and the checksum. Use this on import. */
    fun isValidMnemonic(mnemonic: String): Boolean =
        runCatching { MnemonicUtils.validateMnemonic(normalize(mnemonic)) }.getOrDefault(false)

    /** BIP-44 for Ethereum: m/44'/60'/0'/0/index */
    fun deriveCredentials(mnemonic: String, index: Int = 0, passphrase: String = ""): Credentials {
        require(isValidMnemonic(mnemonic)) { "Invalid mnemonic" }
        val seed = MnemonicUtils.generateSeed(normalize(mnemonic), passphrase)
        try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            val path = intArrayOf(44 or HARDENED, 60 or HARDENED, 0 or HARDENED, 0, index)
            return Credentials.create(Bip32ECKeyPair.deriveKeyPair(master, path))
        } finally {
            seed.fill(0)
        }
    }

    /** EIP-55 checksum address, e.g. 0xf39F... */
    fun addressOf(credentials: Credentials): String =
        Keys.toChecksumAddress(credentials.address)

    /** Returns 64 lowercase hex chars, or null if the input is not a valid secp256k1 key. */
    fun normalizePrivateKey(input: String): String? {
        val s = input.trim().removePrefix("0x").removePrefix("0X")
        if (!Regex("^[0-9a-fA-F]{64}$").matches(s)) return null
        val k = BigInteger(s, 16)
        if (k.signum() == 0 || k >= Sign.CURVE_PARAMS.n) return null
        return s.lowercase()
    }

    fun credentialsFromPrivateKey(input: String): Credentials {
        val hex = normalizePrivateKey(input) ?: throw IllegalArgumentException("Invalid private key")
        return Credentials.create(hex)
    }
}