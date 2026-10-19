package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.TxRecord
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

@Composable
fun ActivityScreen(activity: FragmentActivity, vm: WalletViewModel, address: String) {
    val items = vm.history.filter { it.sender.equals(address, true) }
    val anyPending = items.any { it.status == 0 }
    var open by remember { mutableStateOf<TxRecord?>(null) }

    LaunchedEffect(Unit) { vm.refreshHistory() }
    // while something is pending and this tab is open, keep checking
    LaunchedEffect(anyPending) {
        while (anyPending) {
            delay(15_000.milliseconds)
            vm.refreshHistory()
        }
    }

    fun who(addr: String) = vm.contacts.firstOrNull { it.address.equals(addr, true) }?.name ?: shortAddress(addr)
    fun chainName(id: Long) = vm.chains.firstOrNull { it.chainId == id }?.name ?: "Chain $id"
    fun amountOf(r: TxRecord) = Rpc.formatUnits(BigInteger(r.amount), r.decimals, 6) + " " + r.symbol

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScreenTitle("Activity")
                RefreshButton(vm.historyRefreshing) { vm.refreshHistory() }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "Transactions you send from this wallet appear here, from every chain.",
                    fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
        items(items, key = { "${it.chainId}:${it.hash}" }) { r ->
            val title = when (r.kind) {
                "call" -> r.dapp ?: "Contract call"
                else -> "Sent"
            }
            val sub = if (r.kind == "call") {
                (r.note ?: "To ${who(r.recipient)}") + " · " + chainName(r.chainId)
            } else {
                "To ${who(r.recipient)} · ${chainName(r.chainId)}"
            }
            Hairline()
            Row(
                Modifier.fillMaxWidth().clickable { open = r }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Dim.Avatar),
                    contentAlignment = Alignment.Center
                ) { Icon(Ic.Send, null, Modifier.size(20.dp), tint = Dim.Mid) }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(sub, fontSize = 13.sp, color = Dim.Mid, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (r.kind != "call" || r.amount != "0") {
                        Text("−" + amountOf(r), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    when (r.status) {
                        0 -> Text("Pending", fontSize = 13.sp, color = Dim.Accent)
                        2 -> Text("Failed", fontSize = 13.sp, color = Dim.Danger)
                        else -> Text(
                            DateUtils.getRelativeTimeSpanString(
                                r.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            ).toString(),
                            fontSize = 13.sp, color = Dim.Mid
                        )
                    }
                }
            }
        }
        item {
            if (items.isNotEmpty()) Hairline()
            Spacer(Modifier.height(24.dp))
        }
    }

    open?.let { r ->
        val chain = vm.chains.firstOrNull { it.chainId == r.chainId }
        var confirmRemove by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(if (r.kind == "call") "Contract call" else "Sent") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow(
                        "Status",
                        when (r.status) { 0 -> "Pending"; 1 -> "Confirmed"; else -> "Failed (reverted)" },
                        color = if (r.status == 2) Dim.Danger else Dim.Ink
                    )
                    DetailRow("Network", chain?.name ?: "Chain ${r.chainId} (not saved)")
                    DetailRow("Time", DateFormat.getDateTimeInstance().format(Date(r.createdAt)))
                    if (r.kind != "call" || r.amount != "0") DetailRow("Amount", amountOf(r))
                    r.dapp?.let { DetailRow("Site", it) }
                    r.note?.let { DetailRow("Action", it) }
                    val name = vm.contacts.firstOrNull { it.address.equals(r.recipient, true) }?.name
                    DetailRow("To" + (name?.let { " · $it" } ?: ""), r.recipient, mono = true)
                    r.tokenAddress?.let { DetailRow("Token contract", it, mono = true) }
                    DetailRow("Transaction hash", r.hash, mono = true)
                    Row {
                        TextButton(onClick = {
                            activity.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("hash", r.hash))
                        }) { Text("Copy hash") }
                        TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Dim.Danger) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            dismissButton = {
                if (chain?.explorerUrl != null) {
                    TextButton(onClick = {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, "${chain.explorerUrl}/tx/${r.hash}".toUri())
                        )
                    }) { Text("Explorer") }
                }
            }
        )
        if (confirmRemove) {
            ConfirmDialog(
                title = "Remove from history?",
                text = "Only removes this entry on this phone. The transaction stays on the blockchain.",
                confirmLabel = "Remove",
                onConfirm = { vm.removeTx(r.chainId, r.hash); confirmRemove = false; open = null },
                onDismiss = { confirmRemove = false }
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, color: Color = Dim.Ink) {
    Column {
        Text(label, fontSize = 13.sp, color = Dim.Mid)
        Text(
            value,
            fontSize = if (mono) 13.sp else 15.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = color
        )
    }
}
