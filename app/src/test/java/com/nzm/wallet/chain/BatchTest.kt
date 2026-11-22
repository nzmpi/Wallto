package com.nzm.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class BatchTest {
    private fun w(hex: String) = hex.removePrefix("0x").padStart(64, '0')
    private fun w(n: Int) = w(n.toString(16))

    @Test
    fun encodesErc7821ExecuteByHand() {
        val a = "0x1111111111111111111111111111111111111111"
        val b = "0x2222222222222222222222222222222222222222"
        val items = listOf(
            BatchItem("send", a, BigInteger.ONE, ByteArray(0)),
            BatchItem("call", b, BigInteger.ZERO, byteArrayOf(0xab.toByte(), 0xcd.toByte()))
        )
        // abi.encode(Call[]): offset, length, element offsets, then each (to, value, bytes)
        val executionData = w(0x20) + w(2) + w(0x40) + w(0xc0) +
                w(a) + w(1) + w(0x60) + w(0) +
                w(b) + w(0) + w(0x60) + w(2) + "abcd".padEnd(64, '0')
        val expected = "0xe9ae5c53" +
                "01".padEnd(64, '0') +          // all-or-nothing mode
                w(0x40) + w(executionData.length / 2) + executionData
        assertEquals(expected, Batch.encode(items, allOrNothing = true))
    }
}
