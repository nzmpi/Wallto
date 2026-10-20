package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

fun shortAddress(a: String) = a.take(6) + "…" + a.takeLast(4)

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Dim.Line))
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        modifier = Modifier.heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically)
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dim.Mid)
}

@Composable
fun TabHeader(title: String, chainName: String?, onChainMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScreenTitle(title)
        if (chainName != null) ChainChip(chainName, onChainMenu)
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(Ic.ChevronLeft, "Back", Modifier.size(24.dp))
        }
        Box(Modifier.offset(x = (-12).dp)) { ScreenTitle(title) }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Dim.Accent,
            contentColor = Dim.OnAccent,
            disabledContainerColor = Dim.Raised,
            disabledContentColor = Dim.Mid
        )
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.5.dp, Dim.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim.Ink)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChainChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Icon(Ic.ChevronDown, null, Modifier.size(16.dp))
    }
}

/** Name shown in the header: ENS, else your own name, else short address. */
fun WalletViewModel.walletLabel(address: String): String {
    val custom = activeWallet?.name?.takeIf { !Regex("Wallet \\d+").matches(it) }
    return ensName ?: custom ?: shortAddress(address)
}

@Composable
fun WalletChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.5.dp, Dim.Outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)
        )
        Icon(Ic.ChevronDown, "Switch wallet", Modifier.size(14.dp), tint = Dim.Mid)
    }
}

@Composable
fun CopyAddressButton(address: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .offset((-12).dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button) {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("address", address))
                copied = true
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (copied) Ic.Check else Ic.Copy,
            if (copied) "Copied" else "Copy address",
            Modifier.size(18.dp),
            tint = if (copied) Dim.Accent else Dim.Mid
        )
    }
}

@Composable
fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val angle = if (spinning) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "angle"
        ).value
    } else 0f
    IconButton(onClick = onClick) {
        Icon(Ic.Refresh, "Refresh", Modifier.size(20.dp).rotate(angle), tint = Dim.Mid)
    }
}

@Composable
fun AssetRow(
    initial: String,
    title: String,
    subtitle: String,
    amount: String,
    fiat: String?,
    native: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Column {
        Hairline()
        val base = Modifier.fillMaxWidth().height(72.dp)
        Row(
            if (onClick != null) base.clickable(onClick = onClick) else base,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (native) Dim.Ink else Dim.Avatar),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initial,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (native) Dim.OnAccent else Dim.Ink
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 13.sp, color = Dim.Mid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, fontSize = 16.sp, maxLines = 1)
                if (fiat != null) Text(fiat, fontSize = 13.sp, color = Dim.Mid)
            }
        }
    }
}

@Composable
fun SettingsRow(label: String, value: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Column {
        Hairline()
        Row(
            Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Normal,
                color = if (danger) Dim.Danger else Dim.Ink,
                modifier = Modifier.weight(1f)
            )
            if (value != null) Text(value, fontSize = 14.sp, color = Dim.Mid)
            if (!danger) Icon(Ic.ChevronRight, null, Modifier.size(16.dp), tint = Dim.Mid)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Dim.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}