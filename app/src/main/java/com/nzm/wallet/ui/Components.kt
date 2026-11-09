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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Batch
import com.nzm.wallet.chain.BatchItem
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.WalletRow
import com.nzm.wallet.storage.ChainIcon
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.web3j.crypto.Credentials
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Dim.Line)
    )
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .wrapContentHeight(Alignment.CenterVertically)
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dim.Mid)
}

@Composable
fun TabHeader(title: String, chain: Chain?, onChainMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScreenTitle(title)
        if (chain != null) {
            ChainChip(chain, onChainMenu)
        }
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
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
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
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
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
fun ChainChip(chain: Chain, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChainIcon(chain, 28.dp)
        Text(
            chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f, fill = false)
                .basicMarquee(iterations = Int.MAX_VALUE)
        )
        Icon(Ic.ChevronDown, null, Modifier.size(16.dp))
    }
}

/** Name shown in the header: ENS, else your own name, else short address. */
fun WalletViewModel.walletLabel(address: String): String {
    val custom = activeWallet?.name?.takeIf { !Regex("Wallet \\d+").matches(it) }
    return ensName ?: custom ?: shortAddress(address)
}

@Composable
fun WalletChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .border(1.5.dp, Dim.Outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f, fill = false)
                .basicMarquee(iterations = Int.MAX_VALUE)
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
            .size(30.dp)
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
fun QrButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Ic.Qr, "Show QR code", Modifier.size(18.dp), tint = Dim.Mid)
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
        Icon(
            Ic.Refresh, "Refresh", Modifier
                .size(20.dp)
                .rotate(angle), tint = Dim.Mid
        )
    }
}

@Composable
fun HideBalancesButton(hidden: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (hidden) Ic.EyeOff else Ic.Eye,
            if (hidden) "Show balances" else "Hide balances",
            Modifier.size(22.dp),
            tint = Dim.Mid
        )
    }
}

@Composable
fun AssetRow(
    initial: String,
    icon: (@Composable () -> Unit)? = null,
    title: String,
    subtitle: String,
    amount: String,
    fiat: String?,
    native: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Column {
        Hairline()
        val base = Modifier
            .fillMaxWidth()
            .height(72.dp)
        Row(
            if (onClick != null) base.clickable(onClick = onClick) else base,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (icon != null) icon() else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (native) Dim.Ink else Dim.Avatar),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        initial,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (native) Dim.OnAccent else Dim.Ink
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = Dim.Mid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, fontSize = 16.sp, maxLines = 1)
                if (fiat != null) Text(fiat, fontSize = 13.sp, color = Dim.Mid)
            }
        }
    }
}

@Composable
fun SettingsRow(
    label: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onClick),
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
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = Dim.Danger
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun batchLineText(vm: WalletViewModel, b: BatchItem): String {
    val who = b.name ?: rememberParty(vm, b.recipient ?: b.to).main
    return "${b.line} ${b.link} $who"
}

/** True when [account] is delegated on [chain] to a contract that runs ERC-7821 batches. */
@Composable
fun rememberBatchSupport(chain: Chain?, account: String): Boolean {
    var ok by remember(chain?.chainId, account) { mutableStateOf(false) }
    LaunchedEffect(chain?.chainId, account) {
        ok = false
        if (chain == null) return@LaunchedEffect
        ok = try {
            Batch.status(chain, account).supportsBatch
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    return ok
}

@Composable
internal fun WcSiteHeader(
    name: String?,
    url: String?,
    verified: Boolean,
    warn: String,
    blocked: Boolean,
    otherWallet: String?
) {
    var info by remember { mutableStateOf(false) }

    Column(
        Modifier
            .clickable { info = true }
            .padding(top = 4.dp)
    ) {
        Text(
            name?.ifBlank { null } ?: "Unknown site",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (verified) TxOk else Dim.Danger,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            url ?: "",
            fontSize = 14.sp,
            color = Dim.Mid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (blocked) Text(warn, fontSize = 14.sp, color = Dim.Danger)
    if (otherWallet != null) {
        Text(
            "Not your active wallet. This will be signed by $otherWallet.",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger
        )
    }

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text("Domain check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(warn, color = if (blocked || !verified) Dim.Danger else TxOk)
                    Text("Site says: ${url ?: "unknown"}", fontSize = 14.sp, color = Dim.Mid)
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } }
        )
    }
}

@Stable
class BusyRunner(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun launch(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberBusyRunner(): BusyRunner {
    val scope = rememberCoroutineScope()
    return remember { BusyRunner(scope) }
}

@Stable
class SessionSigner(
    val address: String?,
    val row: WalletRow?,
    val isActive: Boolean,
    private val vault: Vault,
    private val activity: FragmentActivity
) {
    val otherWallet: String?
        get() = if (row != null && address != null && !isActive)
            "${row.name} (${shortAddress(address)})" else null

    suspend fun <T> sign(block: suspend (Credentials) -> T): T {
        val r = row ?: error("Wallet not found")
        val a = address ?: error("Wallet not found")
        return vault.withCredentials(activity, r.id) { c ->
            check(c.address.equals(a, ignoreCase = true)) {
                "Wallet key does not match the site's account"
            }
            block(c)
        }
    }
}

@Composable
fun rememberSessionSigner(
    vm: WalletViewModel,
    vault: Vault,
    activity: FragmentActivity,
    address: String,
    topic: String,
    key: Any
): SessionSigner {
    val signer by produceState(WcService.sessionAddress(topic), key) {
        var tries = 0
        while (value == null && tries < 10) {
            delay(300.milliseconds)
            value = WcService.sessionAddress(topic)
            tries++
        }
    }
    val s = signer
    val row = vm.wallets.firstOrNull { it.address.equals(s, ignoreCase = true) }
        ?: if (s != null && s.equals(address, ignoreCase = true)) vm.activeWallet else null
    val active = s != null && s.equals(address, ignoreCase = true)
    return SessionSigner(s, row, active, vault, activity)
}