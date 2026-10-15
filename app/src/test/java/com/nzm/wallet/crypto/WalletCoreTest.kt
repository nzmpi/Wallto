package com.nzm.wallet.crypto

import org.junit.Assert.*
import org.junit.Test

class WalletCoreTest {
    // Public Hardhat/Anvil test phrase. NEVER put real funds on it.
    private val testMnemonic = "test test test test test test test test test test test junk"

    @Test
    fun derivesKnownAddress() {
        val creds = WalletCore.deriveCredentials(testMnemonic, index = 0)
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", WalletCore.addressOf(creds))
    }

    @Test
    fun secondAccountDiffers() {
        val a0 = WalletCore.addressOf(WalletCore.deriveCredentials(testMnemonic, 0))
        val a1 = WalletCore.addressOf(WalletCore.deriveCredentials(testMnemonic, 1))
        assertNotEquals(a0, a1)
        assertEquals("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", a1)
    }

    @Test
    fun generatedMnemonicIsValid() {
        assertEquals(12, WalletCore.generateMnemonic(12).split(" ").size)
        assertEquals(24, WalletCore.generateMnemonic(24).split(" ").size)
        assertTrue(WalletCore.isValidMnemonic(WalletCore.generateMnemonic()))
    }

    @Test
    fun rejectsBadChecksum() {
        assertFalse(WalletCore.isValidMnemonic("test test test test test test test test test test test test"))
    }

    @Test
    fun importsPrivateKey() {
        val key = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
        val creds = WalletCore.credentialsFromPrivateKey(key)
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", WalletCore.addressOf(creds))
    }

    @Test
    fun rejectsBadPrivateKeys() {
        assertNull(WalletCore.normalizePrivateKey("1234"))
        assertNull(WalletCore.normalizePrivateKey("0".repeat(64)))
        assertNull(WalletCore.normalizePrivateKey("f".repeat(64)))   // >= curve order
        assertNull(WalletCore.normalizePrivateKey("zz" + "0".repeat(62)))
    }
}