package com.nzm.wallet.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.ChainRepository
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.Token
import com.nzm.wallet.chain.TokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.math.BigInteger
import androidx.core.content.edit
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.prices.PriceRepository
import com.nzm.wallet.prices.PriceSnapshot
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.Dispatchers
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModel(app: Application) : AndroidViewModel(app) {
    init {
        WcService.start(app)
        WcService.provider = object : WcService.Provider {
            override fun chains() = chains.toList()
            override fun address() = address
            override fun switchTo(chain: Chain) {
                viewModelScope.launch(Dispatchers.Main) { selectChain(chain.chainId) }
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
    val chainRepo = ChainRepository(ctx)
    val tokenRepo = TokenRepository(ctx)

    /** Set after unlock, null while locked. */
    var address by mutableStateOf<String?>(null)

    var chains by mutableStateOf<List<Chain>>(emptyList())
        private set
    var tokens by mutableStateOf<List<Token>>(emptyList())
        private set

    private var selectedId by mutableLongStateOf(prefs.getLong("chain_id", 11155111L))
    val selected: Chain?
        get() = chains.firstOrNull { it.chainId == selectedId } ?: chains.firstOrNull()

    var nativeBalance by mutableStateOf<BigInteger?>(null)
        private set

    /** Key present with null value = lookup failed. Key absent = still loading. */
    var tokenBalances by mutableStateOf<Map<String, BigInteger?>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var balanceError by mutableStateOf<String?>(null)
        private set
    var lockAfterMs by mutableLongStateOf(prefs.getLong("lock_after_ms", 60_000L))
        private set

    private var refreshJob: Job? = null
    private var refreshSeq = 0
    private var shownChainId: Long? = null

    private val priceRepo = PriceRepository(ctx)
    private var priceKey: String? = priceRepo.keyStore.get()
    var priceKeySet by mutableStateOf(priceKey != null)
        private set
    var priceError by mutableStateOf<String?>(null)
        private set
    private var snapshots by mutableStateOf<Map<Long, PriceSnapshot>>(emptyMap())
    private var priceJob: Job? = null
    private val usd = NumberFormat.getCurrencyInstance(Locale.US)

    var ensName by mutableStateOf<String?>(null)
        private set
    val ensRpc: String? get() = chains.firstOrNull { it.chainId == 1L }?.rpcUrl
    private var ensJob: Job? = null

    fun refreshEns() {
        val a = address
        val rpc = ensRpc
        ensJob?.cancel()
        if (a == null || rpc == null) { ensName = null; return }
        ensJob = viewModelScope.launch {
            try {
                ensName = Ens.reverse(rpc, a)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // network problem: keep what is shown
            }
        }
    }

    init {
        viewModelScope.launch { chainRepo.seedDefaults() }
        viewModelScope.launch { chainRepo.chains.collect { chains = it } }
        viewModelScope.launch {
            snapshotFlow { selected?.chainId }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else tokenRepo.observe(id) }
                .collect { tokens = it }
        }
    }

    fun selectChain(id: Long) {
        if (id == selected?.chainId) return
        selectedId = id
        prefs.edit { putLong("chain_id", id) }
        resetBalances()
    }

    fun setLockAfter(ms: Long) {
        lockAfterMs = ms
        prefs.edit { putLong("lock_after_ms", ms) }
    }

    fun clearSession() {
        address = null
        refreshJob?.cancel()
        loading = false
        resetBalances()
        ensName = null
    }

    private fun resetBalances() {
        nativeBalance = null
        tokenBalances = emptyMap()
        balanceError = null
        shownChainId = selected?.chainId
    }

    fun refresh(force: Boolean = false) {
        val chain = selected ?: return
        val owner = address ?: return
        if (shownChainId != chain.chainId) resetBalances()
        loadSnapshot(chain.chainId)
        val toks = tokens
        refreshPrices(chain, toks, force)
        val turn = ++refreshSeq
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading = true
            balanceError = null
            try {
                coroutineScope {
                    val native = async {
                        try {
                            Result.success(Rpc.balance(chain.rpcUrl, owner))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    val perToken = toks.map { t ->
                        async {
                            t.address to try {
                                Erc20.balanceOf(chain.rpcUrl, t.address, owner)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    native.await().fold(
                        onSuccess = { nativeBalance = it },
                        onFailure = {
                            nativeBalance = null
                            balanceError = it.message ?: "RPC not reachable"
                        }
                    )
                    tokenBalances = perToken.awaitAll().toMap()
                }
            } finally {
                if (turn == refreshSeq) loading = false
            }
        }
    }

    fun amountText(raw: BigInteger?, decimals: Int): String =
        raw?.let { Rpc.formatUnits(it, decimals, 6) } ?: "—"

    private fun loadSnapshot(chainId: Long) {
        if (!snapshots.containsKey(chainId)) snapshots = snapshots + (chainId to priceRepo.snapshot(chainId))
    }

    /** Auto: once per day per chain. Forced: refresh button. Testnets and no key: no calls. */
    private fun refreshPrices(chain: Chain, toks: List<Token>, force: Boolean) {
        val key = priceKey ?: return
        if (chain.isTestnet) return
        if (!force) {
            if (priceJob?.isActive == true) return
            if ((snapshots[chain.chainId] ?: PriceSnapshot.EMPTY).isFromToday()) return
        }
        priceJob?.cancel()
        priceJob = viewModelScope.launch {
            try {
                val s = priceRepo.fetch(key, chain, toks)
                snapshots = snapshots + (chain.chainId to s)
                priceError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                priceError = e.message ?: "Price request failed"
            }
        }
    }

    fun setPriceKey(key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        priceRepo.keyStore.set(k)
        priceKey = k
        priceKeySet = k != null
        priceError = null
        if (k == null) {
            priceJob?.cancel()
            priceRepo.clearSnapshots()
            snapshots = emptyMap()
        } else {
            refresh(force = true)
        }
    }

    fun fiat(token: Token?, raw: BigInteger?): String? {
        val chain = selected ?: return null
        if (raw == null) return null
        val snap = snapshots[chain.chainId] ?: return null
        val price = (if (token == null) snap.native else snap.tokens[token.address.lowercase()]) ?: return null
        val decimals = token?.decimals ?: chain.decimals
        val value = BigDecimal(raw).movePointLeft(decimals) * BigDecimal.valueOf(price)
        return when {
            value.signum() == 0 -> $$"$0.00"
            value < BigDecimal("0.01") -> $$"<$0.01"
            else -> usd.format(value)
        }
    }
}
