
package com.nzm.wallet.wc

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.nzm.wallet.chain.Chain
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.walletkit.client.Wallet
import com.reown.walletkit.client.WalletKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.core.content.edit
import com.nzm.wallet.ui.Batch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

object WcService {
    const val PERSONAL_SIGN = "personal_sign"
    const val TYPED_V4 = "eth_signTypedData_v4"
    const val SEND_TX = "eth_sendTransaction"
    const val SWITCH_CHAIN = "wallet_switchEthereumChain"
    const val SEND_CALLS = "wallet_sendCalls"
    const val GET_CALLS_STATUS = "wallet_getCallsStatus"
    const val GET_CAPABILITIES = "wallet_getCapabilities"
    private val METHODS = listOf(PERSONAL_SIGN, TYPED_V4, SEND_TX, SWITCH_CHAIN, SEND_CALLS, GET_CALLS_STATUS, GET_CAPABILITIES)
    private val EVENTS = listOf("chainChanged", "accountsChanged")

    data class Proposal(val proposal: Wallet.Model.SessionProposal, val verify: Wallet.Model.VerifyContext)
    data class Request(val request: Wallet.Model.SessionRequest, val verify: Wallet.Model.VerifyContext)

    private val _proposals = MutableStateFlow<List<Proposal>>(emptyList())
    val proposals: StateFlow<List<Proposal>> = _proposals.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val _sessions = MutableStateFlow<List<Wallet.Model.Session>>(emptyList())
    val sessions: StateFlow<List<Wallet.Model.Session>> = _sessions.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private var started = false

    interface Provider {
        fun chains(): List<Chain>
        /** Address of the ACTIVE wallet, null while locked. */
        fun address(): String?
        fun switchTo(chain: Chain)
    }
    @Volatile var provider: Provider? = null
    @Volatile private var accountPrefs: SharedPreferences? = null
    private fun cacheAccount(topic: String, address: String) {
        accountPrefs?.edit { putString(topic, address) }
    }

    private val _relayUp = MutableStateFlow<Boolean?>(null)

    private fun relayConnect() {
        if (!started || _relayUp.value == true) return
        try {
            CoreClient.Relay.connect(onError = { e: Core.Model.Error ->
                _status.value = "Relay connect: ${e.throwable.message}"
            })
        } catch (_: Exception) {
        }
    }

    fun onForeground() = relayConnect()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wc_prefs", Context.MODE_PRIVATE)

    fun projectId(ctx: Context): String? = prefs(ctx).getString("project_id", null)?.takeIf { it.isNotBlank() }

    /** SDK reads the ID once at startup, so a change applies after the app restarts. */
    fun setProjectId(ctx: Context, id: String?) {
        prefs(ctx).edit {
            if (id.isNullOrBlank()) remove("project_id") else putString("project_id", id.trim())
        }
    }

    /** The wallet address a session was approved with (taken from the session's accounts). */
    fun accountOf(session: Wallet.Model.Session): String? =
        session.namespaces["eip155"]?.accounts?.firstOrNull()?.substringAfterLast(':')

    fun sessionAddress(topic: String): String? {
        val live = try {
            WalletKit.getActiveSessionByTopic(topic)?.let { accountOf(it) }
        } catch (_: Exception) {
            null
        }
        if (live != null) cacheAccount(topic, live)
        return live ?: accountPrefs?.getString(topic, null)
    }

    fun start(app: Application) {
        if (accountPrefs == null) accountPrefs = app.getSharedPreferences("wc_accounts", Context.MODE_PRIVATE)
        if (started) return
        val projectId = projectId(app) ?: return
        started = true
        CoreClient.initialize(
            application = app,
            projectId = projectId,
            metaData = Core.Model.AppMetaData(
                name = "nzm wallet",
                description = "Personal wallet",
                url = "https://example.com",
                icons = emptyList(),
                redirect = "nzmwallet://request"
            ),
            connectionType = ConnectionType.MANUAL,
            relay = null,
            keyServerUrl = null,
            networkClientTimeout = null,
            telemetryEnabled = false,
            onError = { _status.value = "WalletConnect core failed: ${it.throwable.message}" }
        )
        WalletKit.initialize(
            Wallet.Params.Init(core = CoreClient),
            onSuccess = {
                WalletKit.setWalletDelegate(delegate)
                _ready.value = true
                refreshSessions()
                relayConnect()
            },
            onError = { _status.value = "WalletConnect init failed: ${it.throwable.message}" }
        )
    }

    fun clearStatus() { _status.value = null }

    fun pair(uri: String) {
        val u = uri.trim()
        if (!u.startsWith("wc:")) { _status.value = "Not a WalletConnect link"; return }
        if (!_ready.value) {
            _status.value = "WalletConnect is not ready. Add the Reown project ID in Settings → Connected sites, then restart the app."
            return
        }
        _status.value = null
        WalletKit.pair(
            Wallet.Params.Pair(u),
            onSuccess = { },
            onError = { _status.value = it.throwable.message ?: "Pairing failed" }
        )
    }

    fun refreshSessions() {
        _sessions.value = try {
            WalletKit.getListOfActiveSessions().filter { it.metaData != null }
        } catch (_: Exception) {
            emptyList()
        }
        _sessions.value.forEach { s -> accountOf(s)?.let { cacheAccount(s.topic, it) } }
    }

    fun disconnect(topic: String) {
        WalletKit.disconnectSession(
            Wallet.Params.SessionDisconnect(sessionTopic = topic),
            onSuccess = { refreshSessions() },
            onError = { _status.value = it.throwable.message ?: "Disconnect failed"; refreshSessions() }
        )
        accountPrefs?.edit { remove(topic) }
    }

    /** Shares the given wallet address on every chain saved in the wallet. Returns an error text or null. */
    fun approve(item: Proposal, chains: List<Chain>, address: String, done: (String?) -> Unit) {
        try {
            val ids = chains.map { "eip155:${it.chainId}" }
            val supported = mapOf(
                "eip155" to Wallet.Model.Namespace.Session(
                    chains = ids,
                    methods = METHODS,
                    events = EVENTS,
                    accounts = ids.map { "$it:$address" }
                )
            )
            val namespaces = WalletKit.generateApprovedNamespaces(item.proposal, supported)
            WalletKit.approveSession(
                Wallet.Params.SessionApprove(
                    proposerPublicKey = item.proposal.proposerPublicKey,
                    namespaces = namespaces
                ),
                onSuccess = {
                    _proposals.update { l -> l.filter { it !== item } }
                    refreshSessions()
                    done(null)
                },
                onError = { done(it.throwable.message ?: "Approve failed") }
            )
        } catch (e: Exception) {
            done(e.message ?: "Site needs chains or methods this wallet does not offer")
        }
    }

    fun reject(item: Proposal) {
        _proposals.update { l -> l.filter { it !== item } }
        WalletKit.rejectSession(
            Wallet.Params.SessionReject(
                proposerPublicKey = item.proposal.proposerPublicKey,
                reason = "User rejected"
            ),
            onSuccess = { },
            onError = { }
        )
    }

    fun respondResult(item: Request, result: String) {
        _requests.update { l -> l.filter { it !== item } }
        send(
            item.request.topic,
            Wallet.Model.JsonRpcResponse.JsonRpcResult(id = item.request.request.id, result = result)
        )
    }

    fun respondError(item: Request, code: Int = 5000, message: String = "User rejected") {
        _requests.update { l -> l.filter { it !== item } }
        errorTo(item.request.topic, item.request.request.id, code, message)
    }

    private fun errorTo(topic: String, id: Long, code: Int, message: String) =
        send(topic, Wallet.Model.JsonRpcResponse.JsonRpcError(id = id, code = code, message = message))

    private fun send(topic: String, response: Wallet.Model.JsonRpcResponse) {
        WalletKit.respondSessionRequest(
            Wallet.Params.SessionRequestResponse(sessionTopic = topic, jsonRpcResponse = response),
            onSuccess = { },
            onError = { _status.value = it.throwable.message ?: "Reply to site failed" }
        )
    }

    private fun handleSwitch(r: Wallet.Model.SessionRequest) {
        val id = r.request.id
        val topic = r.topic
        val p = provider
        val active = p?.address()
        if (p == null || active == null) { errorTo(topic, id, 4900, "Wallet unavailable"); return }
        // the wallet this site connected with, not necessarily the active one
        val address = sessionAddress(topic)
        if (address == null) { errorTo(topic, id, 4900, "No account for this session"); return }
        val cid = try {
            val s = JSONArray(r.request.params).getJSONObject(0).getString("chainId")
            if (s.startsWith("0x")) s.drop(2).toLong(16) else s.toLong()
        } catch (_: Exception) {
            errorTo(topic, id, -32602, "Invalid chainId"); return
        }
        val chains = p.chains()
        val chain = chains.firstOrNull { it.chainId == cid }
        if (chain == null) { errorTo(topic, id, 4902, "Chain not added to this wallet"); return }

        val finish = {
            // the app's chain only follows sites of the wallet you are looking at
            if (address.equals(active, ignoreCase = true)) p.switchTo(chain)
            send(topic, Wallet.Model.JsonRpcResponse.JsonRpcResult(id = id, result = "null"))
            WalletKit.emitSessionEvent(
                Wallet.Params.SessionEmit(
                    topic,
                    Wallet.Model.SessionEvent("chainChanged", cid.toString()),
                    "eip155:$cid"
                ),
                onSuccess = { },
                onError = { }
            )
        }

        val inSession = try {
            WalletKit.getActiveSessionByTopic(topic)?.namespaces?.get("eip155")
                ?.chains?.contains("eip155:$cid") == true
        } catch (_: Exception) {
            false
        }
        if (inSession) { finish(); return }

        // site did not ask for this chain at connect time: extend the session to all saved chains
        val ids = chains.map { "eip155:${it.chainId}" }
        val ns = mapOf(
            "eip155" to Wallet.Model.Namespace.Session(
                chains = ids, methods = METHODS, events = EVENTS,
                accounts = ids.map { "$it:$address" }
            )
        )
        WalletKit.updateSession(
            Wallet.Params.SessionUpdate(sessionTopic = topic, namespaces = ns),
            onSuccess = { refreshSessions(); finish() },
            onError = { errorTo(topic, id, 4901, it.throwable.message ?: "Could not add chain to session") }
        )
    }

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun saveCall(id: String, chainId: Long, hash: String) {
        accountPrefs?.edit { putString("call:$id", "$chainId:$hash") }
    }

    private fun result(r: Wallet.Model.SessionRequest, json: String) =
        send(r.topic, Wallet.Model.JsonRpcResponse.JsonRpcResult(id = r.request.id, result = json))

    /** Read-only batch methods. True if answered here, no UI. */
    private fun answerReadOnly(r: Wallet.Model.SessionRequest): Boolean {
        val chains = provider?.chains() ?: emptyList()
        when (r.request.method) {
            GET_CAPABILITIES -> bg.launch {
                try {
                    val p = JSONArray(r.request.params)
                    val acct = p.getString(0)
                    val asked = p.optJSONArray(1)?.let { a ->
                        (0 until a.length()).map { a.getString(it).removePrefix("0x").toLong(16) }
                    }
                    val out = JSONObject()
                    for (c in chains) {
                        if (asked != null && c.chainId !in asked) continue
                        val ok = try { Batch.status(c, acct).supportsBatch } catch (_: Exception) { false }
                        out.put(
                            "0x" + c.chainId.toString(16),
                            JSONObject().put("atomic", JSONObject().put("status", if (ok) "supported" else "unsupported"))
                        )
                    }
                    result(r, out.toString())
                } catch (_: Exception) {
                    errorTo(r.topic, r.request.id, -32602, "Bad params")
                }
            }
            GET_CALLS_STATUS -> bg.launch {
                try {
                    val raw = JSONArray(r.request.params).get(0)
                    val id = if (raw is JSONObject) raw.getString("id") else raw.toString()
                    val saved = accountPrefs?.getString("call:$id", null)
                        ?: return@launch errorTo(r.topic, r.request.id, 5730, "Unknown bundle id")
                    val (cid, hash) = saved.split(":", limit = 2)
                    val chain = chains.firstOrNull { it.chainId == cid.toLong() }
                        ?: return@launch errorTo(r.topic, r.request.id, 5710, "Chain not available")
                    val web3 = Web3j.build(HttpService(chain.rpcUrl))
                    val rc = try {
                        web3.ethGetTransactionReceipt(hash).send().transactionReceipt.orElse(null)
                    } finally {
                        web3.shutdown()
                    }
                    val o = JSONObject().put("version", "2.0.0").put("id", id)
                        .put("chainId", "0x" + cid.toLong().toString(16)).put("atomic", true)
                    if (rc == null) {
                        o.put("status", 100)
                    } else {
                        val ok = rc.isStatusOK
                        o.put("status", if (ok) 200 else 500)
                        o.put(
                            "receipts", JSONArray().put(
                                JSONObject()
                                    .put("logs", JSONArray())
                                    .put("status", if (ok) "0x1" else "0x0")
                                    .put("blockHash", rc.blockHash)
                                    .put("blockNumber", "0x" + rc.blockNumber.toString(16))
                                    .put("gasUsed", "0x" + rc.gasUsed.toString(16))
                                    .put("transactionHash", rc.transactionHash)
                            )
                        )
                    }
                    result(r, o.toString())
                } catch (e: Exception) {
                    errorTo(r.topic, r.request.id, -32603, e.message ?: "Lookup failed")
                }
            }
            else -> return false
        }
        return true
    }

    private val delegate = object : WalletKit.WalletDelegate {
        override fun onSessionProposal(
            sessionProposal: Wallet.Model.SessionProposal,
            verifyContext: Wallet.Model.VerifyContext
        ) {
            _proposals.update { it + Proposal(sessionProposal, verifyContext) }
        }

        override fun onSessionRequest(
            sessionRequest: Wallet.Model.SessionRequest,
            verifyContext: Wallet.Model.VerifyContext
        ) {
            refreshSessions()
            if (sessionRequest.request.method == SWITCH_CHAIN) { handleSwitch(sessionRequest); return }
            if (answerReadOnly(sessionRequest)) return
            if (sessionRequest.request.method !in METHODS) {
                // eth_sign, eth_signTransaction and the rest are refused without a prompt
                errorTo(sessionRequest.topic, sessionRequest.request.id, 4200, "Unsupported method")
                return
            }
            _requests.update { it + Request(sessionRequest, verifyContext) }
        }

        override fun onConnectionStateChange(state: Wallet.Model.ConnectionState) {
            _relayUp.value = state.isAvailable
        }

        override fun onSessionExtend(session: Wallet.Model.Session) { refreshSessions() }
        override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) { refreshSessions() }
        override fun onSessionSettleResponse(settleSessionResponse: Wallet.Model.SettledSessionResponse) { refreshSessions() }
        override fun onSessionUpdateResponse(sessionUpdateResponse: Wallet.Model.SessionUpdateResponse) {}
        override fun onError(error: Wallet.Model.Error) { _status.value = error.throwable.message }
    }
}
