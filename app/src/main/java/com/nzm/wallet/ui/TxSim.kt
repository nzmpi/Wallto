package com.nzm.wallet.ui

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Constants.PROVIDER
import com.nzm.wallet.utils.Constants.TRANSFORM
import com.nzm.wallet.utils.Constants.TWO_255
import com.nzm.wallet.utils.Helpers.generateSecretKey
import com.nzm.wallet.utils.Helpers.getPrefs
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Keys
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SimKey {
    private const val ALIAS = "sim_api_key"
    private const val PREFS = "sim_prefs"
    private const val NAME = "k"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generateSecretKey(ALIAS)
    }

    fun get(context: Context): String? {
        val s = context.getPrefs(PREFS).getString(NAME, null)
            ?: return null
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun set(context: Context, value: String?) {
        context.getPrefs(PREFS).edit {
            val k = value?.trim()?.takeIf { it.isNotEmpty() }
            if (k == null) {
                remove(NAME).apply()
                return
            }
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(k.toByteArray(Charsets.UTF_8))
            putString(NAME, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP))
        }
    }
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
    val unlimited: Boolean
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

    fun supported(chainId: Long) = SLUGS.containsKey(chainId)

    private fun hex(v: BigInteger) = "0x" + v.toString(16)

    private fun readCapped(c: HttpURLConnection, code: Int): String {
        val ins = if (code in 200..299) c.inputStream else c.errorStream ?: return ""
        val buf = ByteArrayOutputStream()
        ins.use {
            val chunk = ByteArray(8192)
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                check(buf.size() <= 2_000_000) { "Response too large" }
            }
        }
        return buf.toString("UTF-8")
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
            val text = readCapped(c, code)
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

    private fun revertText(returnData: String?): String? {
        val d = returnData?.removePrefix("0x") ?: return null
        if (!d.startsWith("08c379a0") || d.length < 8 + 128) return null
        return try {
            val len = BigInteger(d.substring(8 + 64, 8 + 128), 16).toInt()
            val bytes =
                d.substring(8 + 128, 8 + 128 + len * 2).chunked(2).map { it.toInt(16).toByte() }
                    .toByteArray()
            String(bytes, Charsets.UTF_8).take(200)
        } catch (_: Exception) {
            null
        }
    }

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
                val msg = res.optJSONObject("error")?.optString("message")?.ifBlank { null }
                val why = revertText(res.optString("returnData", "")) ?: msg ?: "execution reverted"
                return@withContext SimResult(
                    "Would fail: $why",
                    gas,
                    emptyList(),
                    emptyList(),
                    emptyList()
                )
            }

            val user = plan.from.lowercase()
            val deltas = LinkedHashMap<String, BigInteger>()
            val nfts = mutableListOf<String>()
            val approvalsRaw = mutableListOf<Triple<String, String, BigInteger>>()
            val logs = res.optJSONArray("logs") ?: JSONArray()
            for (i in 0 until logs.length()) {
                val l = logs.getJSONObject(i)
                val topics = l.optJSONArray("topics") ?: continue
                if (topics.length() == 0) continue
                val t0 = topics.getString(0).lowercase()
                val token = l.optString("address").lowercase()
                if (t0 == TRANSFER && topics.length() == 3) {
                    val from = addrOf(topics.getString(1))
                    val to = addrOf(topics.getString(2))
                    val v = num(l.optString("data", "0x"))
                    if (from == user) deltas[token] = (deltas[token] ?: BigInteger.ZERO) - v
                    if (to == user) deltas[token] = (deltas[token] ?: BigInteger.ZERO) + v
                } else if (t0 == TRANSFER && topics.length() == 4) {
                    val from = addrOf(topics.getString(1))
                    val to = addrOf(topics.getString(2))
                    val id = num(topics.getString(3))
                    val short = shortAddress(Keys.toChecksumAddress(token))
                    if (from == user) nfts.add("−NFT #$id ($short)")
                    if (to == user) nfts.add("+NFT #$id ($short)")
                } else if (t0 == APPROVAL && topics.length() == 3 && addrOf(topics.getString(1)) == user) {
                    approvalsRaw.add(
                        Triple(
                            token,
                            addrOf(topics.getString(2)),
                            num(l.optString("data", "0x"))
                        )
                    )
                }
            }
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
            val approvals = approvalsRaw.map { (addr, spender, v) ->
                val t = info(addr)
                val dec = t?.decimals ?: 18
                SimApproval(
                    t?.symbol ?: shortAddress(Keys.toChecksumAddress(addr)),
                    shortAddress(Keys.toChecksumAddress(spender)),
                    shortAmount(v, dec),
                    v >= TWO_255
                )
            }
            SimResult(null, gas, lines.sortedBy { it.incoming }, nfts, approvals)
        }
}
