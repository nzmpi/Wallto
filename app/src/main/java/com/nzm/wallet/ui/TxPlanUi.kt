package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Helpers.fmt

@Stable
class PlanEditor {
    var plan by mutableStateOf<TxPlan?>(null)
        private set
    var base by mutableStateOf<TxPlan?>(null)
        private set
    internal var feeOpen by mutableStateOf(false)
    internal var nonceOpen by mutableStateOf(false)
    internal var gasOpen by mutableStateOf(false)

    fun load(p: TxPlan) {
        plan = p
        base = p
    }

    fun clear() {
        plan = null
        base = null
        feeOpen = false
        nonceOpen = false
        gasOpen = false
    }

    internal fun update(p: TxPlan) {
        plan = p
    }
}

@Composable
fun rememberPlanEditor(): PlanEditor = remember { PlanEditor() }

@Composable
internal fun TxNetworkCard(chain: Chain) {
    TxCard {
        Row(Modifier
            .fillMaxWidth()
            .height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Network", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
            Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
        }
    }
}

@Composable
internal fun TxFeeCard(
    vm: WalletViewModel,
    chain: Chain,
    editor: PlanEditor,
    busy: Boolean,
    waiting: String = "Preparing…"
) {
    val p = editor.plan
    val base = editor.base
    TxCard(Modifier.clickable(enabled = p != null && !busy) { editor.feeOpen = true }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Max fee", fontSize = 15.sp, color = Dim.Mid)
                if (p != null && base != null && p.fee != base.fee) {
                    Text("Edited", fontSize = 12.sp, color = Dim.Accent)
                }
            }
            if (p == null) {
                Text(waiting, fontSize = 15.sp, color = Dim.Mid)
            } else {
                val fiat =
                    if (chain.chainId == vm.selected?.chainId) vm.fiat(null, p.maxFeeWei) else null
                val coin = fmt(p.maxFeeWei, chain)
                Column(horizontalAlignment = Alignment.End) {
                    Text(fiat ?: coin, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (fiat != null) Text(coin, fontSize = 12.sp, color = Dim.Mid)
                }
                Spacer(Modifier.width(10.dp))
                Icon(Ic.Edit, "Edit fee", Modifier.size(18.dp), tint = Dim.Mid)
            }
        }
    }
}

@Composable
internal fun TxPlanRows(editor: PlanEditor, busy: Boolean) {
    val p = editor.plan ?: return
    EditRow("Nonce", p.nonce.toString(), "Edit nonce", busy) { editor.nonceOpen = true }
    EditRow("Gas limit", p.gasLimit.toString(), "Edit gas limit", busy) { editor.gasOpen = true }
}

@Composable
private fun EditRow(
    label: String,
    value: String,
    description: String,
    busy: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Icon(Ic.Edit, description, Modifier.size(16.dp), tint = Dim.Mid)
    }
}

@Composable
internal fun PlanEditorDialogs(editor: PlanEditor) {
    val p = editor.plan
    val base = editor.base
    if (editor.feeOpen && p != null && base != null) {
        TxFeeDialog(
            plan = p, base = base,
            onSave = { editor.update(p.copy(fee = it)); editor.feeOpen = false },
            onDismiss = { editor.feeOpen = false }
        )
    }
    if (editor.nonceOpen && p != null) {
        TxNonceDialog(
            current = p.nonce,
            onSave = { editor.update(p.copy(nonce = it)); editor.nonceOpen = false },
            onDismiss = { editor.nonceOpen = false }
        )
    }
    if (editor.gasOpen && p != null && base != null) {
        TxGasDialog(
            current = p.gasLimit,
            base = base.gasLimit,
            onSave = { editor.update(p.copy(gasLimit = it)); editor.gasOpen = false },
            onDismiss = { editor.gasOpen = false }
        )
    }
}
