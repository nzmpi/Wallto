package com.nzm.wallet.wc

import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Constants.MAX_UINT
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class PersonalMsg(val bytes: ByteArray, val text: String, val isText: Boolean)

class TypedReq(
    val json: String,
    val primaryType: String,
    val domainName: String?,
    val domainChainId: Long?,
    val verifyingContract: String?,
    val pretty: String
) {
    val looksLikePermit: Boolean
        get() = primaryType.contains("Permit", true) || primaryType.contains("Order", true) ||
                primaryType.contains("Approval", true)
}

class WcTx(val to: String, val value: BigInteger, val data: String)

enum class CallKind { TRANSFER, APPROVE, APPROVE_ALL, TRANSFER_FROM }

/**
 * A decoded token call. [party]: recipient, spender or operator. [from]: transferFrom only.
 * [amount]: token amount or NFT id; for APPROVE_ALL the raw approved flag.
 */
class KnownCall(
    val kind: CallKind,
    val party: String,
    val amount: BigInteger,
    val token: Token?,
    val from: String? = null
) {
    val danger: Boolean get() = kind == CallKind.APPROVE || kind == CallKind.APPROVE_ALL
}

object WcSigner {
    private fun isHex(s: String) =
        s.startsWith("0x") && s.length % 2 == 0 && s.drop(2)
            .all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    fun parsePersonal(params: String, wallet: String): PersonalMsg {
        val arr = JSONArray(params)
        require(arr.length() >= 2) { "Bad request" }
        val a = arr.getString(0)
        val b = arr.getString(1)
        val (msg, addr) = if (a.equals(wallet, true)) b to a else a to b
        require(addr.equals(wallet, true)) { "Request is for a different address" }
        val bytes =
            if (isHex(msg)) Numeric.hexStringToByteArray(msg) else msg.toByteArray(Charsets.UTF_8)
        val text = try {
            val s = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            if (s.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) null else s
        } catch (_: Exception) {
            null
        }
        return PersonalMsg(bytes, text ?: Numeric.toHexString(bytes), text != null)
    }

    fun signPersonal(c: Credentials, bytes: ByteArray): String =
        hex(Sign.signPrefixedMessage(bytes, c.ecKeyPair))

    fun parseTyped(params: String, wallet: String): TypedReq {
        val arr = JSONArray(params)
        require(arr.length() >= 2) { "Bad request" }
        require(arr.getString(0).equals(wallet, true)) { "Request is for a different address" }
        val raw = arr.get(1)
        val json = raw as? String ?: raw.toString()
        val o = JSONObject(json)
        val domain = o.getJSONObject("domain")
        val chainId = when (val v = domain.opt("chainId")) {
            is Number -> v.toLong()
            is String -> if (v.startsWith("0x")) v.drop(2).toLong(16) else v.toLong()
            else -> null
        }
        return TypedReq(
            json = json,
            primaryType = o.getString("primaryType"),
            domainName = domain.optString("name").takeIf { it.isNotEmpty() },
            domainChainId = chainId,
            verifyingContract = domain.optString("verifyingContract").takeIf { it.isNotEmpty() },
            pretty = o.getJSONObject("message").toString(2)
        )
    }

    fun signTyped(c: Credentials, t: TypedReq): String {
        val hash = StructuredDataEncoder(t.json).hashStructuredData()
        return hex(Sign.signMessage(hash, c.ecKeyPair, false))
    }

    fun parseTx(params: String, wallet: String): WcTx {
        val o = JSONArray(params).getJSONObject(0)
        require(o.optString("from").equals(wallet, true)) { "Request is for a different address" }
        val to = o.optString("to")
        require(isValidAddress(to)) { "Contract creation or missing recipient is not supported" }
        val value = o.optString("value").let {
            if (it.isEmpty() || it == "0x") BigInteger.ZERO else Numeric.decodeQuantity(it)
        }
        var data = o.optString("data").ifEmpty { o.optString("input") }
        if (data == "0x") data = ""
        require(data.isEmpty() || isHex(data)) { "Bad call data" }
        return WcTx(to, value, data)
    }

    /**
     * The one decoder for token calls, used by every screen. Null for any other call, or for
     * call data too short to hold the arguments.
     */
    fun decodeCall(to: String, data: String, tokens: List<Token>): KnownCall? {
        val d = data.removePrefix("0x").lowercase()
        fun word(i: Int) = d.substring(8 + i * 64, 8 + (i + 1) * 64)
        fun addr(i: Int) = Keys.toChecksumAddress("0x" + word(i).takeLast(40))
        fun num(i: Int) = BigInteger(word(i), 16)
        val token = tokens.firstOrNull { it.address.equals(to, true) }
        return try {
            when (d.take(8)) {
                "a9059cbb" -> KnownCall(CallKind.TRANSFER, addr(0), num(1), token)
                "095ea7b3" -> KnownCall(CallKind.APPROVE, addr(0), num(1), token)
                "a22cb465" -> KnownCall(CallKind.APPROVE_ALL, addr(0), num(1), token)
                "23b872dd" -> KnownCall(
                    CallKind.TRANSFER_FROM,
                    addr(1),
                    num(2),
                    token,
                    from = addr(0)
                )

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** "Unlimited USDC", "1.5 USDC" or "123 raw units" when the token is not in the list. */
    fun amountLabel(raw: BigInteger, token: Token?): String = when {
        raw == MAX_UINT -> "Unlimited" + (token?.let { " ${it.symbol}" } ?: "")
        token == null -> "$raw raw units"
        else -> shortAmount(raw, token.decimals) + " ${token.symbol}"
    }

    /** Plain-words description of the call data. Only the common dangerous ones are decoded. */
    fun describe(
        tx: WcTx,
        tokens: List<Token>,
        guess: SelectorLookup.Guess? = null
    ): Pair<String, Boolean> {
        if (tx.data.isEmpty()) return "Plain transfer, no contract call" to false
        val c = decodeCall(tx.to, tx.data, tokens)
        if (c != null) {
            val amount = amountLabel(c.amount, c.token)
            return when (c.kind) {
                CallKind.APPROVE -> "Approve token spending\nSpender: ${c.party}\nAmount: $amount"
                CallKind.APPROVE_ALL -> "setApprovalForAll: gives ${c.party} control of ALL your tokens in this collection"
                CallKind.TRANSFER -> "Token transfer\nTo: ${c.party}\nAmount: $amount"
                CallKind.TRANSFER_FROM -> "transferFrom\nFrom: ${c.from}\nTo: ${c.party}\nAmount/ID: $amount"
            } to c.danger
        }
        val sel = tx.data.drop(2).take(8).lowercase()
        return if (guess != null) {
            buildString {
                append("Call ${guess.signature.substringBefore('(')}()")
                append("\nName from a public database, unverified")
                guess.args?.take(8)?.forEach { (t, v) -> append("\n$t: $v") }
                guess.risks.forEach { append("\nWARNING: $it") }
            } to guess.risks.isNotEmpty()
        } else {
            "Contract call, function $sel (${tx.data.length / 2 - 5} bytes of arguments). Not decoded." to false
        }
    }

    private fun hex(s: Sign.SignatureData) = Numeric.toHexString(s.r + s.s + s.v)
}
