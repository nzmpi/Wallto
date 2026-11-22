package com.nzm.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Utf8String
import org.web3j.utils.Numeric

class Erc20Test {
    private fun abiString(s: String) =
        Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(listOf(Utf8String(s))))

    @Test
    fun decodesShortAndLongNames() {
        assertEquals("USDC", Erc20.decodeString(abiString("USDC")))
        // 45 chars: the length byte is '-', which the old text parser put in front of the name
        val long = "A".repeat(45)
        assertEquals(long, Erc20.decodeString(abiString(long)))
        val veryLong = "B".repeat(130)
        assertEquals(veryLong, Erc20.decodeString(abiString(veryLong)))
    }

    @Test
    fun decodesBytes32Names() {
        val raw = ByteArray(32).also { "MKR".toByteArray().copyInto(it) }
        assertEquals("MKR", Erc20.decodeString(raw))
    }

    @Test
    fun emptyIsNull() {
        assertNull(Erc20.decodeString(ByteArray(0)))
        assertNull(Erc20.decodeString(abiString("")))
    }
}
