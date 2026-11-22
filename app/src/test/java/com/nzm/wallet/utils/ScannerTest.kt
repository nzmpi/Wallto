package com.nzm.wallet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {
    private val bob = "0x2222222222222222222222222222222222222222"
    private val usdc = "0x3333333333333333333333333333333333333333"

    @Test
    fun plainAddressAndSimpleLinkGiveTheRecipient() {
        assertEquals(bob, (parseScan(bob) as ScanResult.Address).value)
        assertEquals(bob, (parseScan("ethereum:$bob?value=1e18") as ScanResult.Address).value)
        assertTrue(parseScan("wc:abc@2?relay-protocol=irn") is ScanResult.WalletConnect)
    }

    @Test
    fun linksNamingAChainOrTokenAreRefused() {
        assertTrue(parseScan("ethereum:$bob@137") is ScanResult.Unsupported)
        assertTrue(parseScan("ethereum:$usdc/transfer?address=$bob&uint256=1") is ScanResult.Unsupported)
        assertTrue(parseScan("hello") is ScanResult.Unknown)
    }
}
