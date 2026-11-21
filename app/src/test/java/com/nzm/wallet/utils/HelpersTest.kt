package com.nzm.wallet.utils

import com.nzm.wallet.utils.Helpers.parseAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class HelpersTest {
    @Test
    fun parsesDotAndCommaDecimals() {
        assertEquals(BigInteger.valueOf(1_500_000), parseAmount("1.5", 6))
        assertEquals(BigInteger.valueOf(1_500_000), parseAmount(" 1,5 ", 6))
        assertEquals(BigInteger.valueOf(500_000), parseAmount(".5", 6))
        assertEquals(BigInteger.valueOf(1_000_000_000), parseAmount("1000", 6))
        assertEquals(BigInteger.valueOf(1_000_000), parseAmount("1.000", 6)) // dot is always decimal
        assertEquals(BigInteger.TEN.pow(18), parseAmount("1", 18))
    }

    @Test
    fun refusesAmbiguousOrOddInput() {
        listOf("1,000", "12,345", "", "abc", "1e3", "+5", "-1", "0", "1.2.3", "1,000.5", "1 000")
            .forEach { s ->
                assertThrows(s, IllegalArgumentException::class.java) { parseAmount(s, 6) }
            }
    }

    @Test
    fun refusesTooManyDecimals() {
        assertThrows(IllegalArgumentException::class.java) { parseAmount("0.0000001", 6) }
    }
}
