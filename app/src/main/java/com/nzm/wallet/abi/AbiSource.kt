package com.nzm.wallet.abi

import com.nzm.wallet.ui.shortAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A callable function taken from an ABI. [sigText] is what AbiCodec.parseSignature reads. */
class AbiFn(
    val name: String,
    val sigText: String,
    val readOnly: Boolean,
    val payable: Boolean,
    val inputNames: List<String>,
    val inputTypes: List<String>
)

class AbiFns(val list: List<AbiFn>, val skipped: Int)

private fun typeOf(o: JSONObject): String {
    val t = o.getString("type")
    if (!t.startsWith("tuple")) return t
    val comps = o.optJSONArray("components") ?: JSONArray()
    val inner = (0 until comps.length()).joinToString(",") { typeOf(comps.getJSONObject(it)) }
    return "($inner)" + t.removePrefix("tuple")
}

/** Functions the wallet can encode. Ones with types AbiCodec cannot parse are counted in [AbiFns.skipped]. */
fun abiFunctions(abiJson: String): AbiFns {
    val arr = try { JSONArray(abiJson) } catch (_: Exception) { return AbiFns(emptyList(), 0) }
    val out = LinkedHashMap<String, AbiFn>()
    var skipped = 0
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("type") != "function") continue
        try {
            val name = o.getString("name")
            val ins = o.optJSONArray("inputs") ?: JSONArray()
            val outs = o.optJSONArray("outputs") ?: JSONArray()
            val inTypes = (0 until ins.length()).map { typeOf(ins.getJSONObject(it)) }
            val inNames = (0 until ins.length()).map { ins.getJSONObject(it).optString("name", "") }
            val outTypes = (0 until outs.length()).map { typeOf(outs.getJSONObject(it)) }
            val sig = "$name(${inTypes.joinToString(",")})" +
                    if (outTypes.isEmpty()) "" else " returns (${outTypes.joinToString(",")})"
            val mut = o.optString("stateMutability", if (o.optBoolean("constant")) "view" else "nonpayable")
            if (runCatching { AbiCodec.parseSignature(sig) }.isFailure) { skipped++; continue }
            out.putIfAbsent(sig, AbiFn(name, sig, mut == "view" || mut == "pure", mut == "payable", inNames, inTypes))
        } catch (_: Exception) {
            skipped++
        }
    }
    return AbiFns(out.values.sortedBy { it.name.lowercase() }, skipped)
}

class AbiResult(val abi: String, val note: String?)

object Sourcify {
    private const val BASE = "https://sourcify.dev/server/v2/contract"
    private const val MAX_BYTES = 4_000_000

    private class Hit(val abi: String?, val implementation: String?)

    private fun get(chainId: Long, address: String): Hit? {
        val c = URL("$BASE/$chainId/$address?fields=abi,proxyResolution").openConnection() as HttpURLConnection
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
                pr.optJSONArray("implementations")?.optJSONObject(0)?.optString("address")?.ifBlank { null }
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
            val impl = try { get(chainId, hit.implementation) } catch (_: Exception) { null }
            if (impl?.abi != null) {
                return@withContext AbiResult(impl.abi, "Proxy. Using the ABI of implementation ${
                    shortAddress(
                        hit.implementation
                    )
                }.")
            }
            if (hit.abi != null) {
                return@withContext AbiResult(hit.abi, "Proxy. Implementation ${shortAddress(hit.implementation)} is not verified, so this is the proxy's own ABI.")
            }
        }
        AbiResult(hit.abi ?: throw IllegalStateException("No ABI returned"), null)
    }
}
