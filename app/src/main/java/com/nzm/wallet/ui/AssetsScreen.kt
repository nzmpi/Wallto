package com.nzm.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Token
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim

@Composable
fun AssetsScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    onChainMenu: () -> Unit
) {
    val chain = vm.selected ?: return

    LaunchedEffect(address, chain.chainId, chain.rpcUrl, vm.tokens) { vm.refresh() }

    var sendOpen by remember { mutableStateOf(false) }
    var sendToken by remember { mutableStateOf<Token?>(null) }
    var addToken by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Token?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AddressPill(address)
                ChainChip(chain.name, onChainMenu)
            }
        }
        item {
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    vm.amountText(vm.nativeBalance, chain.decimals),
                    fontSize = 48.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1.4).sp,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline()
                )
                Text(chain.symbol, fontSize = 20.sp, color = Dim.Mid, modifier = Modifier.alignByBaseline())
            }
            val sub = vm.fiat(null, vm.nativeBalance)
            if (sub != null) {
                Text(sub, fontSize = 16.sp, color = Dim.Mid, modifier = Modifier.padding(top = 10.dp))
            }
            vm.balanceError?.let {
                Text(
                    "Can't load balances: $it",
                    fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Send", onClick = { sendToken = null; sendOpen = true }, icon = Ic.Send)
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Assets", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                RefreshButton(vm.loading) { vm.refresh(force = true) }
                TextButton(onClick = { addToken = true }) {
                    Text("Add token", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Accent)
                }
            }
        }
        item(key = "native") {
            AssetRow(
                initial = chain.symbol.take(1).uppercase(),
                title = chain.symbol,
                subtitle = chain.name,
                amount = vm.amountText(vm.nativeBalance, chain.decimals),
                fiat = vm.fiat(null, vm.nativeBalance),
                native = true
            )
        }
        items(vm.tokens, key = { it.address }) { t ->
            val known = vm.tokenBalances.containsKey(t.address)
            val raw = vm.tokenBalances[t.address]
            AssetRow(
                initial = t.symbol.take(1).uppercase(),
                title = t.symbol,
                subtitle = t.name,
                amount = if (known) vm.amountText(raw, t.decimals) else "…",
                fiat = vm.fiat(t, raw),
                onClick = { info = t }
            )
        }
        item {
            Hairline()
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sendOpen) {
        SendDialog(activity, vault, chain, address, vm.tokens, sendToken) { sent ->
            sendOpen = false
            if (sent) vm.refresh()
        }
    }
    if (addToken) TokenDialog(chain, vm.tokenRepo) { addToken = false }
    info?.let { t ->
        TokenInfoDialog(
            activity, chain, t, address,
            onSend = { info = null; sendToken = t; sendOpen = true }
        ) { info = null }
    }
}
