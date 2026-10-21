package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.TxRecord
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

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

    fun chainName(id: Long) = vm.chains.firstOrNull { it.chainId == id }?.name ?: "Chain $id"
    fun amountOf(r: TxRecord) = amountText(r)

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
            val to = rememberParty(vm, r.recipient).main
            val sub = if (r.kind == "call") {
                (r.note ?: "To $to") + " · " + chainName(r.chainId)
            } else {
                "To $to · ${chainName(r.chainId)}"
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
                        Text("− " + amountOf(r), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
        ActivityDetail(activity, vm, r) { open = null }
    }
}

private fun amountText(r: TxRecord) = Rpc.formatUnits(BigInteger(r.amount), r.decimals, 6) + " " + r.symbol

@Composable
private fun ActivityDetail(activity: FragmentActivity, vm: WalletViewModel, r: TxRecord, onClose: () -> Unit) {
    val chain = vm.chains.firstOrNull { it.chainId == r.chainId }
    var confirmRemove by remember { mutableStateOf(false) }
    val isCall = r.kind == "call"
    val hasAmount = !isCall || r.amount != "0"

    TxPage(
        onDismiss = onClose,
        bottom = {
            OutlineButton("Copy hash", {
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("hash", r.hash))
            }, Modifier.weight(1f))
            if (chain?.explorerUrl != null) {
                PrimaryButton("Explorer", {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, "${chain.explorerUrl}/tx/${r.hash}".toUri()))
                }, Modifier.weight(1f))
            } else {
                PrimaryButton("Close", onClose, Modifier.weight(1f))
            }
        }
    ) {
        PageHeader(if (isCall) "Contract call" else "Sent", onClose)

        if (isCall && r.dapp != null) {
            Text(r.dapp, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (hasAmount) TxAmount(if (isCall) "Also sent" else "Sent", amountText(r))
        else TxAmount("Called", r.note ?: "Contract call")

        TxCard {
            TxPartyRow("From", rememberParty(vm, r.sender))
            TxLine()
            TxPartyRow(if (isCall) "Contract" else "To", rememberParty(vm, r.recipient))
        }

        TxCard {
            TxRow(
                "Status",
                when (r.status) { 0 -> "Pending"; 1 -> "Confirmed"; else -> "Failed (reverted)" },
                when (r.status) { 0 -> Dim.Accent; 1 -> TxOk; else -> Dim.Danger }
            )
            TxLine()
            TxRow("Network", chain?.name ?: "Chain ${r.chainId} (not saved)")
            TxLine()
            TxRow("Time", DateFormat.getDateTimeInstance().format(Date(r.createdAt)))
            if (isCall && hasAmount && r.note != null) {
                TxLine()
                TxRow("Action", r.note)
            }
        }

        TxAdvanced {
            r.tokenAddress?.let {
                TxLabel("Token contract", top = 0)
                TxMono(it)
            }
            TxLabel("Transaction hash", top = if (r.tokenAddress != null) 12 else 0)
            TxMono(r.hash)
        }

        TxCard(Modifier.clickable { confirmRemove = true }) {
            Text(
                "Remove from history", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center
            )
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove from history?",
            text = "Only removes this entry on this phone. The transaction stays on the blockchain.",
            confirmLabel = "Remove",
            onConfirm = { vm.removeTx(r.chainId, r.hash); confirmRemove = false; onClose() },
            onDismiss = { confirmRemove = false }
        )
    }
}
