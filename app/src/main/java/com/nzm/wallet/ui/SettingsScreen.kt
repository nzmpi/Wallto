package com.nzm.wallet.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.Token
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.launch

private val LOCK_OPTIONS = listOf(
    30_000L to "30 seconds",
    60_000L to "1 minute",
    300_000L to "5 minutes",
    900_000L to "15 minutes"
)

private fun lockLabel(ms: Long) =
    LOCK_OPTIONS.firstOrNull { it.first == ms }?.second ?: "${ms / 1000} seconds"

@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    vm: WalletViewModel,
    address: String,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    onReveal: () -> Unit,
    onWipe: () -> Unit
) {
    Crossfade(targetState = page, animationSpec = tween(140), label = "settings") { p ->
        when (p) {
            SettingsPage.Root -> SettingsRoot(vm, onPage, onReveal, onWipe)
            SettingsPage.Chains -> ChainsPage(vm) { onPage(SettingsPage.Root) }
            SettingsPage.Tokens -> TokensPage(activity, vm, address) { onPage(SettingsPage.Root) }
        }
    }
}

@Composable
private fun SettingsRoot(
    vm: WalletViewModel,
    onPage: (SettingsPage) -> Unit,
    onReveal: () -> Unit,
    onWipe: () -> Unit
) {
    var lockDialog by remember { mutableStateOf(false) }
    val chain = vm.selected
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp)
    ) {
        ScreenTitle("Settings")
        Spacer(Modifier.height(28.dp))
        SectionLabel("Networks")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Chains", "${vm.chains.size}") { onPage(SettingsPage.Chains) }
        SettingsRow("Tokens", chain?.let { "${vm.tokens.size} on ${it.name}" }) { onPage(SettingsPage.Tokens) }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Security")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Reveal recovery secret", onClick = onReveal)
        SettingsRow("Lock after", lockLabel(vm.lockAfterMs)) { lockDialog = true }
        Hairline()
        Spacer(Modifier.height(28.dp))
        Hairline()
        SettingsRow("Wipe wallet", danger = true, onClick = onWipe)
        Hairline()
        Spacer(Modifier.height(24.dp))
    }
    if (lockDialog) {
        AlertDialog(
            onDismissRequest = { lockDialog = false },
            title = { Text("Lock after") },
            text = {
                Column {
                    LOCK_OPTIONS.forEach { (ms, label) ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clickable { vm.setLockAfter(ms); lockDialog = false },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = ms == vm.lockAfterMs, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = Dim.Ink)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { lockDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun ChainsPage(vm: WalletViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Chain?>(null) }
    var removing by remember { mutableStateOf<Chain?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp)
    ) {
        PageHeader("Chains", onBack)
        Spacer(Modifier.height(20.dp))
        vm.chains.forEach { c ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().height(68.dp).clickable { editing = c },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Chain ID ${c.chainId}" + if (c.isTestnet) ", testnet" else "",
                        fontSize = 13.sp, color = Dim.Mid
                    )
                }
                if (vm.chains.size > 1) {
                    IconButton(onClick = { removing = c }) {
                        Icon(Ic.Trash, "Remove ${c.name}", Modifier.size(22.dp), tint = Dim.Mid)
                    }
                }
            }
        }
        Hairline()
        Spacer(Modifier.height(24.dp))
        OutlineButton("Add chain", onClick = { adding = true }, icon = Ic.Plus)
        Spacer(Modifier.height(24.dp))
    }

    if (adding) {
        ChainDialog(vm.chainRepo, null) { added ->
            adding = false
            if (added != null) vm.selectChain(added.chainId)
        }
    }
    editing?.let { c -> ChainDialog(vm.chainRepo, c) { editing = null } }
    removing?.let { c ->
        ConfirmDialog(
            title = "Remove ${c.name}?",
            text = "Removes this chain and its token list from this device. Your wallet and funds on the chain are not affected.\n\n" +
                    "To add it back you need:\nChain ID: ${c.chainId}\nRPC: ${c.rpcUrl}",
            confirmLabel = "Remove",
            onConfirm = {
                scope.launch { vm.chainRepo.remove(c.chainId) }
                removing = null
            },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun TokensPage(activity: FragmentActivity, vm: WalletViewModel, address: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chain = vm.selected
    var adding by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Token?>(null) }
    var removing by remember { mutableStateOf<Token?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp)
    ) {
        PageHeader(if (chain != null) "Tokens on ${chain.name}" else "Tokens", onBack)
        Spacer(Modifier.height(20.dp))
        if (vm.tokens.isEmpty()) {
            Text("No tokens added on this chain.", fontSize = 15.sp, color = Dim.Mid)
        }
        vm.tokens.forEach { t ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().height(68.dp).clickable { info = t },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.symbol, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(shortAddress(t.address), fontSize = 13.sp, color = Dim.Mid)
                }
                IconButton(onClick = { removing = t }) {
                    Icon(Ic.Trash, "Remove ${t.symbol}", Modifier.size(22.dp), tint = Dim.Mid)
                }
            }
        }
        if (vm.tokens.isNotEmpty()) Hairline()
        Spacer(Modifier.height(24.dp))
        OutlineButton("Add token", onClick = { adding = true }, icon = Ic.Plus)
        Spacer(Modifier.height(24.dp))
    }

    if (adding && chain != null) TokenDialog(chain, vm.tokenRepo) { adding = false }
    info?.let { t ->
        if (chain != null) TokenInfoDialog(activity, chain, t, address) { info = null }
    }
    removing?.let { t ->
        ConfirmDialog(
            title = "Remove ${t.symbol}?",
            text = "Removes it from this list only. Your tokens stay in your wallet. Add it again with the contract address:\n${t.address}",
            confirmLabel = "Remove",
            onConfirm = {
                scope.launch { vm.tokenRepo.remove(t.chainId, t.address) }
                removing = null
            },
            onDismiss = { removing = null }
        )
    }
}
