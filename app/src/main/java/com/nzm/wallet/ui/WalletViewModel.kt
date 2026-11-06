package com.nzm.wallet.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.AppDatabase
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.ChainRepository
import com.nzm.wallet.dbs.Contact
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.dbs.TokenRepository
import com.nzm.wallet.dbs.TxRecord
import com.nzm.wallet.dbs.WalletRow
import com.nzm.wallet.dbs.syncWithVault
import com.nzm.wallet.dbs.txRecordOf
import com.nzm.wallet.prices.PriceRepository
import com.nzm.wallet.prices.PriceSnapshot
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.utils.Helpers.formatUnits
import com.nzm.wallet.utils.Helpers.getPrefs
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.BigInteger
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
    private val prefs = ctx.getPrefs("wallet_prefs")
    val chainRepo = ChainRepository(ctx)
    val tokenRepo = TokenRepository(ctx)

    /** Address of the active wallet. Set after unlock, null while locked. */
    var address by mutableStateOf<String?>(null)

    // ---- wallets ----
    private val walletDao = AppDatabase.get(ctx).walletDao()

    /** Public info of every wallet (name, address). Safe to show while unlocked. */
    var wallets by mutableStateOf<List<WalletRow>>(emptyList())
        private set

    /** Id of the active wallet. Kept while locked, so unlock opens the same wallet. */
    var activeWalletId by mutableStateOf<String?>(null)
        private set
    val activeWallet: WalletRow? get() = wallets.firstOrNull { it.id == activeWalletId }

    /** UI sets these. WalletGate shows the add flow / delete confirm. */
    var addWalletRequest by mutableStateOf(false)
    var deleteWalletRequest by mutableStateOf<String?>(null)

    /** wc: link received from outside the app. Paired once unlocked and WalletConnect is ready. */
    var pendingWcUri by mutableStateOf<String?>(null)

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
    var backgroundedAt = 0L
    var hideBalances by mutableStateOf(prefs.getBoolean("hide_balances", false))
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

    private val contactDao = AppDatabase.get(ctx).contactDao()
    var contacts by mutableStateOf<List<Contact>>(emptyList())
        private set

    private val activityDao = AppDatabase.get(ctx).activityDao()
    private var allHistory by mutableStateOf<List<TxRecord>>(emptyList())

    /** Only the active wallet's transactions. */
    val history: List<TxRecord>
        get() {
            val a = address ?: return emptyList()
            return allHistory.filter { it.sender.equals(a, ignoreCase = true) }
        }
    var historyRefreshing by mutableStateOf(false)
        private set

    @Volatile
    private var nextDapp: String? = null

    @Volatile
    private var nextNote: String? = null

    fun refreshEns() {
        val a = address
        val rpc = ensRpc
        ensJob?.cancel()
        if (a == null || rpc == null) {
            ensName = null; return
        }
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

    fun toggleHideBalances() {
        hideBalances = !hideBalances
        prefs.edit { putBoolean("hide_balances", hideBalances) }
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
        viewModelScope.launch { contactDao.observe().collect { contacts = it } }
        viewModelScope.launch { activityDao.observeAll().collect { allHistory = it } }
        viewModelScope.launch { walletDao.observe().collect { wallets = it } }
        SendTx.onBroadcast = { plan, hash ->
            val d = nextDapp
            val n = nextNote
            nextDapp = null
            nextNote = null
            recordTx(txRecordOf(plan, hash, d, n))
        }
    }

    // ---- wallet management ----
    /** Call after a successful unlock of wallet [id]. Fills the wallet list, stores its address. */
    suspend fun onUnlocked(vault: Vault, id: String, addr: String) {
        walletDao.syncWithVault(vault.ids())
        walletDao.setAddress(id, addr)
        activeWalletId = id
        address = addr
    }

    fun hasAddress(addr: String) = wallets.any { it.address.equals(addr, ignoreCase = true) }
    fun hasSentTo(addr: String) = allHistory.any { it.recipient.equals(addr, ignoreCase = true) }

    /** After Vault.save of a new wallet: list it, make it the active one, show it. */
    suspend fun addWallet(vault: Vault, id: String, addr: String) {
        walletDao.syncWithVault(vault.ids())
        walletDao.setAddress(id, addr)
        vault.activeId = id
        showWallet(id, addr)
    }

    /** Switch without biometrics. Signing still asks for it. */
    fun switchWallet(vault: Vault, id: String) {
        if (id == activeWalletId || address == null) return
        val row = wallets.firstOrNull { it.id == id } ?: return
        if (row.address.isBlank() || !vault.exists(id)) return
        vault.activeId = id
        showWallet(id, row.address)
    }

    private fun showWallet(id: String, addr: String) {
        refreshJob?.cancel()
        loading = false
        ensJob?.cancel()
        ensName = null
        activeWalletId = id
        address = addr
        resetBalances()
    }

    fun renameWallet(id: String, name: String) {
        val n = name.trim().take(24)
        if (n.isNotEmpty()) viewModelScope.launch { walletDao.rename(id, n) }
    }

    /** Drops the list entry. Vault.delete(id) is done by the caller. */
    suspend fun forgetWallet(id: String) {
        walletDao.delete(id)
    }

    /** Wipe all. */
    suspend fun wipeWallets() {
        walletDao.deleteAll()
        activeWalletId = null
    }

    // ---- chain, session ----
    fun selectChain(id: Long) {
        if (id == selected?.chainId) return
        selectedId = id
        prefs.edit { putLong("chain_id", id) }
        resetBalances()
    }

    fun saveContact(c: Contact) {
        viewModelScope.launch { contactDao.upsert(c) }
    }

    fun deleteContact(address: String) {
        viewModelScope.launch { contactDao.delete(address) }
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
        addWalletRequest = false
        deleteWalletRequest = null
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
        raw?.let { formatUnits(it, decimals, 6) } ?: "—"

    private fun loadSnapshot(chainId: Long) {
        if (!snapshots.containsKey(chainId)) snapshots =
            snapshots + (chainId to priceRepo.snapshot(chainId))
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

    /** Call right before signing a site's transaction so the history shows where it came from. */
    fun annotateNext(dapp: String?, note: String?) {
        nextDapp = dapp; nextNote = note
    }

    private fun recordTx(r: TxRecord) {
        viewModelScope.launch { activityDao.upsert(r) }
        val chain = chains.firstOrNull { it.chainId == r.chainId } ?: return
        viewModelScope.launch {
            val ok = try {
                SendTx.waitForReceipt(chain.rpcUrl, r.hash, 10 * 60_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (ok != null) activityDao.setStatus(r.chainId, r.hash, if (ok) 1 else 2)
        }
    }

    /** Re-checks every pending transaction, of every wallet, on whatever chain it was sent. */
    fun refreshHistory() {
        viewModelScope.launch {
            historyRefreshing = true
            try {
                for ((chainId, hash) in activityDao.pending()) {
                    val chain = chains.firstOrNull { it.chainId == chainId } ?: continue
                    val ok = try {
                        SendTx.receiptStatus(chain.rpcUrl, hash)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (ok != null) activityDao.setStatus(chainId, hash, if (ok) 1 else 2)
                }
            } finally {
                historyRefreshing = false
            }
        }
    }

    fun removeTx(chainId: Long, hash: String) {
        viewModelScope.launch { activityDao.delete(chainId, hash) }
    }

    fun clearHistory() {
        viewModelScope.launch { activityDao.deleteAll() }
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
        val price = (if (token == null) snap.native else snap.tokens[token.address.lowercase()])
            ?: return null
        val decimals = token?.decimals ?: chain.decimals
        val value = BigDecimal(raw).movePointLeft(decimals) * BigDecimal.valueOf(price)
        return when {
            value.signum() == 0 -> $$"$0.00"
            value < BigDecimal("0.01") -> $$"<$0.01"
            else -> usd.format(value)
        }
    }
}
