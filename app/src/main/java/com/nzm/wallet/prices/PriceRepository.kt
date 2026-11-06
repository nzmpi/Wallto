package com.nzm.wallet.prices

import android.content.Context
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.core.content.edit
import com.nzm.wallet.storage.PriceKeyStore
import com.nzm.wallet.utils.Helpers.getPrefs

data class PriceSnapshot(val at: Long, val native: Double?, val tokens: Map<String, Double>) {
    fun isFromToday(): Boolean =
        at > 0 && Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .toLocalDate() == LocalDate.now()

    companion object {
        val EMPTY = PriceSnapshot(0L, null, emptyMap())
    }
}

class PriceRepository(context: Context) {
    private val prefs = context.getPrefs("price_prefs")
    val keyStore = PriceKeyStore(context)

    fun snapshot(chainId: Long): PriceSnapshot {
        val raw = prefs.getString("snap_$chainId", null) ?: return PriceSnapshot.EMPTY
        return try {
            val o = JSONObject(raw)
            val t = o.getJSONObject("tokens")
            PriceSnapshot(
                at = o.getLong("at"),
                native = if (o.isNull("native")) null else o.getDouble("native"),
                tokens = t.keys().asSequence().associateWith { t.getDouble(it) }
            )
        } catch (_: Exception) {
            PriceSnapshot.EMPTY
        }
    }

    private fun save(chainId: Long, s: PriceSnapshot) {
        val o = JSONObject()
            .put("at", s.at)
            .put("native", s.native ?: JSONObject.NULL)
            .put("tokens", JSONObject(s.tokens))
        prefs.edit { putString("snap_$chainId", o.toString()) }
    }

    fun clearSnapshots() {
        prefs.edit {
            prefs.all.keys.filter { it.startsWith("snap_") }.forEach { remove(it) }
        }
    }

    private fun loadPlatforms(): List<CoinGecko.Platform>? {
        val raw = prefs.getString("platforms", null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoinGecko.Platform(
                    o.getString("id"),
                    o.getLong("chainId"),
                    if (o.isNull("native")) null else o.getString("native")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun savePlatforms(list: List<CoinGecko.Platform>) {
        val arr = JSONArray()
        list.filter { it.chainId != null }.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("chainId", it.chainId)
                    .put("native", it.nativeCoinId ?: JSONObject.NULL)
            )
        }
        prefs.edit {
            putString("platforms", arr.toString()).putLong(
                "platforms_at",
                System.currentTimeMillis()
            )
        }
    }

    /** Platform list is cached for a day. It maps EVM chain IDs to CoinGecko platform IDs. */
    private suspend fun platformFor(key: String, chainId: Long): CoinGecko.Platform? {
        val fresh = System.currentTimeMillis() - prefs.getLong("platforms_at", 0L) < DAY_MS
        var list = if (fresh) loadPlatforms() else null
        if (list == null) {
            list = CoinGecko.platforms(key)
            savePlatforms(list)
        }
        return list.firstOrNull { it.chainId == chainId }
    }

    /**
     * Fetches native and token prices for one chain: 1 call for the native coin, 1 for all tokens,
     * plus 1 for the platform list once a day. Throws only if every call failed, so old prices stay.
     */
    suspend fun fetch(key: String, chain: Chain, tokens: List<Token>): PriceSnapshot {
        val old = snapshot(chain.chainId)
        val platform = platformFor(key, chain.chainId)
            ?: // chain unknown to CoinGecko: remember the attempt so it is not retried before tomorrow
            return PriceSnapshot(
                System.currentTimeMillis(),
                null,
                emptyMap()
            ).also { save(chain.chainId, it) }
        var native = old.native
        val merged = old.tokens.toMutableMap()
        var failure: Exception? = null
        var succeeded = 0

        if (platform.nativeCoinId != null) {
            try {
                CoinGecko.nativePrice(key, platform.nativeCoinId)?.let { native = it }
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (tokens.isNotEmpty()) {
            try {
                merged.putAll(CoinGecko.tokenPrices(key, platform.id, tokens.map { it.address }))
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        val f = failure
        if (succeeded == 0 && f != null) throw (f as? IOException ?: IOException(
            f.message ?: "Price request failed"
        ))

        return PriceSnapshot(System.currentTimeMillis(), native, merged).also {
            save(
                chain.chainId,
                it
            )
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
