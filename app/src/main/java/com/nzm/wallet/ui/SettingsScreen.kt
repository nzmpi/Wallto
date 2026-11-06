package com.nzm.wallet.ui

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Contact
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.dbs.WalletRow
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.SHAPE
import com.nzm.wallet.utils.Helpers
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys

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
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    onReveal: () -> Unit,
    onWipe: () -> Unit
) {
    Crossfade(targetState = page, animationSpec = tween(140), label = "settings") { p ->
        when (p) {
            SettingsPage.Root -> SettingsRoot(
                activity,
                vault,
                vm,
                address,
                onPage,
                onReveal,
                onWipe
            )

            SettingsPage.Wallets -> WalletsPage(vm) { onPage(SettingsPage.Root) }
            SettingsPage.Chains -> ChainsPage(vm) { onPage(SettingsPage.Root) }
            SettingsPage.Tokens -> TokensPage(activity, vm, address) { onPage(SettingsPage.Root) }
            SettingsPage.Contacts -> ContactsPage(vm) { onPage(SettingsPage.Root) }
            SettingsPage.Sites -> ConnectedSitesPage(activity, vm) { onPage(SettingsPage.Root) }
        }
    }
}

@Composable
private fun SettingsRoot(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    onPage: (SettingsPage) -> Unit,
    onReveal: () -> Unit,
    onWipe: () -> Unit
) {
    val ctx = LocalContext.current
    val sessions by WcService.sessions.collectAsState()
    var reownProjectId by remember { mutableStateOf(WcService.projectId(activity)) }
    var lockDialog by remember { mutableStateOf(false) }
    var delegation by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var reownDialog by remember { mutableStateOf(false) }
    var alchemyKey by remember { mutableStateOf(SimKey.get(ctx)) }
    var alchemyDialog by remember { mutableStateOf(false) }
    val chain = vm.selected

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        ScreenTitle("Settings")
        Spacer(Modifier.height(28.dp))
        SectionLabel("Wallets")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Manage wallets", "${vm.wallets.size}") { onPage(SettingsPage.Wallets) }
        SettingsRow("Smart account (EIP-7702)", "Change") { if (chain != null) delegation = true }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Networks")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Chains", "${vm.chains.size}") { onPage(SettingsPage.Chains) }
        SettingsRow("Tokens", chain?.let { "${vm.tokens.size} on ${it.name}" }) {
            onPage(
                SettingsPage.Tokens
            )
        }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Address book")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Contacts", "${vm.contacts.size}") { onPage(SettingsPage.Contacts) }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Connections")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Connected sites", "${sessions.size}") { onPage(SettingsPage.Sites) }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Security")
        Spacer(Modifier.height(6.dp))
        SettingsRow("Reveal recovery secret", vm.activeWallet?.name, onClick = onReveal)
        SettingsRow("Lock after", lockLabel(vm.lockAfterMs)) { lockDialog = true }
        Hairline()
        Spacer(Modifier.height(28.dp))
        SectionLabel("Keys")
        Spacer(Modifier.height(6.dp))
        SettingsRow(
            "CoinGecko API key",
            when {
                !vm.priceKeySet -> "Add"
                vm.priceError != null -> "Error"
                else -> "Change"
            }
        ) { keyDialog = true }
        SettingsRow(
            "Reown project ID",
            when {
                reownProjectId == null -> "Add"
                else -> "Change"
            }
        ) { reownDialog = true }
        SettingsRow(
            "Alchemy API key",
            when {
                alchemyKey == null -> "Add"
                else -> "Change"
            }
        ) { alchemyDialog = true }
        Hairline()
        Spacer(Modifier.height(28.dp))
        Hairline()
        SettingsRow("Wipe all wallets", danger = true, onClick = onWipe)
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
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { vm.setLockAfter(ms); lockDialog = false },
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
    if (keyDialog) PriceKeyDialog(vm) { keyDialog = false }
    if (reownDialog) ReownDialog(activity) { reownDialog = false }
    if (alchemyDialog) AlchemyDialog(ctx) { alchemyDialog = false }
    if (delegation && chain != null) {
        DelegationDialog(activity, vault, vm, chain, address) { delegation = false }
    }
}

@Composable
private fun WalletsPage(vm: WalletViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf<WalletRow?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        PageHeader("Wallets", onBack)
        Spacer(Modifier.height(20.dp))
        vm.wallets.forEach { w ->
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clickable { renaming = w },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (w.address.isBlank()) "Unlock to show address" else shortAddress(w.address),
                        fontSize = 13.sp, color = Dim.Mid
                    )
                }
                if (w.id == vm.activeWalletId) {
                    Text(
                        "Active",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Dim.Accent
                    )
                }
                IconButton(onClick = { vm.deleteWalletRequest = w.id }) {
                    Icon(Ic.Trash, "Delete ${w.name}", Modifier.size(22.dp), tint = Dim.Mid)
                }
            }
        }
        if (vm.wallets.isNotEmpty()) Hairline()
        Spacer(Modifier.height(12.dp))
        Text(
            "Tap a wallet to rename it. Switch wallets from the menu on the Assets screen. " +
                    "Contacts and tokens are shared by all wallets.",
            fontSize = 13.sp, color = Dim.Mid
        )
        Spacer(Modifier.height(24.dp))
        OutlineButton("Add wallet", onClick = { vm.addWalletRequest = true }, icon = Ic.Plus)
        Spacer(Modifier.height(24.dp))
    }

    renaming?.let { w ->
        WalletRenameDialog(w) { name ->
            if (name != null) vm.renameWallet(
                w.id,
                name
            ); renaming = null
        }
    }
}

@Composable
private fun WalletRenameDialog(wallet: WalletRow, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(wallet.name) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename wallet") },
        text = {
            OutlinedTextField(
                name, { name = it.take(24) }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } }
    )
}

@Composable
private fun ChainsPage(vm: WalletViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Chain?>(null) }
    var removing by remember { mutableStateOf<Chain?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        PageHeader("Chains", onBack)
        Spacer(Modifier.height(20.dp))
        vm.chains.forEach { c ->
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clickable { editing = c },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChainIcon(c, 28.dp)
                Spacer(Modifier.width(10.dp))
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
                IconStore.forgetChain(c.chainId)
                removing = null
            },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun TokensPage(
    activity: FragmentActivity,
    vm: WalletViewModel,
    address: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val chain = vm.selected
    var adding by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Token?>(null) }
    var removing by remember { mutableStateOf<Token?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        PageHeader(if (chain != null) "Tokens on ${chain.name}" else "Tokens", onBack)
        Spacer(Modifier.height(20.dp))
        if (vm.tokens.isEmpty()) {
            Text("No tokens added on this chain.", fontSize = 15.sp, color = Dim.Mid)
        }
        vm.tokens.forEach { t ->
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clickable { info = t },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssetIcon(chain, t, 28.dp, t.symbol.take(1).uppercase())
                Spacer(Modifier.width(10.dp))
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
                IconStore.forgetToken(t.chainId, t.address)
                removing = null
            },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun PriceKeyDialog(vm: WalletViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("CoinGecko API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Demo key. Dollar prices for mainnets load once a day and when you press refresh. The key is stored encrypted on this phone.")
                vm.priceError?.let { Text(it, color = Dim.Danger) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (vm.priceKeySet) "New key" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = SHAPE,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { vm.setPriceKey(text); onClose() }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (vm.priceKeySet) {
                    TextButton(onClick = { vm.setPriceKey(null); onClose() }) {
                        Text(
                            "Remove",
                            color = Dim.Danger
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun ReownDialog(activity: FragmentActivity, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val idSet = WcService.projectId(activity) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Reown project ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Restart the app after saving. Leave empty and save to remove it.")
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(if (idSet) "New ID" else "Project ID") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = SHAPE
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    WcService.setProjectId(activity, text)
                    onClose()
                }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (idSet) {
                    TextButton(onClick = { WcService.setProjectId(activity, null); onClose() }) {
                        Text(
                            "Remove",
                            color = Dim.Danger
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun AlchemyDialog(ctx: Context, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val keySet = SimKey.get(ctx) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Alchemy API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Used only when you tap Simulation. Alchemy then sees the sender, contract, value and data of that transaction. " +
                            "The key is stored encrypted on this phone. Free keys: dashboard.alchemy.com",
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text(if (keySet) "New key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = SHAPE
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    SimKey.set(ctx, text)
                    onClose()
                }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (keySet) {
                    TextButton(onClick = { SimKey.set(ctx, null); onClose() }) {
                        Text(
                            "Remove",
                            color = Dim.Danger
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun ContactsPage(vm: WalletViewModel, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var removing by remember { mutableStateOf<Contact?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        PageHeader("Contacts", onBack)
        Spacer(Modifier.height(20.dp))
        if (vm.contacts.isEmpty()) {
            Text(
                "No contacts yet. Save one from the Send screen, or add one here.",
                fontSize = 15.sp,
                color = Dim.Mid
            )
            Spacer(Modifier.height(16.dp))
        }
        vm.contacts.forEach { c ->
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clickable { editing = c },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        shortAddress(c.address) + (c.ens?.let { " · $it" } ?: ""),
                        fontSize = 13.sp, color = Dim.Mid
                    )
                }
                IconButton(onClick = { removing = c }) {
                    Icon(Ic.Trash, "Remove ${c.name}", Modifier.size(22.dp), tint = Dim.Mid)
                }
            }
        }
        Hairline()
        Spacer(Modifier.height(24.dp))
        OutlineButton("Add contact", onClick = { adding = true }, icon = Ic.Plus)
        Spacer(Modifier.height(24.dp))
    }

    if (adding) ContactEditDialog(vm, null) { adding = false }
    editing?.let { c -> ContactEditDialog(vm, c) { editing = null } }
    removing?.let { c ->
        ConfirmDialog(
            title = "Remove ${c.name}?",
            text = "Only the saved name is removed.\n\n${c.address}",
            confirmLabel = "Remove",
            onConfirm = { vm.deleteContact(c.address); removing = null },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun ContactEditDialog(vm: WalletViewModel, existing: Contact?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Add contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it.take(32) }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = SHAPE
                )
                OutlinedTextField(
                    address, { address = it.trim() }, label = { Text("Address") },
                    enabled = existing == null, modifier = Modifier.fillMaxWidth(),
                    shape = SHAPE
                )
                error?.let { Text(it, color = Dim.Danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Enter a name"
                    !Helpers.isValidAddress(address) -> error = "Invalid address or checksum"
                    else -> {
                        vm.saveContact(
                            Contact(Keys.toChecksumAddress(address), name.trim(), existing?.ens)
                        )
                        onClose()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
