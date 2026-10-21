package com.nzm.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService

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
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val walletName = vm.activeWallet?.name ?: "Wallet"
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Connect to ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.url, fontSize = 14.sp, color = Dim.Mid)
                Text(warn, color = if (blocked) Dim.Danger else Dim.Mid, fontSize = 14.sp)
                Text("Wallet: $walletName", fontWeight = FontWeight.SemiBold)
                Text("Shares its address ${shortAddress(address)} on all chains saved in this wallet. " +
                        "The site can request signatures and transactions. Each one needs your approval.")
                if (vm.wallets.size > 1)
                    Text("Wrong wallet? Reject, switch wallet, then connect again.", fontSize = 13.sp, color = Dim.Mid)
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
