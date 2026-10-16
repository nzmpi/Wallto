package com.nzm.wallet.abi

import org.junit.Assert.*
import org.junit.Test
import org.web3j.utils.Numeric
import java.math.BigInteger

class AbiCodecTest {
    private fun hex(b: ByteArray) = Numeric.toHexStringNoPrefix(b)
    private fun call(sigText: String, vararg args: String): String {
        val sig = AbiCodec.parseSignature(sigText)
        val values = sig.inputs.mapIndexed { i, t -> AbiCodec.parseValue(t, args[i]) }
        return hex(AbiCodec.encodeCall(sig, values))
    }

    @Test fun selectors() {
        assertEquals("a9059cbb", hex(AbiCodec.parseSignature("transfer(address,uint256)").selector))
        assertEquals("095ea7b3", hex(AbiCodec.parseSignature("approve(address,uint256)").selector))
        assertEquals("a5643bf2", hex(AbiCodec.parseSignature("sam(bytes,bool,uint256[])").selector))
        assertEquals(
            "70a08231",
            hex(AbiCodec.parseSignature("function balanceOf(address owner) view returns (uint256)").selector)
        )
    }

    @Test fun bazVector() {
        val expected = "cdcd77c0" + "0".repeat(62) + "45" + "0".repeat(63) + "1"
        assertEquals(expected, call("baz(uint32,bool)", "69", "true"))
    }

    @Test fun samVector() {
        val expected = "a5643bf2" +
                "0".repeat(62) + "60" +
                "0".repeat(63) + "1" +
                "0".repeat(62) + "a0" +
                "0".repeat(62) + "04" +
                "64617665" + "0".repeat(56) +
                "0".repeat(62) + "03" +
                "0".repeat(63) + "1" +
                "0".repeat(63) + "2" +
                "0".repeat(63) + "3"
        assertEquals(expected, call("sam(bytes,bool,uint256[])", "0x64617665", "true", "[1,2,3]"))
    }

    @Test fun roundTrip() {
        val types = listOf(AbiType.Uint(256), AbiType.Str, AbiType.Address, AbiType.Arr(AbiType.Uint(256)))
        val inputs = listOf("123456789", "hello", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", "1,2,3")
        val values = types.mapIndexed { i, t -> AbiCodec.parseValue(t, inputs[i]) }
        val decoded = AbiCodec.decodeOutputs(types, AbiCodec.encodeArgs(types, values))
        assertEquals(
            listOf("123456789", "hello", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", "[1, 2, 3]"),
            decoded
        )
    }

    @Test fun negativeInt() {
        val t = AbiType.IntN(256)
        val enc = AbiCodec.encodeArgs(listOf(t), listOf(AbiCodec.parseValue(t, "-1")))
        assertEquals("f".repeat(64), hex(enc))
        assertEquals(listOf("-1"), AbiCodec.decodeOutputs(listOf(t), enc))
    }

    @Test fun rejects() {
        assertThrows(IllegalArgumentException::class.java) { AbiCodec.parseValue(AbiType.Uint(8), "256") }
        assertThrows(IllegalArgumentException::class.java) { AbiCodec.parseValue(AbiType.Address, "0x123") }
        assertThrows(IllegalArgumentException::class.java) { AbiCodec.parseType("uint256[][]") }
        assertThrows(IllegalArgumentException::class.java) { AbiCodec.parseType("string[]") }
        assertThrows(IllegalArgumentException::class.java) { AbiCodec.parseSignature("f((uint256,address))") }
    }

    @Test fun revertReason() {
        val data = "0x08c379a0" + hex(AbiCodec.encodeArgs(listOf(AbiType.Str), listOf("nope")))
        assertEquals("Reverted: nope", AbiCodec.decodeRevert(data))
        assertEquals("Custom error 0xe450d38c", AbiCodec.decodeRevert("0xe450d38c"))
    }

    @Test fun unlimitedApprovalWarns() {
        val sig = AbiCodec.parseSignature("approve(address,uint256)")
        val max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
        val notes = AbiCodec.riskNotes(sig, listOf("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", max))
        assertTrue(notes.any { it.contains("UNLIMITED") })
    }
}
