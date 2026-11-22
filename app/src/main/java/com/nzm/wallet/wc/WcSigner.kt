package com.nzm.wallet.wc

import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.chain.BatchItem
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Constants.MAX_UINT
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.sanitize
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import com.nzm.wallet.utils.Helpers.stripFormat
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

enum class CallKind { TRANSFER, APPROVE, APPROVE_ALL, REVOKE_ALL, TRANSFER_FROM }

/**
 * A decoded token call. [party]: recipient, spender or operator. [from]: transferFrom only.
 * [amount]: token amount or NFT id; for APPROVE_ALL / REVOKE_ALL the raw approved flag.
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
            // control chars, or bidi overrides that can show text reversed: show hex instead
            val odd = s.any {
                (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') ||
                        Character.getType(it) == Character.FORMAT.toInt()
            }
            if (odd) null else s
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
            // display fields only: the signature is made over json
            primaryType = o.getString("primaryType").sanitize(80),
            domainName = domain.optString("name").sanitize(80).takeIf { it.isNotEmpty() },
            domainChainId = chainId,
            verifyingContract = domain.optString("verifyingContract").sanitize(80)
                .takeIf { it.isNotEmpty() },
            pretty = o.getJSONObject("message").toString(2).stripFormat()
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
        require(data.isEmpty() || !to.equals(wallet, true)) { SELF_CALL }
        return WcTx(to, value, data)
    }

    /**
     * A call from a delegated (EIP-7702) account to itself runs execute() or the delegate's admin
     * functions (add a key, change settings) with full authority, and hides what it does. Sites never get it.
     */
    private const val SELF_CALL =
        "Calls to your own account are refused: they can run hidden calls or change the account's settings"

    /** A wallet_sendCalls request that can be signed. [id] is blank when the site sent none. */
    class BatchReq(val version: String, val id: String, val chain: Chain, val items: List<BatchItem>)

    /** A refusal with the EIP-5792 / JSON-RPC error code to send back to the site. */
    class Refused(message: String, val code: Int) : IllegalArgumentException(message)

    private fun hexBig(s: String?): BigInteger {
        val t = s?.removePrefix("0x")?.takeIf { it.isNotEmpty() } ?: return BigInteger.ZERO
        return BigInteger(t, 16)
    }

    /**
     * Parses wallet_sendCalls params. Throws [Refused] with a code, or another exception for bad JSON.
     * [tokensFor] gives the token list of a chain; [idTaken] says whether this site used the id before.
     */
    fun parseCalls(
        params: String,
        signer: String,
        chains: List<Chain>,
        tokensFor: (Long) -> List<Token>,
        idTaken: (String) -> Boolean
    ): BatchReq {
        val o = JSONArray(params).getJSONObject(0)
        val version = o.optString("version", "1.0")
        val cid = try {
            hexBig(o.optString("chainId")).longValueExact()
        } catch (_: ArithmeticException) {
            throw Refused("Invalid chain ID", -32602)
        }
        val chain = chains.firstOrNull { it.chainId == cid }
            ?: throw Refused("Chain $cid is not saved in this wallet. Add it first.", 5710)
        val from = o.optString("from", "")
        if (from.isNotBlank() && !from.equals(signer, true))
            throw Refused("The site asked for a different account than the one it connected with.", 4100)
        o.optJSONObject("capabilities")?.let { caps ->
            for (k in caps.keys()) {
                val optional = caps.optJSONObject(k)?.optBoolean("optional", false) == true
                if (!optional) throw Refused("The site needs a feature this wallet does not support: $k", 5700)
            }
        }
        val calls = o.getJSONArray("calls")
        if (calls.length() == 0) throw Refused("The batch has no calls.", 5700)
        if (calls.length() > 20) throw Refused("The batch has too many calls (max 20).", 5700)
        val tokens = tokensFor(chain.chainId)
        val items = (0 until calls.length()).map { i ->
            val c = calls.getJSONObject(i)
            val toRaw = c.optString("to", "")
            if (!isValidAddress(toRaw)) throw Refused("Call ${i + 1} has an invalid address", -32602)
            val to = Keys.toChecksumAddress(toRaw)
            if (to.equals(signer, true)) throw Refused("Call ${i + 1}: $SELF_CALL", -32602)
            val value = hexBig(c.optString("value", "0x0"))
            val data = Numeric.hexStringToByteArray(c.optString("data", "0x").ifBlank { "0x" })
            batchItem(chain, tokens, to, value, data)
        }
        val id = o.optString("id", "")
        if (id.isNotBlank() && idTaken(id)) throw Refused("The site reused a batch id.", 5720)
        return BatchReq(version, id, chain, items)
    }

    /** One call of a site's batch, in words, with its warnings. */
    private fun batchItem(
        c: Chain,
        tokens: List<Token>,
        to: String,
        value: BigInteger,
        data: ByteArray
    ): BatchItem {
        if (data.isEmpty()) {
            return BatchItem("Send ${shortAmount(value, c.decimals)} ${c.symbol}", to, value, data)
        }
        val k = decodeCall(to, Numeric.toHexString(data), tokens)
            ?: return BatchItem(
                "Call 0x${Numeric.toHexStringNoPrefix(data).take(8)}",
                to,
                value,
                data,
                listOf("Unknown call. Check the calldata under Advanced details."),
                link = "on"
            )
        val tk = k.token
        val amount = amountLabel(k.amount, tk)
        val unlisted = "Token is not in your list: ${shortAddress(to)}"
        return when (k.kind) {
            CallKind.TRANSFER -> if (tk != null) BatchItem(
                "Send $amount", to, value, data,
                recipient = k.party, token = tk, spend = k.amount
            ) else BatchItem(
                "Send $amount of an unlisted token", to, value, data,
                listOf(unlisted), recipient = k.party
            )

            CallKind.APPROVE -> BatchItem(
                "Approve $amount", to, value, data,
                buildList {
                    if (k.amount == MAX_UINT) add("Unlimited approval lets ${shortAddress(k.party)} spend all your tokens.")
                    if (tk == null) add(unlisted)
                },
                recipient = k.party, link = "for"
            )

            CallKind.APPROVE_ALL -> BatchItem(
                "Approve all tokens of ${shortAddress(to)}", to, value, data,
                listOf("Gives ${shortAddress(k.party)} control of all your tokens in this collection."),
                recipient = k.party, link = "for"
            )

            CallKind.REVOKE_ALL -> BatchItem(
                "Revoke approval for all tokens of ${shortAddress(to)}", to, value, data,
                recipient = k.party, link = "for"
            )

            CallKind.TRANSFER_FROM -> BatchItem(
                "Transfer $amount from ${shortAddress(k.from!!)}", to, value, data,
                recipient = k.party
            )
        }
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
                "a22cb465" -> KnownCall(
                    if (num(1).signum() == 0) CallKind.REVOKE_ALL else CallKind.APPROVE_ALL,
                    addr(0),
                    num(1),
                    token
                )

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
                CallKind.REVOKE_ALL -> "setApprovalForAll: removes ${c.party}'s control of your tokens in this collection"
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
