package com.nzm.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.*
import com.reown.walletkit.client.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WcHost(activity: FragmentActivity, vault: Vault, vm: WalletViewModel, address: String) {
    val proposals by WcService.proposals.collectAsState()
    val requests by WcService.requests.collectAsState()
    val p = proposals.firstOrNull()
    val r = requests.firstOrNull()
    if (p != null) {
        key(p.proposal.proposerPublicKey) { ProposalDialog(vm, address, p) }
    } else if (r != null) {
        key(r.request.request.id) { RequestDialog(activity, vault, vm, address, r) }
    }
}

/** Returns (line, blocked). Blocked sites cannot be approved. */
private fun verdict(v: Wallet.Model.VerifyContext): Pair<String, Boolean> = when {
    v.isScam == true -> "WARNING: this site is flagged as a scam." to true
    v.validation == Wallet.Model.Validation.INVALID ->
        "WARNING: the site's domain does not match what it claims. Likely phishing." to true
    v.validation == Wallet.Model.Validation.VALID -> "Verified domain: ${v.origin}" to false
    else -> "Domain could not be verified. Check the name and address yourself." to false
}

@Composable
private fun ProposalDialog(vm: WalletViewModel, address: String, item: WcService.Proposal) {
    val p = item.proposal
    val (warn, blocked) = verdict(item.verify)
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Connect to ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.url, fontSize = 14.sp, color = Dim.Mid)
                Text(warn, color = if (blocked) Dim.Danger else Dim.Mid, fontSize = 14.sp)
                Text("Shares your address ${shortAddress(address)} on all chains saved in this wallet. " +
                        "The site can request signatures and transactions. Each one needs your approval.")
                error?.let { Text(it, color = Dim.Danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !blocked && !busy, onClick = {
                busy = true
                WcService.approve(item, vm.chains, address) { err -> busy = false; error = err }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = { WcService.reject(item) }) { Text("Reject", color = Dim.Danger) } }
    )
}

private sealed interface Parsed {
    class Msg(val m: PersonalMsg) : Parsed
    class Typed(val t: TypedReq) : Parsed
    class Tx(val t: WcTx) : Parsed
    class Bad(val why: String) : Parsed
}

@Composable
private fun RequestDialog(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    item: WcService.Request
) {
    val req = item.request
    val cid = req.chainId?.removePrefix("eip155:")?.toLongOrNull()
    val chain = vm.chains.firstOrNull { it.chainId == cid }
    val (warn, blocked) = verdict(item.verify)
    val site = req.peerMetaData
    val scope = rememberCoroutineScope()

    val parsed = remember(item) {
        if (chain == null) Parsed.Bad("Chain ${req.chainId} is not saved in this wallet. Add it first.")
        else try {
            when (req.request.method) {
                WcService.PERSONAL_SIGN -> Parsed.Msg(WcSigner.parsePersonal(req.request.params, address))
                WcService.TYPED_V4 -> {
                    val t = WcSigner.parseTyped(req.request.params, address)
                    if (t.domainChainId != null && t.domainChainId != chain.chainId)
                        Parsed.Bad("Signed data is for chain ${t.domainChainId}, request is for ${chain.chainId}.")
                    else Parsed.Typed(t)
                }
                WcService.SEND_TX -> Parsed.Tx(WcSigner.parseTx(req.request.params, address))
                else -> Parsed.Bad("Unsupported method")
            }
        } catch (e: Exception) {
            Parsed.Bad(e.message ?: "Could not read request")
        }
    }

    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (parsed is Parsed.Tx && chain != null) {
        LaunchedEffect(item) {
            try {
                plan = SendTx.plan(chain, address, parsed.t.to, parsed.t.value, data = parsed.t.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not prepare transaction"
            }
        }
    }

    val title = when (parsed) {
        is Parsed.Msg -> "Sign message"
        is Parsed.Typed -> "Sign typed data"
        is Parsed.Tx -> "Send transaction"
        is Parsed.Bad -> "Request"
    }
    val canApprove = !blocked && !busy && when (parsed) {
        is Parsed.Tx -> plan != null
        is Parsed.Bad -> false
        else -> true
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("${site?.name ?: "Unknown site"}\n${site?.url ?: ""}", fontSize = 14.sp, color = Dim.Mid)
                Text(warn, fontSize = 14.sp, color = if (blocked) Dim.Danger else Dim.Mid)
                chain?.let { Text("Network: ${it.name}" + if (it.isTestnet) " (testnet)" else "", fontWeight = FontWeight.SemiBold) }
                when (parsed) {
                    is Parsed.Bad -> Text(parsed.why, color = Dim.Danger)
                    is Parsed.Msg -> {
                        if (!parsed.m.isText) Text("Binary data, not readable text:", color = Dim.Danger)
                        Mono(parsed.m.text)
                        Text("Signing proves you own this address. It does not move funds.", fontSize = 13.sp, color = Dim.Mid)
                    }
                    is Parsed.Typed -> {
                        Text("Type: ${parsed.t.primaryType}")
                        parsed.t.domainName?.let { Text("Domain: $it") }
                        parsed.t.verifyingContract?.let { Text("Contract: $it") }
                        if (parsed.t.looksLikePermit)
                            Text("This kind of signature can authorize spending your tokens without a transaction. " +
                                    "Only sign it on a site you trust.", color = Dim.Danger)
                        Mono(parsed.t.pretty)
                    }
                    is Parsed.Tx -> {
                        val sym = chain!!.symbol
                        Text("To:", fontSize = 13.sp, color = Dim.Mid)
                        Mono(parsed.t.to)
                        Text("Value: ${Rpc.formatUnits(parsed.t.value, chain.decimals, 18)} $sym", fontWeight = FontWeight.SemiBold)
                        val (desc, danger) = WcSigner.describe(parsed.t, if (chain.chainId == vm.selected?.chainId) vm.tokens else emptyList())
                        Text(desc, color = if (danger) Dim.Danger else Dim.Ink)
                        plan?.let { Text("Max fee: ${Rpc.formatUnits(it.maxFeeWei, chain.decimals, 8)} $sym", fontSize = 13.sp, color = Dim.Mid) }
                        if (plan == null && error == null) Text("Preparing…", color = Dim.Mid)
                    }
                }
                error?.let { Text(it, color = Dim.Danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = canApprove, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val result: String = when (parsed) {
                            is Parsed.Msg -> vault.withCredentials(activity) { WcSigner.signPersonal(it, parsed.m.bytes) }
                            is Parsed.Typed -> vault.withCredentials(activity) { WcSigner.signTyped(it, parsed.t) }
                            is Parsed.Tx -> {
                                val pl = plan ?: return@launch
                                val desc = WcSigner.describe(parsed.t, if (chain!!.chainId == vm.selected?.chainId) vm.tokens else emptyList()).first
                                vm.annotateNext(site?.name ?: site?.url, desc.lineSequence().first())
                                vault.withCredentials(activity) { SendTx.signAndSend(pl, it) }
                            }
                            is Parsed.Bad -> return@launch
                        }
                        WcService.respondResult(item, result)
                        if (parsed is Parsed.Tx) vm.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Failed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (parsed is Parsed.Tx) "Send" else "Sign") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { WcService.respondError(item) }) {
                Text("Reject", color = Dim.Danger)
            }
        }
    )
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Dim.Ink)
}
