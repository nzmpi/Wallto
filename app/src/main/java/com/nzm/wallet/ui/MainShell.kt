package com.nzm.wallet.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.Token
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService

enum class HomeTab(val label: String, val icon: ImageVector) {
    Assets("Assets", Ic.Rows),
    Activity("Activity", Ic.Clock),
    Connect("Connect", Ic.Scan),
    Contracts("Contracts", Ic.Code),
    Settings("Settings", Ic.Sliders)
}

enum class SettingsPage { Root, Wallets, Chains, Tokens, Contacts, Sites }
private data class SendRequest(val token: Token?, val to: String)

@Composable
fun MainShell(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    onReveal: () -> Unit,
    onWipe: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Assets) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var chainMenuOpen by remember { mutableStateOf(false) }
    var walletMenuOpen by remember { mutableStateOf(false) }
    var sendReq by remember { mutableStateOf<SendRequest?>(null) }

    // errors from the WalletConnect side appear as a toast, whatever tab you are on
    val wcStatus by WcService.status.collectAsState()
    LaunchedEffect(wcStatus) {
        wcStatus?.let {
            Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
            WcService.clearStatus()
        }
    }

    val wcReady by WcService.ready.collectAsState()
    LaunchedEffect(vm.pendingWcUri, wcReady) {
        val u = vm.pendingWcUri ?: return@LaunchedEffect
        if (wcReady) {
            vm.pendingWcUri = null
            Toast.makeText(activity, "Connecting…", Toast.LENGTH_SHORT).show()
            WcService.pair(u)
        } else if (WcService.projectId(activity) == null) {
            vm.pendingWcUri = null
            Toast.makeText(activity, "Add the Reown project ID in Settings → Connected sites first", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = chainMenuOpen) { chainMenuOpen = false }
    BackHandler(enabled = walletMenuOpen) { walletMenuOpen = false }
    BackHandler(
        enabled = !chainMenuOpen && !walletMenuOpen && tab == HomeTab.Settings && settingsPage != SettingsPage.Root
    ) {
        settingsPage = SettingsPage.Root
    }
    BackHandler(
        enabled = !chainMenuOpen && !walletMenuOpen && tab != HomeTab.Assets &&
                !(tab == HomeTab.Settings && settingsPage != SettingsPage.Root)
    ) { tab = HomeTab.Assets }

    fun scan() {
        scanQr(
            activity,
            onResult = { raw ->
                when (val r = parseScan(raw)) {
                    is ScanResult.WalletConnect -> {
                        Toast.makeText(activity, "Connecting…", Toast.LENGTH_SHORT).show()
                        WcService.pair(r.uri)
                    }
                    is ScanResult.Address -> sendReq = SendRequest(null, r.value)
                    ScanResult.Unknown ->
                        Toast.makeText(activity, "Not a WalletConnect or address QR code", Toast.LENGTH_LONG).show()
                }
            },
            onError = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Dim.Bg,
            contentColor = Dim.Ink,
            bottomBar = {
                NavBar(tab) { t ->
                    if (t == HomeTab.Connect) scan() else { tab = t; chainMenuOpen = false; walletMenuOpen = false }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                Crossfade(targetState = tab, animationSpec = tween(150), label = "tab") { t ->
                    when (t) {
                        HomeTab.Assets -> AssetsScreen(
                            activity, vm, address,
                            onSend = { sendReq = SendRequest(it, "") },
                            onWalletMenu = { chainMenuOpen = false; walletMenuOpen = true },
                            onChainMenu = { walletMenuOpen = false; chainMenuOpen = true }
                        )
                        HomeTab.Activity -> ActivityScreen(activity, vm, address)
                        HomeTab.Connect -> Unit // an action, not a tab
                        HomeTab.Contracts -> ContractsScreen(activity, vault, vm, address) { chainMenuOpen = true }
                        HomeTab.Settings -> SettingsScreen(
                            activity, vm, address, settingsPage,
                            onPage = { settingsPage = it },
                            onReveal = onReveal,
                            onWipe = onWipe
                        )
                    }
                }
            }
        }
        SendChainMenu(
            vm = vm,
            open = chainMenuOpen,
            onDismiss = { chainMenuOpen = false },
            onSelect = { vm.selectChain(it); chainMenuOpen = false },
            onManage = { tab = HomeTab.Settings; settingsPage = SettingsPage.Chains; chainMenuOpen = false }
        )
        WalletMenuOverlay(
            vm = vm,
            open = walletMenuOpen,
            onDismiss = { walletMenuOpen = false },
            onSelect = { vm.switchWallet(vault, it); walletMenuOpen = false },
            onAdd = { walletMenuOpen = false; vm.addWalletRequest = true }
        )
        WcHost(activity, vault, vm, address)
        sendReq?.let { r ->
            key(r) {
                SendScreen(activity, vault, vm, address, r.token, r.to) { sent ->
                    sendReq = null
                    if (sent) vm.refresh()
                }
            }
        }
    }
}

@Composable
private fun NavBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Column(Modifier.background(Dim.Nav)) {
        Hairline()
        Row(Modifier.navigationBarsPadding().height(68.dp).selectableGroup()) {
            HomeTab.entries.forEach { t ->
                val active = t == selected
                val color by animateColorAsState(if (active) Dim.Accent else Dim.Mid, tween(150), label = "tabColor")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(t) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(t.icon, null, Modifier.size(24.dp), tint = color)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label, fontSize = 12.sp, color = color,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.WalletMenuOverlay(
    vm: WalletViewModel,
    open: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    AnimatedVisibility(visible = open, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
        Box(
            Modifier.fillMaxSize().background(Dim.Scrim).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
        )
    }
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 62.dp, start = 20.dp),
        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 0f)),
        exit = fadeOut(tween(100))
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.width(280.dp).clip(shape).background(Dim.Raised).border(1.dp, Dim.MenuLine, shape).padding(6.dp)
        ) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                vm.wallets.filter { it.address.isNotBlank() }.forEach { w ->
                    WalletMenuItem(w.name, w.address, w.id == vm.activeWalletId) { onSelect(w.id) }
                }
            }
            MenuLine()
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onAdd).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add wallet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dim.Accent, modifier = Modifier.weight(1f))
                Icon(Ic.Plus, null, Modifier.size(16.dp), tint = Dim.Accent)
            }
        }
    }
}

@Composable
private fun WalletMenuItem(name: String, address: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Dim.RaisedSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(shortAddress(address), fontSize = 12.sp, color = Dim.Mid, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Ic.Check, "Selected", Modifier.size(18.dp), tint = Dim.Accent)
        }
    }
}
