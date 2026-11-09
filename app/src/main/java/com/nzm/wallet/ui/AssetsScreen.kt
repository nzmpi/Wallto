package com.nzm.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.storage.AssetIcon
import com.nzm.wallet.ui.theme.Dim

@Composable
fun AssetsScreen(
    activity: FragmentActivity,
    vm: WalletViewModel,
    address: String,
    onSend: (Token?) -> Unit,
    onWalletMenu: () -> Unit,
    onChainMenu: () -> Unit
) {
    val chain = vm.selected ?: return
    var qrOpen by remember { mutableStateOf(false) }
    val hide = vm.hideBalances
    val mask = "****"

    LaunchedEffect(address, chain.chainId, chain.rpcUrl, vm.tokens) { vm.refresh() }
    LaunchedEffect(address, vm.ensRpc) { vm.refreshEns() }

    var addToken by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Token?>(null) }

    // hidden only when the balance is known to be exactly 0; unknown or failed stays visible
    val shownTokens = vm.tokens.filter { vm.tokenBalances[it.address]?.signum() != 0 }
    val hiddenCount = vm.tokens.size - shownTokens.size

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        item {
            val maxName = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp()
            } * 0.35f
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WalletChip(
                    vm.walletLabel(address),
                    Modifier
                        .widthIn(max = maxName)
                        .offset((-6).dp),
                    onWalletMenu
                )
                Spacer(Modifier.width(1.dp))
                CopyAddressButton(address)
                Spacer(Modifier.width(1.dp))
                QrButton { qrOpen = true }
                Spacer(Modifier.weight(1f))
                ChainChip(chain, onChainMenu)
            }
        }
        item {
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (hide) mask else vm.amountText(vm.nativeBalance, chain.decimals),
                        fontSize = 48.sp,
                        lineHeight = 52.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.4).sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alignByBaseline()
                    )
                    Text(
                        chain.symbol,
                        fontSize = 20.sp,
                        color = Dim.Mid,
                        modifier = Modifier.alignByBaseline()
                    )
                }
                HideBalancesButton(hide) { vm.toggleHideBalances() }
            }
            val sub = vm.fiat(null, vm.nativeBalance)
            if (sub != null) {
                Text(
                    if (hide) mask else sub,
                    fontSize = 16.sp,
                    color = Dim.Mid,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            vm.balanceError?.let {
                Text(
                    "Can't load balances: $it",
                    fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Send", onClick = { onSend(null) }, icon = Ic.Send)
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Assets",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                RefreshButton(vm.loading) { vm.refresh(force = true) }
                TextButton(onClick = { addToken = true }) {
                    Text(
                        "Add token",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Dim.Accent
                    )
                }
            }
        }
        item(key = "native") {
            AssetRow(
                initial = chain.symbol.take(1).uppercase(),
                icon = { AssetIcon(chain, null, 40.dp, chain.symbol.take(1).uppercase()) },
                title = chain.symbol,
                subtitle = chain.name,
                amount = if (hide) mask else vm.amountText(vm.nativeBalance, chain.decimals),
                fiat = vm.fiat(null, vm.nativeBalance)?.let { if (hide) mask else it },
                native = true
            )
        }
        items(shownTokens, key = { it.address }) { t ->
            val known = vm.tokenBalances.containsKey(t.address)
            val raw = vm.tokenBalances[t.address]
            AssetRow(
                initial = t.symbol.take(1).uppercase(),
                icon = { AssetIcon(chain, t, 40.dp, t.symbol.take(1).uppercase()) },
                title = t.symbol,
                subtitle = t.name,
                amount = if (hide) mask else if (known) vm.amountText(raw, t.decimals) else "…",
                fiat = vm.fiat(t, raw)?.let { if (hide) mask else it },
                onClick = { info = t }
            )
        }
        item {
            Hairline()
            if (hiddenCount > 0) {
                Text(
                    "$hiddenCount token${if (hiddenCount == 1) "" else "s"} with zero balance hidden. Manage in Settings → Tokens.",
                    fontSize = 13.sp, color = Dim.Mid, modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addToken) TokenDialog(chain, vm.tokenRepo) { addToken = false }
    info?.let { t ->
        TokenInfoDialog(
            activity, chain, t, address,
            onSend = { info = null; onSend(t) }
        ) { info = null }
    }
    if (qrOpen) QrDialog(vm.walletLabel(address), address) { qrOpen = false }
}
