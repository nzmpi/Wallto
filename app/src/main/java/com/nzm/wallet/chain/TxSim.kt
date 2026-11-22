package com.nzm.wallet.chain

import android.content.Context
import com.nzm.wallet.abi.AbiCodec
import com.nzm.wallet.abi.AbiType
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.storage.ApiKeyStore
import com.nzm.wallet.utils.Constants.ZERO
import com.nzm.wallet.utils.Helpers.isUnlimited
import com.nzm.wallet.utils.Helpers.readCapped
import com.nzm.wallet.utils.Helpers.sanitize
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import kotlin.collections.iterator

/** The Alchemy key. Stored by the same code as the CoinGecko key. */
object SimKey {
    private fun store(context: Context) = ApiKeyStore(context, "sim_prefs", "sim_api_key")
    fun get(context: Context): String? = store(context).get()
    fun set(context: Context, value: String?) = store(context).set(value)
}

class SimLine(
    val symbol: String,
    val amount: String,
    val incoming: Boolean,
    val unlistedAddress: String?   // set when the token is not in your token list
)

class SimApproval(
    val symbol: String,
    val spender: String,
    val amount: String,
    val unlimited: Boolean,
    val nft: Boolean = false       // [amount] is "all items" or "#id", [symbol] the collection
)

/** What the logs of a simulated call do to the user's address, before token lookups. */
internal class SimLogs(
    val deltas: Map<String, BigInteger>,                 // token (lowercase) -> net change
    val nfts: List<String>,                              // "+NFT #1 (0xab…cd)", "−3 × #7 (…)"
    val approvals: List<Triple<String, String, BigInteger>>, // token, spender, amount
    val nftApprovals: List<SimApproval>
)

class SimResult(
    val failure: String?,
    val gasUsed: BigInteger?,
    val lines: List<SimLine>,
    val nfts: List<String>,
    val approvals: List<SimApproval>
)

/** Runs a transaction through Alchemy's eth_simulateV1. Everything else keeps using your own RPC. */
object TxSim {
    private val SLUGS = mapOf(
        1L to "eth-mainnet",
        10L to "opt-mainnet",
        137L to "polygon-mainnet",
        42161L to "arb-mainnet",
        8453L to "base-mainnet",
        56L to "bnb-mainnet",
        43114L to "avax-mainnet",
        59144L to "linea-mainnet",
        534352L to "scroll-mainnet",
        324L to "zksync-mainnet",
        5000L to "mantle-mainnet",
        81457L to "blast-mainnet",
        42220L to "celo-mainnet",
        100L to "gnosis-mainnet",
        11155111L to "eth-sepolia",
        84532L to "base-sepolia",
        421614L to "arb-sepolia",
        11155420L to "opt-sepolia",
        80002L to "polygon-amoy"
    )

    private const val TRANSFER =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private const val APPROVAL =
        "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"
    private const val NATIVE = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    private val TRANSFER_SINGLE =
        Hash.sha3String("TransferSingle(address,address,address,uint256,uint256)")
    private val TRANSFER_BATCH =
        Hash.sha3String("TransferBatch(address,address,address,uint256[],uint256[])")
    private val APPROVAL_FOR_ALL = Hash.sha3String("ApprovalForAll(address,address,bool)")
    private val UINTS = AbiType.Arr(AbiType.Uint(256))

    fun supported(chainId: Long) = SLUGS.containsKey(chainId)

    private fun hex(v: BigInteger) = "0x" + v.toString(16)

    private fun readBody(c: HttpURLConnection, code: Int): String {
        val ins = if (code in 200..299) c.inputStream else c.errorStream ?: return ""
        val bytes = ins.readCapped(2_000_000) ?: error("Response too large")
        return String(bytes, Charsets.UTF_8)
    }

    private fun post(url: String, body: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Alchemy rejected the key")
            if (code == 429) throw IllegalStateException("Alchemy rate limit reached. Try again later.")
            val text = readBody(c, code)
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IllegalStateException("Alchemy error $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun addrOf(topic: String) = "0x" + topic.takeLast(40).lowercase()

    private fun num(h: String): BigInteger {
        val s = h.removePrefix("0x")
        return if (s.isEmpty()) BigInteger.ZERO else BigInteger(s, 16)
    }

    /**
     * Reads ERC-20 / ERC-721 / ERC-1155 transfers and approvals that touch [owner].
     * ERC-1155 matters: without it a call that sends your items away would show "no changes".
     */
    internal fun readLogs(logs: JSONArray, owner: String): SimLogs {
        val user = owner.lowercase()
        val deltas = LinkedHashMap<String, BigInteger>()
        val nfts = mutableListOf<String>()
        val approvals = mutableListOf<Triple<String, String, BigInteger>>()
        val nftApprovals = mutableListOf<SimApproval>()
        fun item(sign: String, label: String, collection: String) =
            nfts.add("$sign$label (${shortAddress(Keys.toChecksumAddress(collection))})")

        fun moved(from: String, to: String, label: String, collection: String) {
            if (from == user) item("−", label, collection)
            if (to == user) item("+", label, collection)
        }
        for (i in 0 until logs.length()) {
            val l = logs.getJSONObject(i)
            val topics = l.optJSONArray("topics") ?: continue
            if (topics.length() == 0) continue
            fun topic(n: Int) = topics.getString(n)
            val t0 = topic(0).lowercase()
            val token = l.optString("address").lowercase()
            val data = l.optString("data", "0x")
            when (t0) {
                TRANSFER if topics.length() == 3 -> {
                    val v = num(data)
                    if (addrOf(topic(1)) == user) deltas[token] =
                        (deltas[token] ?: BigInteger.ZERO) - v
                    if (addrOf(topic(2)) == user) deltas[token] =
                        (deltas[token] ?: BigInteger.ZERO) + v
                }

                TRANSFER if topics.length() == 4 ->
                    moved(addrOf(topic(1)), addrOf(topic(2)), "NFT #${num(topic(3))}", token)

                TRANSFER_SINGLE if topics.length() == 4 -> {
                    val d = data.removePrefix("0x")
                    val id = num(d.take(64))
                    val v = num(d.drop(64).take(64))
                    moved(addrOf(topic(2)), addrOf(topic(3)), "$v × #$id", token)
                }

                TRANSFER_BATCH if topics.length() == 4 -> {
                    val (ids, values) = AbiCodec.decodeOutputs(
                        listOf(UINTS, UINTS), Numeric.hexStringToByteArray(data)
                    ).map { it.removeSurrounding("[", "]").split(", ").filter(String::isNotBlank) }
                    ids.forEachIndexed { k, id ->
                        moved(
                            addrOf(topic(2)),
                            addrOf(topic(3)),
                            "${values.getOrNull(k) ?: "?"} × #$id",
                            token
                        )
                    }
                }

                APPROVAL if topics.length() == 3 && addrOf(topic(1)) == user ->
                    approvals.add(Triple(token, addrOf(topic(2)), num(data)))

                // ERC-721 approve(spender, id): one item
                APPROVAL if topics.length() == 4 && addrOf(topic(1)) == user &&
                        addrOf(topic(2)) != ZERO ->
                    nftApprovals.add(
                        nftApproval(
                            token,
                            addrOf(topic(2)),
                            "#${num(topic(3))}",
                            false
                        )
                    )

                APPROVAL_FOR_ALL if topics.length() == 3 && addrOf(topic(1)) == user &&
                        num(data).signum() != 0 ->
                    nftApprovals.add(nftApproval(token, addrOf(topic(2)), "all items", true))
            }
        }
        return SimLogs(deltas, nfts, approvals, nftApprovals)
    }

    private fun nftApproval(collection: String, spender: String, what: String, all: Boolean) =
        SimApproval(
            shortAddress(Keys.toChecksumAddress(collection)),
            shortAddress(Keys.toChecksumAddress(spender)),
            what, all, nft = true
        )

    suspend fun run(chain: Chain, plan: TxPlan, apiKey: String, known: List<Token>): SimResult =
        withContext(Dispatchers.IO) {
            val slug = SLUGS[chain.chainId]
                ?: throw IllegalStateException("Simulation is not available for ${chain.name}")
            val call = JSONObject()
                .put("from", plan.from)
                .put("to", plan.to)
                .put("value", hex(plan.value))
            if (plan.data.isNotBlank() && plan.data != "0x") call.put("data", plan.data)
            val req = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "eth_simulateV1")
                .put(
                    "params", JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "blockStateCalls",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "calls",
                                            JSONArray().put(call)
                                        )
                                    )
                                )
                                .put("traceTransfers", true)
                                .put("validation", false)
                        )
                        .put("latest")
                )
            val j = post("https://$slug.g.alchemy.com/v2/$apiKey", req.toString())
            j.optJSONObject("error")?.let {
                val m = it.optString("message", "error")
                throw IllegalStateException(
                    if (m.contains("not found", true) || m.contains(
                            "not supported",
                            true
                        ) || m.contains("does not exist", true)
                    )
                        "Alchemy does not support simulation on ${chain.name}"
                    else "Alchemy: ${m.take(160)}"
                )
            }
            val res =
                j.getJSONArray("result").getJSONObject(0).getJSONArray("calls").getJSONObject(0)
            val gas = res.optString("gasUsed", "").takeIf { it.startsWith("0x") }?.let { num(it) }
            if (res.optString("status", "0x1") == "0x0") {
                val msg = res.optJSONObject("error")?.optString("message")?.sanitize(200)
                    ?.ifBlank { null }
                val why = AbiCodec.decodeRevert(res.optString("returnData", "")) ?: msg
                ?: "execution reverted"
                return@withContext SimResult(
                    "Would fail: $why",
                    gas,
                    emptyList(),
                    emptyList(),
                    emptyList()
                )
            }

            val read = readLogs(res.optJSONArray("logs") ?: JSONArray(), plan.from)
            val deltas = LinkedHashMap(read.deltas)
            // some nodes do not log the top-level value; do not miss it
            if (plan.value.signum() > 0 && deltas[NATIVE] == null) deltas[NATIVE] =
                plan.value.negate()

            val infos = HashMap<String, Token?>()
            suspend fun info(addr: String): Token? = infos.getOrPut(addr) {
                known.firstOrNull { it.address.equals(addr, true) }
                    ?: try {
                        Erc20.fetchInfo(chain.rpcUrl, chain.chainId, Keys.toChecksumAddress(addr))
                    } catch (_: Exception) {
                        null
                    }
            }

            val lines = mutableListOf<SimLine>()
            for ((addr, d) in deltas) {
                if (d.signum() == 0) continue
                val abs = d.abs()
                if (addr == NATIVE) {
                    lines.add(
                        SimLine(
                            chain.symbol, shortAmount(abs, chain.decimals),
                            d.signum() > 0, null
                        )
                    )
                } else {
                    val t = info(addr)
                    val listed = known.any { it.address.equals(addr, true) }
                    val dec = t?.decimals ?: 18
                    lines.add(
                        SimLine(
                            t?.symbol ?: "unknown token", shortAmount(abs, dec),
                            d.signum() > 0,
                            if (listed) null else Keys.toChecksumAddress(addr)
                        )
                    )
                }
            }
            val approvals = read.approvals.map { (addr, spender, v) ->
                val t = info(addr)
                val dec = t?.decimals ?: 18
                SimApproval(
                    t?.symbol ?: shortAddress(Keys.toChecksumAddress(addr)),
                    shortAddress(Keys.toChecksumAddress(spender)),
                    shortAmount(v, dec),
                    isUnlimited(v)
                )
            }
            SimResult(
                null,
                gas,
                lines.sortedBy { it.incoming },
                read.nfts,
                approvals + read.nftApprovals
            )
        }
}
