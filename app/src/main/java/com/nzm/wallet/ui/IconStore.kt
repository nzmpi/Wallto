package com.nzm.wallet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.Token
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.crypto.Keys
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Logos from Trust Wallet's public assets repo, for mainnets only.
 * Each logo is downloaded once, kept in app storage and reused until the chain or token is removed.
 * A 404 is remembered, so nothing is asked twice. Nothing is sent but the file path (chain and token address).
 */
object IconStore {
    private const val BASE = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains"
    private const val MAX_BYTES = 400_000
    private const val MAX_SIDE = 192

    private val FOLDERS = mapOf(
        1L to "ethereum", 56L to "smartchain", 137L to "polygon", 42161L to "arbitrum", 10L to "optimism",
        8453L to "base", 43114L to "avalanchec", 100L to "xdai", 250L to "fantom", 42220L to "celo",
        59144L to "linea", 324L to "zksync", 534352L to "scroll", 5000L to "mantle", 81457L to "blast",
        25L to "cronos", 1284L to "moonbeam", 1285L to "moonriver", 1313161554L to "aurora",
        1088L to "metis", 1101L to "polygonzkevm", 204L to "opbnb", 288L to "boba", 2222L to "kava",
        169L to "manta", 146L to "sonic", 1329L to "sei", 8217L to "klaytn", 1666600000L to "harmony",
        66L to "okc", 9001L to "evmos", 321L to "kcc", 4689L to "iotexevm", 88L to "tomochain",
        820L to "callisto", 61L to "classic", 2020L to "ronin"
    )

    private var dir: File? = null
    private val mem = LruCache<String, ImageBitmap>(96)
    private val failed = ConcurrentHashMap.newKeySet<String>()   // network errors: retry next launch
    private val lock = Mutex()

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.noBackupFilesDir, "icons").also { it.mkdirs() }
    }

    private fun chainKey(id: Long) = "c$id"
    private fun nativeKey(id: Long) = "n$id"
    private fun tokenKey(chainId: Long, address: String) = "t${chainId}_${address.lowercase()}"

    suspend fun chain(chain: Chain): ImageBitmap? {
        if (chain.isTestnet) return null
        val f = FOLDERS[chain.chainId] ?: return null
        return load(chainKey(chain.chainId), "$BASE/$f/info/logo.png")
    }

    /** The native coin: ETH on an L2 shows the Ethereum logo, other coins show the chain logo. */
    suspend fun native(chain: Chain): ImageBitmap? {
        if (chain.isTestnet) return null
        val f = FOLDERS[chain.chainId] ?: return null
        if (chain.symbol == "ETH" && f != "ethereum") return load(nativeKey(chain.chainId), "$BASE/ethereum/info/logo.png")
        return chain(chain)
    }

    suspend fun token(chain: Chain, token: Token): ImageBitmap? {
        if (chain.isTestnet) return null
        val f = FOLDERS[chain.chainId] ?: return null
        val addr = try { Keys.toChecksumAddress(token.address) } catch (_: Exception) { return null }
        return load(tokenKey(chain.chainId, token.address), "$BASE/$f/assets/$addr/logo.png")
    }

    fun forgetChain(chainId: Long) {
        val d = dir ?: return
        d.listFiles()?.filter {
            it.name.startsWith(chainKey(chainId) + ".") || it.name.startsWith(nativeKey(chainId) + ".") ||
                    it.name.startsWith("t${chainId}_")
        }?.forEach { it.delete() }
        mem.evictAll()
        failed.clear()
    }

    fun forgetToken(chainId: Long, address: String) {
        val k = tokenKey(chainId, address)
        File(dir ?: return, "$k.png").delete()
        File(dir ?: return, "$k.none").delete()
        mem.remove(k)
        failed.remove(k)
    }

    private fun decode(f: File): ImageBitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        return bmp?.asImageBitmap()
    }

    private suspend fun load(key: String, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val d = dir ?: return@withContext null
        mem.get(key)?.let { return@withContext it }
        val png = File(d, "$key.png")
        val none = File(d, "$key.none")
        if (!png.exists()) {
            if (none.exists() || key in failed) return@withContext null
            lock.withLock {
                if (png.exists() || none.exists()) return@withLock
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8_000
                    c.readTimeout = 10_000
                    when (c.responseCode) {
                        404 -> { none.createNewFile(); return@withLock }
                        200 -> {}
                        else -> { failed.add(key); return@withLock }
                    }
                    val buf = ByteArrayOutputStream()
                    c.inputStream.use { ins ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = ins.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > MAX_BYTES) { none.createNewFile(); return@withLock }
                        }
                    }
                    val tmp = File(d, "$key.tmp")
                    tmp.writeBytes(buf.toByteArray())
                    if (decode(tmp) == null) { tmp.delete(); none.createNewFile() }
                    else tmp.renameTo(png)
                } catch (_: Exception) {
                    failed.add(key)
                } finally {
                    c.disconnect()
                }
            }
        }
        if (!png.exists()) return@withContext null
        val bmp = decode(png) ?: run { png.delete(); return@withContext null }
        mem.put(key, bmp)
        bmp
    }
}

@Composable
private fun IconCircle(bmp: ImageBitmap?, letter: String, size: Dp, modifier: Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(Dim.Avatar),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(letter, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, color = Dim.Ink)
        }
    }
}

/** Token logo, or the chain's coin when [token] is null. Falls back to the letter. Testnets always show the letter. */
@SuppressLint("RememberReturnType")
@Composable
fun AssetIcon(chain: Chain?, token: Token?, size: Dp, letter: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { IconStore.init(ctx) }
    val bmp by produceState<ImageBitmap?>(null, chain?.chainId, chain?.isTestnet, token?.address) {
        value = when {
            chain == null || chain.isTestnet -> null
            token == null -> IconStore.native(chain)
            else -> IconStore.token(chain, token)
        }
    }
    IconCircle(bmp, letter, size, modifier)
}

/** The chain's own logo. Falls back to the first letter of its name. */
@SuppressLint("RememberReturnType")
@Composable
fun ChainIcon(chain: Chain, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { IconStore.init(ctx) }
    val bmp by produceState<ImageBitmap?>(null, chain.chainId, chain.isTestnet) { value = IconStore.chain(chain) }
    IconCircle(bmp, chain.name.take(1).uppercase(), size, modifier)
}
