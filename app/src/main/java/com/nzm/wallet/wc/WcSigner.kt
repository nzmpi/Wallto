package com.nzm.wallet.wc

import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.chain.Token
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Credentials
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

object WcSigner {
    private val MAX_UINT = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

    private fun isHex(s: String) =
        s.startsWith("0x") && s.length % 2 == 0 && s.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    fun parsePersonal(params: String, wallet: String): PersonalMsg {
        val arr = JSONArray(params)
        require(arr.length() >= 2) { "Bad request" }
        val a = arr.getString(0)
        val b = arr.getString(1)
        val (msg, addr) = if (a.equals(wallet, true)) b to a else a to b
        require(addr.equals(wallet, true)) { "Request is for a different address" }
        val bytes = if (isHex(msg)) Numeric.hexStringToByteArray(msg) else msg.toByteArray(Charsets.UTF_8)
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

    fun signPersonal(c: Credentials, bytes: ByteArray): String = hex(Sign.signPrefixedMessage(bytes, c.ecKeyPair))

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
        require(SendTx.isValidAddress(to)) { "Contract creation or missing recipient is not supported" }
        val value = o.optString("value").let {
            if (it.isEmpty() || it == "0x") BigInteger.ZERO else Numeric.decodeQuantity(it)
        }
        var data = o.optString("data").ifEmpty { o.optString("input") }
        if (data == "0x") data = ""
        require(data.isEmpty() || isHex(data)) { "Bad call data" }
        return WcTx(to, value, data)
    }

    /** Plain-words description of the call data. Only the common dangerous ones are decoded. */
    fun describe(tx: WcTx, tokens: List<Token>): Pair<String, Boolean> {
        if (tx.data.isEmpty()) return "Plain transfer, no contract call" to false
        val d = tx.data.drop(2).lowercase()
        val sel = d.take(8)
        fun word(i: Int) = d.drop(8 + i * 64).take(64)
        fun addr(i: Int) = "0x" + word(i).takeLast(40)
        val token = tokens.firstOrNull { it.address.equals(tx.to, true) }
        fun amount(w: String): String {
            val v = BigInteger(w, 16)
            if (v == MAX_UINT) return "UNLIMITED"
            val dec = token?.decimals ?: return "$v (raw units)"
            return v.toBigDecimal().movePointLeft(dec).stripTrailingZeros().toPlainString() + " ${token.symbol}"
        }
        return try {
            when (sel) {
                "095ea7b3" if d.length >= 8 + 128 ->
                    "Approve token spending\nSpender: ${addr(0)}\nAmount: ${amount(word(1))}" to true

                "a22cb465" if d.length >= 8 + 128 ->
                    "setApprovalForAll: gives ${addr(0)} control of ALL your tokens in this collection" to true

                "a9059cbb" if d.length >= 8 + 128 ->
                    "Token transfer\nTo: ${addr(0)}\nAmount: ${amount(word(1))}" to false

                "23b872dd" if d.length >= 8 + 192 ->
                    "transferFrom\nFrom: ${addr(0)}\nTo: ${addr(1)}\nAmount/ID: ${amount(word(2))}" to false

                else -> "Contract call, function $sel (${d.length / 2 - 4} bytes of arguments). Not decoded." to false
            }
        } catch (_: Exception) {
            "Contract call, function $sel. Not decoded." to false
        }
    }

    private fun hex(s: Sign.SignatureData) = Numeric.toHexString(s.r + s.s + s.v)
}
