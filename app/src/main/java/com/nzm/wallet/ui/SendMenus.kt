package com.nzm.wallet.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.ui.theme.Dim

@Composable
private fun MenuScrim(open: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = open, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Dim.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }
}

@Composable
private fun MenuItem(
    title: String, selected: Boolean, trailing: String? = null,
    color: Color = Dim.Ink, onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Dim.RaisedSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(
            trailing,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Dim.Mid,
            maxLines = 1
        )
    }
}

@Composable
fun MenuLine() {
    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Dim.MenuLine)
    )
}

@Composable
fun BoxScope.SendChainMenu(
    vm: WalletViewModel, open: Boolean, onDismiss: () -> Unit, onSelect: (Long) -> Unit,
    onManage: (() -> Unit)? = null
) {
    MenuScrim(open, onDismiss)
    AnimatedVisibility(
        visible = open,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 56.dp, end = 20.dp),
        enter = fadeIn(tween(140)) + scaleIn(
            tween(160),
            initialScale = 0.94f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        exit = fadeOut(tween(100))
    ) {
        val shape = RoundedCornerShape(20.dp)
        val current = vm.selected?.chainId
        val mainnets = vm.chains.filter { !it.isTestnet }
        val testnets = vm.chains.filter { it.isTestnet }
        Column(
            Modifier
                .width(260.dp)
                .heightIn(max = 420.dp)
                .clip(shape)
                .background(Dim.Raised)
                .border(1.dp, Dim.MenuLine, shape)
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
        ) {
            mainnets.forEach { MenuItem(it.name, it.chainId == current) { onSelect(it.chainId) } }
            if (mainnets.isNotEmpty() && testnets.isNotEmpty()) MenuLine()
            testnets.forEach {
                MenuItem(
                    it.name,
                    it.chainId == current,
                    "testnet"
                ) { onSelect(it.chainId) }
            }
            if (onManage != null) {
                MenuLine()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onManage)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Manage chains",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Dim.Accent,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Ic.ChevronRight, null, Modifier.size(16.dp), tint = Dim.Accent)
                }
            }
        }
    }
}

/** tokenAddr null = native coin. */
@Composable
fun BoxScope.SendTokenMenu(
    vm: WalletViewModel,
    chain: Chain,
    tokenAddr: String?,
    open: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onAdd: () -> Unit
) {
    MenuScrim(open, onDismiss)
    AnimatedVisibility(
        visible = open,
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(top = 152.dp, start = 20.dp),
        enter = fadeIn(tween(140)) + scaleIn(
            tween(160),
            initialScale = 0.94f,
            transformOrigin = TransformOrigin(0f, 0f)
        ),
        exit = fadeOut(tween(100))
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .width(260.dp)
                .heightIn(max = 420.dp)
                .clip(shape)
                .background(Dim.Raised)
                .border(1.dp, Dim.MenuLine, shape)
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
        ) {
            MenuItem(
                chain.symbol,
                tokenAddr == null,
                vm.amountText(vm.nativeBalance, chain.decimals)
            ) { onPick(null) }
            vm.tokens
                .filter { it.address == tokenAddr || vm.tokenBalances[it.address]?.signum() != 0 }
                .forEach { t ->
                    MenuItem(
                        t.symbol,
                        t.address == tokenAddr,
                        vm.amountText(vm.tokenBalances[t.address], t.decimals)
                    ) {
                        onPick(t.address)
                    }
                }
            MenuLine()
            MenuItem("Add token…", false, color = Dim.Accent, onClick = onAdd)
        }
    }
}
