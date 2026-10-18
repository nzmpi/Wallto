package com.nzm.wallet.ui

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim

enum class HomeTab(val label: String, val icon: ImageVector) {
    Assets("Assets", Ic.Rows),
    Activity("Activity", Ic.Clock),
    Connect("Connect", Ic.Scan),
    Contracts("Contracts", Ic.Code),
    Settings("Settings", Ic.Sliders)
}

enum class SettingsPage { Root, Chains, Tokens }

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
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !menuOpen && tab == HomeTab.Settings && settingsPage != SettingsPage.Root) {
        settingsPage = SettingsPage.Root
    }
    BackHandler(
        enabled = !menuOpen && tab != HomeTab.Assets &&
                !(tab == HomeTab.Settings && settingsPage != SettingsPage.Root)
    ) { tab = HomeTab.Assets }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Dim.Bg,
            contentColor = Dim.Ink,
            bottomBar = { NavBar(tab) { tab = it; menuOpen = false } }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                Crossfade(targetState = tab, animationSpec = tween(150), label = "tab") { t ->
                    when (t) {
                        HomeTab.Assets -> AssetsScreen(activity, vault, vm, address) { menuOpen = true }
                        HomeTab.Activity -> ComingSoonScreen(
                            "Activity", "Sent and received transactions will be listed here.", vm
                        ) { menuOpen = true }
                        HomeTab.Connect -> ConnectScreen(activity)
                        HomeTab.Contracts -> ContractsScreen(activity, vault, vm, address) { menuOpen = true }
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
        ChainMenuOverlay(
            vm = vm,
            open = menuOpen,
            onDismiss = { menuOpen = false },
            onSelect = { vm.selectChain(it); menuOpen = false },
            onManage = { tab = HomeTab.Settings; settingsPage = SettingsPage.Chains; menuOpen = false }
        )
        WcHost(activity, vault, vm, address)
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
private fun BoxScope.ChainMenuOverlay(
    vm: WalletViewModel,
    open: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onManage: () -> Unit
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
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 62.dp, end = 20.dp),
        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut(tween(100))
    ) {
        val shape = RoundedCornerShape(20.dp)
        val current = vm.selected?.chainId
        val mainnets = vm.chains.filter { !it.isTestnet }
        val testnets = vm.chains.filter { it.isTestnet }
        Column(
            Modifier.width(260.dp).clip(shape).background(Dim.Raised).border(1.dp, Dim.MenuLine, shape).padding(6.dp)
        ) {
            mainnets.forEach { ChainMenuItem(it, it.chainId == current) { onSelect(it.chainId) } }
            if (mainnets.isNotEmpty() && testnets.isNotEmpty()) MenuDivider()
            testnets.forEach { ChainMenuItem(it, it.chainId == current) { onSelect(it.chainId) } }
            MenuDivider()
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onManage).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Manage chains", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dim.Accent, modifier = Modifier.weight(1f))
                Icon(Ic.ChevronRight, null, Modifier.size(16.dp), tint = Dim.Accent)
            }
        }
    }
}

@Composable
private fun ChainMenuItem(chain: Chain, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Dim.RaisedSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        if (chain.isTestnet) Text("testnet", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Dim.Mid)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Ic.Check, "Selected", Modifier.size(18.dp), tint = Dim.Accent)
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Dim.MenuLine))
}
