package com.nzm.wallet.wc

import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Constants.MAX_UINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class WcSignerTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"
    private val usdc = "0x3333333333333333333333333333333333333333"
    private val tokens = listOf(Token(1, usdc, "USDC", "USD Coin", 6))

    private fun word(hex: String) = hex.removePrefix("0x").padStart(64, '0')
    private fun word(n: BigInteger) = word(n.toString(16))
    private fun call(sel: String, vararg words: String) = "0x$sel" + words.joinToString("")

    // ---- parsing: the checks on what a site sends ----

    @Test
    fun personalDecodesHexAndAcceptsEitherOrder() {
        val m = WcSigner.parsePersonal("""["0x6869", "$me"]""", me)
        assertTrue(m.isText)
        assertEquals("hi", m.text)
        assertEquals("hi", WcSigner.parsePersonal("""["$me", "hi"]""", me).text)
    }

    @Test
    fun personalRejectsOtherAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            WcSigner.parsePersonal("""["hi", "$other"]""", me)
        }
    }

    @Test
    fun typedReadsDomainAndRejectsOtherAddress() {
        val json = """{"types":{"EIP712Domain":[]},"primaryType":"Permit",
            "domain":{"name":"USDC","chainId":"0x1","verifyingContract":"$usdc"},"message":{}}"""
        val t = WcSigner.parseTyped("""["$me", ${org.json.JSONObject.quote(json)}]""", me)
        assertEquals(1L, t.domainChainId)
        assertEquals("USDC", t.domainName)
        assertTrue(t.looksLikePermit)
        assertThrows(IllegalArgumentException::class.java) {
            WcSigner.parseTyped("""["$other", ${org.json.JSONObject.quote(json)}]""", me)
        }
    }

    @Test
    fun txChecksSenderRecipientAndData() {
        val ok = WcSigner.parseTx(
            """[{"from":"$me","to":"$other","value":"0x10","data":"0xabcd"}]""",
            me
        )
        assertEquals(BigInteger.valueOf(16), ok.value)
        assertEquals("0xabcd", ok.data)
        assertEquals(
            "",
            WcSigner.parseTx("""[{"from":"$me","to":"$other","data":"0x"}]""", me).data
        )

        listOf(
            """[{"from":"$other","to":"$other"}]""",           // signs for someone else
            """[{"from":"$me","data":"0x60806040"}]""",       // contract creation
            """[{"from":"$me","to":"$other","data":"0xzz"}]""", // not hex
            """[{"from":"$me","to":"$other","data":"0xabc"}]""" // odd length
        ).forEach { p ->
            assertThrows(p, IllegalArgumentException::class.java) { WcSigner.parseTx(p, me) }
        }
    }

    // ---- decoding: what the screens show ----

    @Test
    fun decodesUnlimitedApproveAsDanger() {
        val c = WcSigner.decodeCall(usdc, call("095ea7b3", word(other), word(MAX_UINT)), tokens)!!
        assertEquals(CallKind.APPROVE, c.kind)
        assertTrue(c.danger)
        assertTrue(c.party.equals(other, true))
        assertEquals("Unlimited USDC", WcSigner.amountLabel(c.amount, c.token))
    }

    @Test
    fun decodesTransferAndTransferFrom() {
        val t = WcSigner.decodeCall(
            usdc,
            call("a9059cbb", word(other), word(BigInteger.valueOf(1_500_000))),
            tokens
        )!!
        assertEquals(CallKind.TRANSFER, t.kind)
        assertFalse(t.danger)
        assertEquals("1.5 USDC", WcSigner.amountLabel(t.amount, t.token))

        val f = WcSigner.decodeCall(
            other,
            call("23b872dd", word(me), word(other), word(BigInteger.TEN)),
            tokens
        )!!
        assertEquals(CallKind.TRANSFER_FROM, f.kind)
        assertTrue(f.from!!.equals(me, true))
        assertTrue(f.party.equals(other, true))
        assertEquals("10 raw units", WcSigner.amountLabel(f.amount, f.token))
    }

    @Test
    fun approveAllIsDanger() {
        val c = WcSigner.decodeCall(other, call("a22cb465", word(me), word("1")), tokens)!!
        assertEquals(CallKind.APPROVE_ALL, c.kind)
        assertTrue(c.danger)
    }

    @Test
    fun revokeAllIsNotDanger() {
        val c = WcSigner.decodeCall(other, call("a22cb465", word(me), word("0")), tokens)!!
        assertEquals(CallKind.REVOKE_ALL, c.kind)
        assertFalse(c.danger)
    }

    @Test
    fun unknownOrTruncatedIsNotDecoded() {
        assertNull(WcSigner.decodeCall(usdc, call("deadbeef", word(other)), tokens))
        assertNull(
            WcSigner.decodeCall(
                usdc,
                call("095ea7b3", word(other)),
                tokens
            )
        ) // amount missing
        assertNull(WcSigner.decodeCall(usdc, "0x", tokens))
    }

    @Test
    fun describeUsesSharedDecoder() {
        val (text, danger) = WcSigner.describe(
            WcTx(usdc, BigInteger.ZERO, call("095ea7b3", word(other), word(MAX_UINT))), tokens
        )
        assertTrue(danger)
        assertTrue(text, "Unlimited USDC" in text)
        val (unknown, d2) = WcSigner.describe(
            WcTx(
                usdc,
                BigInteger.ZERO,
                call("deadbeef", word(other))
            ), tokens
        )
        assertFalse(d2)
        assertTrue(unknown, "deadbeef (32 bytes of arguments)" in unknown)
    }

    // ---- audit round 3 ----

    @Test
    fun txRefusesCallToOwnAccountButAllowsPlainSelfTransfer() {
        assertThrows(IllegalArgumentException::class.java) {
            WcSigner.parseTx("""[{"from":"$me","to":"$me","data":"0xe9ae5c53"}]""", me)
        }
        assertEquals("", WcSigner.parseTx("""[{"from":"$me","to":"$me","value":"0x1"}]""", me).data)
    }

    @Test
    fun personalWithBidiOverrideIsShownAsHex() {
        val m = WcSigner.parsePersonal("""["Sign in to \u202Egro.live", "$me"]""", me)
        assertFalse(m.isText)
        assertTrue(m.text.startsWith("0x"))
    }

    private val chains = listOf(com.nzm.wallet.dbs.Chain(1, "Ethereum", "https://x", "ETH"))
    private fun calls(callsJson: String, extra: String = "") =
        """[{"version":"2.0.0","chainId":"0x1","from":"$me","calls":$callsJson$extra}]"""

    private fun parse(params: String, taken: Set<String> = emptySet()) =
        WcSigner.parseCalls(params, me, chains, { tokens }, { it in taken })

    private fun refusedCode(params: String, taken: Set<String> = emptySet()): Int =
        assertThrows(WcSigner.Refused::class.java) { parse(params, taken) }.code

    @Test
    fun batchParsesTransferAndRefusesBadRequests() {
        val transfer = call("a9059cbb", word(other), word(BigInteger.valueOf(1_500_000)))
        val ok = parse(calls("""[{"to":"$usdc","data":"$transfer"}]""", ""","id":"a1""""))
        assertEquals("a1", ok.id)
        assertEquals("Send 1.5 USDC", ok.items.single().line)

        // a call to the account itself is refused, even with no data
        assertEquals(-32602, refusedCode(calls("""[{"to":"$me","data":"0xe9ae5c53"}]""")))
        assertEquals(-32602, refusedCode(calls("""[{"to":"$me"}]""")))
        val many = (1..21).joinToString(",", "[", "]") { """{"to":"$other"}""" }
        assertEquals(5700, refusedCode(calls(many)))
        assertEquals(5700, refusedCode(calls("[]")))
        assertEquals(
            5720,
            refusedCode(calls("""[{"to":"$other"}]""", ""","id":"a1""""), setOf("a1"))
        )
        assertEquals(
            5700,
            refusedCode(
                calls(
                    """[{"to":"$other"}]""",
                    ""","capabilities":{"paymasterService":{}}"""
                )
            )
        )
        assertEquals(
            4100,
            refusedCode("""[{"chainId":"0x1","from":"$other","calls":[{"to":"$usdc"}]}]""")
        )
        // 2^64 + 1 must not wrap around to chain 1
        assertEquals(
            -32602,
            refusedCode("""[{"chainId":"0x10000000000000001","calls":[{"to":"$other"}]}]""")
        )
    }
}
