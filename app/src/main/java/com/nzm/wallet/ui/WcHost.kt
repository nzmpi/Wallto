package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService
import com.reown.walletkit.client.Wallet

@Composable
fun WcHost(activity: FragmentActivity, vault: Vault, vm: WalletViewModel, address: String) {
    val proposals by WcService.proposals.collectAsState()
    val requests by WcService.requests.collectAsState()
    val p = proposals.firstOrNull()
    val r = requests.firstOrNull()
    if (p != null) {
        key(p.proposal.proposerPublicKey) { ProposalDialog(vm, address, p) }
    } else if (r != null) {
        key(r.request.request.id) { WcRequestScreen(activity, vault, vm, address, r) }
    }
}

/** [address] is the active wallet: a new connection always uses it. */
@Composable
private fun ProposalDialog(vm: WalletViewModel, address: String, item: WcService.Proposal) {
    val p = item.proposal
    val (warn, blocked) = verdict(item.verify)
    val verified = item.verify.validation == Wallet.Model.Validation.VALID && !blocked
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var siteInfo by remember { mutableStateOf(false) }

    TxPage(
        onDismiss = { },
        bottom = {
            OutlineButton("Reject", { WcService.reject(item) }, Modifier.weight(1f))
            PrimaryButton(
                if (busy) "Working…" else "Connect",
                onClick = {
                    busy = true
                    WcService.approve(item, vm.chains, address) { err -> busy = false; error = err }
                },
                enabled = !blocked && !busy,
                modifier = Modifier.weight(1f)
            )
        }
    ) {
        PageHeader("Connect") { WcService.reject(item) }

        // site: green when the domain is verified, red otherwise. Tap for the check.
        Column(Modifier.clickable { siteInfo = true }.padding(top = 4.dp)) {
            Text(
                p.name.ifBlank { "Unknown site" },
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = if (verified) TxOk else Dim.Danger, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(p.url, fontSize = 14.sp, color = Dim.Mid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (blocked) Text(warn, fontSize = 14.sp, color = Dim.Danger)

        TxCard {
            TxPartyRow("Wallet", rememberParty(vm, address))
        }

        TxCard {
            TxRow("Networks", "${vm.chains.size} saved")
            TxLine()
            Text(
                "Shares this wallet's address on all chains saved in it. " +
                        "The site can request signatures and transactions. Each one needs your approval.",
                fontSize = 14.sp, color = Dim.Mid, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }

        if (vm.wallets.size > 1)
            Text("Wrong wallet? Reject, switch wallet, then connect again.", fontSize = 13.sp, color = Dim.Mid)
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
    }

    if (siteInfo) {
        AlertDialog(
            onDismissRequest = { siteInfo = false },
            title = { Text("Domain check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(warn, color = if (blocked || !verified) Dim.Danger else TxOk)
                    Text("Site says: ${p.url}", fontSize = 14.sp, color = Dim.Mid)
                }
            },
            confirmButton = { TextButton(onClick = { siteInfo = false }) { Text("Close") } }
        )
    }
}
