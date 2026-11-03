package com.nzm.wallet.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.*
import com.nzm.wallet.chain.Rpc.isContract
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.ZERO
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.shortAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import java.math.BigInteger

@Composable
fun DelegationDialog(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    chain: Chain,
    from: String,
    onClose: (changed: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }       // delegate address, ZERO = revoke
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var basePlan by remember { mutableStateOf<TxPlan?>(null) }
    var feeEdit by remember { mutableStateOf(false) }
    var nonceEdit by remember { mutableStateOf(false) }
    var gasEdit by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    var hash by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("Pending...") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(false) }

    LaunchedEffect(chain.chainId, from) {
        loaded = false
        loadError = null
        try {
            current = Batch.status(chain, from).delegate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Could not read the account"
        }
        loaded = true
    }

    LaunchedEffect(hash) {
        val h = hash ?: return@LaunchedEffect
        result = try {
            when (SendTx.waitForReceipt(chain.rpcUrl, h)) {
                true -> "Confirmed"
                false -> "FAILED (reverted)"
                null -> "Not mined yet. Check explorer."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Status unknown: ${e.message}"
        }
        if (result == "Confirmed") changed = true
    }

    val p = plan
    val base = basePlan
    val h = hash
    val tgt = target
    val revoke = tgt == ZERO

    fun launchSafe(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    fun prepare(delegate: String) = launchSafe {
        if (delegate != ZERO) {
            require(
                isContract(
                    chain.rpcUrl,
                    delegate
                )
            ) { "This contract is not deployed on ${chain.name}" }
        }
        val built = SendTx.plan(chain, from, from, BigInteger.ZERO)
            .let { it.copy(gasLimit = it.gasLimit.max(Delegation.GAS_LIMIT)) }
        basePlan = built
        plan = built
        target = delegate
        understood = false
    }

    fun goBack() {
        when {
            busy -> {}
            h != null -> onClose(changed)
            p != null -> {
                plan = null; basePlan = null; target = null
            }

            else -> onClose(changed)
        }
    }

    TxPage(
        onDismiss = { if (!busy && h == null) goBack() },
        bottom = {
            when {
                h != null -> {
                    if (chain.explorerUrl != null) {
                        OutlineButton("Explorer", {
                            activity.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "${chain.explorerUrl}/tx/$h".toUri()
                                )
                            )
                        }, Modifier.weight(1f))
                    }
                    PrimaryButton("Done", { onClose(changed) }, Modifier.weight(1f))
                }

                p != null && tgt != null -> {
                    OutlineButton("Back", { goBack() }, Modifier.weight(1f))
                    PrimaryButton(
                        if (busy) "Working…" else "Sign & send",
                        onClick = {
                            launchSafe {
                                hash = vault.withCredentials(activity) { creds ->
                                    Delegation.signAndSend(
                                        p,
                                        tgt,
                                        creds
                                    )
                                }
                            }
                        },
                        enabled = !busy && (revoke || understood), modifier = Modifier.weight(1f)
                    )
                }

                else -> OutlineButton("Close", { goBack() }, Modifier.weight(1f))
            }
        }
    ) {
        when {
            // ---------- status ----------
            h != null -> {
                PageHeader("Sent") { onClose(changed) }
                TxAmount(
                    if (revoke) "Delegation revoked" else "Delegation set",
                    Delegation.knownName(tgt) ?: ""
                )
                TxCard {
                    TxRow(
                        "Status", result,
                        when {
                            result == "Confirmed" -> TxOk
                            result.startsWith("FAILED") -> Dim.Danger
                            else -> Dim.Ink
                        }
                    )
                    TxLine()
                    TxLabel("Transaction hash")
                    TxMono(h)
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ---------- review ----------
            p != null && tgt != null -> {
                PageHeader("Review") { goBack() }
                TxAmount(
                    if (revoke) "Revoke delegation" else "Delegate account",
                    if (revoke) "Back to a plain account" else (Delegation.knownName(tgt)
                        ?: shortAddress(tgt)),
                    color = if (revoke) Dim.Ink else Dim.Danger
                )
                TxCard { TxPartyRow("Account", rememberParty(vm, p.from)) }
                if (revoke) {
                    Text(
                        "Your account on ${chain.name} stops running contract code. Your funds are not affected.",
                        fontSize = 14.sp, color = Dim.Mid
                    )
                } else {
                    TxCard {
                        Text(
                            "This gives the contract below control of your account on ${chain.name}. " +
                                    "Calls made through it run with your account's authority. " +
                                    "Only delegate to a contract you trust. Scam sites ask for this to drain wallets.",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger
                        )
                        Spacer(Modifier.height(10.dp))
                        TxLabel("Delegate contract", top = 0)
                        TxMono(Keys.toChecksumAddress(tgt))
                        if (Delegation.knownName(tgt) == null) {
                            Text(
                                "Not a contract this wallet knows.",
                                fontSize = 13.sp,
                                color = Dim.Danger,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    TxCard(Modifier.clickable { understood = !understood }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "I understand and I trust this contract",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (understood) "Yes" else "No",
                                fontSize = 15.sp,
                                color = if (understood) TxOk else Dim.Mid
                            )
                        }
                    }
                }
                TxCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Network",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
                    }
                }
                TxCard(Modifier.clickable(enabled = !busy) { feeEdit = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Max fee", fontSize = 15.sp, color = Dim.Mid)
                            if (base != null && p.fee != base.fee) Text(
                                "Edited",
                                fontSize = 12.sp,
                                color = Dim.Accent
                            )
                        }
                        val fiat = vm.fiat(null, p.maxFeeWei)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                fiat ?: fmt(p.maxFeeWei, chain),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (fiat != null) Text(
                                fmt(p.maxFeeWei, chain),
                                fontSize = 12.sp,
                                color = Dim.Mid
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit fee", Modifier.size(18.dp), tint = Dim.Mid)
                    }
                }
                TxAdvanced {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { nonceEdit = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Nonce",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(p.nonce.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit nonce", Modifier.size(16.dp), tint = Dim.Mid)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { gasEdit = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Gas limit",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            p.gasLimit.toString(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit gas limit", Modifier.size(16.dp), tint = Dim.Mid)
                    }
                    TxRow("Authorization chain", chain.chainId.toString())
                    TxRow("Authorization nonce", (p.nonce + BigInteger.ONE).toString())
                    TxLabel("Delegate address", top = 12)
                    TxMono(if (revoke) "$ZERO (none)" else Keys.toChecksumAddress(tgt))
                    Text(
                        "The authorization is only valid on this chain.",
                        fontSize = 12.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ---------- overview ----------
            else -> {
                PageHeader("Smart account") { goBack() }
                TxCard {
                    TxPartyRow("Account", rememberParty(vm, from))
                    TxLine()
                    TxRow("Network", chain.name)
                }
                TxCard {
                    TxLabel("Delegated to", top = 4)
                    when {
                        loadError != null -> Text(
                            loadError ?: "",
                            fontSize = 14.sp,
                            color = Dim.Danger
                        )

                        !loaded -> Text("Checking…", fontSize = 14.sp, color = Dim.Mid)
                        current == null -> Text(
                            "Not delegated. This is a plain account on ${chain.name}.",
                            fontSize = 14.sp
                        )

                        else -> {
                            val name = Delegation.knownName(current)
                            Text(
                                name ?: "Unknown contract",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (name == null) Dim.Danger else Dim.Ink
                            )
                            TxMono(current ?: "")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "Delegation is per chain. It lets your account run a contract's code, for example to send batches. " +
                            "Your keys stay in this wallet.",
                    fontSize = 13.sp, color = Dim.Mid
                )
                if (loaded && loadError == null) {
                    Delegation.KNOWN.filter { !it.address.equals(current, true) }.forEach { k ->
                        TxCard(Modifier.clickable(enabled = !busy) { prepare(k.address) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (current == null) "Delegate to" else "Switch to",
                                        fontSize = 13.sp,
                                        color = Dim.Mid
                                    )
                                    Text(k.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        shortAddress(Keys.toChecksumAddress(k.address)),
                                        fontSize = 12.sp,
                                        color = Dim.Mid
                                    )
                                }
                                Icon(Ic.ChevronRight, null, Modifier.size(18.dp), tint = Dim.Mid)
                            }
                        }
                    }
                    if (current != null) {
                        TxCard(Modifier.clickable(enabled = !busy) { prepare(ZERO) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Revoke delegation",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Dim.Danger,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Ic.ChevronRight, null, Modifier.size(18.dp), tint = Dim.Mid)
                            }
                        }
                    }
                }
                if (busy) Text("Working…", fontSize = 14.sp, color = Dim.Mid)
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
    }

    if (feeEdit && p != null && base != null) {
        TxFeeDialog(
            plan = p,
            base = base,
            onSave = { plan = p.copy(fee = it); feeEdit = false },
            onDismiss = { feeEdit = false })
    }
    if (nonceEdit && p != null) {
        TxNonceDialog(
            current = p.nonce,
            onSave = { plan = p.copy(nonce = it); nonceEdit = false },
            onDismiss = { nonceEdit = false })
    }
    if (gasEdit && p != null && base != null) {
        TxGasDialog(
            current = p.gasLimit,
            base = base.gasLimit,
            onSave = { plan = p.copy(gasLimit = it); gasEdit = false },
            onDismiss = { gasEdit = false }
        )
    }
}
