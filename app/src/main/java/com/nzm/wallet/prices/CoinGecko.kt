package com.nzm.wallet.prices

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object CoinGecko {
    private const val BASE = "https://api.coingecko.com/api/v3"

    data class Platform(val id: String, val chainId: Long?, val nativeCoinId: String?)

    private suspend fun get(path: String, key: String): String = withContext(Dispatchers.IO) {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("accept", "application/json")
            conn.setRequestProperty("x-cg-demo-api-key", key)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(describe(code))
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        401, 403 -> "CoinGecko rejected the API key (HTTP $code)"
        429 -> "CoinGecko rate limit reached (HTTP 429)"
        else -> "CoinGecko request failed (HTTP $code)"
    }

    suspend fun platforms(key: String): List<Platform> {
        val arr = JSONArray(get("/asset_platforms", key))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Platform(
                id = o.getString("id"),
                chainId = if (o.isNull("chain_identifier")) null else o.getLong("chain_identifier"),
                nativeCoinId = if (o.isNull("native_coin_id")) null else o.getString("native_coin_id")
            )
        }
    }

    suspend fun nativePrice(key: String, coinId: String): Double? {
        val o = JSONObject(get("/simple/price?ids=${Uri.encode(coinId)}&vs_currencies=usd", key))
        return o.optJSONObject(coinId)?.optDouble("usd")?.takeIf { !it.isNaN() }
    }

    /** Result keys are lowercase contract addresses. Tokens CoinGecko does not know are absent. */
    suspend fun tokenPrices(
        key: String,
        platformId: String,
        addresses: List<String>
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (batch in addresses.chunked(100)) {
            val path = "/simple/token_price/${Uri.encode(platformId)}" +
                    "?contract_addresses=${batch.joinToString(",")}&vs_currencies=usd"
            val o = JSONObject(get(path, key))
            o.keys().forEach { k ->
                o.optJSONObject(k)?.optDouble("usd")?.takeIf { !it.isNaN() }
                    ?.let { result[k.lowercase()] = it }
            }
        }
        return result
    }
}
