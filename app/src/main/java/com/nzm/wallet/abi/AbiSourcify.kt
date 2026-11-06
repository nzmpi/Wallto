package com.nzm.wallet.abi

import com.nzm.wallet.utils.Helpers.sanitize
import com.nzm.wallet.utils.Helpers.shortAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class AbiResult(val abi: String, val note: String?)

object Sourcify {
    private const val BASE = "https://sourcify.dev/server/v2/contract"
    private const val MAX_BYTES = 4_000_000

    private class Hit(val abi: String?, val implementation: String?)

    private fun get(chainId: Long, address: String): Hit? {
        val c =
            URL("$BASE/$chainId/$address?fields=abi,proxyResolution").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            when (val code = c.responseCode) {
                404 -> return null
                200 -> {}
                else -> throw IllegalStateException("Sourcify error $code")
            }
            val buf = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    check(buf.size() <= MAX_BYTES) { "ABI is too large" }
                }
            }
            val bytes = buf.toByteArray()
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            val abi = j.optJSONArray("abi")?.toString()
            val pr = j.optJSONObject("proxyResolution")
            val impl = if (pr?.optBoolean("isProxy") == true)
                pr.optJSONArray("implementations")?.optJSONObject(0)?.optString("address")
                    ?.ifBlank { null }
            else null
            return Hit(abi, impl)
        } finally {
            c.disconnect()
        }
    }

    /** For a proxy, returns the implementation's ABI when it is verified. Calls still go to the proxy address. */
    suspend fun fetch(chainId: Long, address: String): AbiResult = withContext(Dispatchers.IO) {
        val hit = get(chainId, address) ?: throw IllegalStateException("Not verified on Sourcify")
        if (hit.implementation != null) {
            val impl = try {
                get(chainId, hit.implementation)
            } catch (_: Exception) {
                null
            }
            if (impl?.abi != null) {
                return@withContext AbiResult(
                    impl.abi, "Proxy. Using the ABI of implementation ${
                        shortAddress(
                            hit.implementation
                        )
                    }."
                )
            }
            if (hit.abi != null) {
                return@withContext AbiResult(
                    hit.abi,
                    "Proxy. Implementation ${shortAddress(hit.implementation)} is not verified, so this is the proxy's own ABI."
                )
            }
        }
        AbiResult(hit.abi ?: throw IllegalStateException("No ABI returned"), null)
    }
}

object SelectorLookup {
    class Guess(
        val signature: String,
        val args: List<Pair<String, String>>?,
        val others: Int,
        val risks: List<String>
    )

    private class Match(val signature: String, val others: Int)
    private class Entry(val match: Match?)

    private const val BASE = "https://api.4byte.sourcify.dev/signature-database/v1/lookup"
    private const val MAX_BYTES = 200_000
    private val NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*\\(.*\\)")
    private val cache = ConcurrentHashMap<String, Entry>()

    private fun matchesSelector(name: String, selector: String): Boolean =
        name.length <= 400 && NAME.matches(name) &&
                Numeric.toHexStringNoPrefix(
                    Hash.sha3(name.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
                ) == selector

    private fun fetch(selector: String): Entry {
        val c = URL("$BASE?function=0x$selector&filter=true").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            check(code == 200) { "Signature lookup error $code" }
            val buf = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    check(buf.size() <= MAX_BYTES) { "Signature response too large" }
                }
            }
            val arr = JSONObject(String(buf.toByteArray(), Charsets.UTF_8))
                .optJSONObject("result")?.optJSONObject("function")?.optJSONArray("0x$selector")
                ?: return Entry(null)
            val good = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { !it.optBoolean("filtered", false) }
                .filter { matchesSelector(it.optString("name"), selector) }
                .sortedByDescending { it.optBoolean("hasVerifiedContract", false) }
            if (good.isEmpty()) return Entry(null)
            return Entry(Match(good[0].getString("name"), good.size - 1))
        } finally {
            c.disconnect()
        }
    }

    /** Best guess for the function called by [dataHex] (0x + selector + args). Throws on network errors. */
    suspend fun guess(dataHex: String): Guess? = withContext(Dispatchers.IO) {
        val d = dataHex.removePrefix("0x").lowercase()
        if (d.length < 8 || !d.all { it in '0'..'9' || it in 'a'..'f' }) return@withContext null
        val selector = d.take(8)
        val entry = cache[selector] ?: fetch(selector).also { cache[selector] = it }
        val m = entry.match ?: return@withContext null

        var args: List<Pair<String, String>>? = null
        var risks: List<String> = emptyList()
        try {
            val sig = AbiCodec.parseSignature(m.signature)
            val body = Numeric.hexStringToByteArray(d.drop(8))
            val vals = AbiCodec.decodeOutputs(sig.inputs, body)
            args = vals.mapIndexed { i, v -> sig.inputs[i].canonical to v.sanitize(300) }
            risks = try {
                AbiCodec.riskNotes(
                    sig,
                    sig.inputs.mapIndexed { i, t -> AbiCodec.parseValue(t, vals[i]) })
            } catch (_: Exception) {
                emptyList()
            }
        } catch (_: Exception) {
        }
        Guess(m.signature, args, m.others, risks)
    }
}
