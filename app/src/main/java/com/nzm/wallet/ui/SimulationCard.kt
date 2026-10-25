package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SimulationCard(vm: WalletViewModel, chain: Chain, plan: TxPlan) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(SimKey.get(ctx)) }
    var keyDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember(plan.from, plan.to, plan.value, plan.data) { mutableStateOf<SimResult?>(null) }
    var error by remember(plan.from, plan.to, plan.value, plan.data) { mutableStateOf<String?>(null) }

    fun run(k: String) {
        busy = true
        error = null
        scope.launch {
            try {
                result = TxSim.run(chain, plan, k, vm.tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = null
                error = e.message ?: "Simulation failed"
            } finally {
                busy = false
            }
        }
    }

    val supported = TxSim.supported(chain.chainId)
    TxCard(Modifier.clickable(enabled = !busy && supported) {
        val k = key
        if (k == null) keyDialog = true else run(k)
    }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Simulation", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
            Text(
                when {
                    !supported -> "Not available on ${chain.name}"
                    busy -> "Simulating…"
                    key == null -> "Add Alchemy key"
                    result != null || error != null -> "Tap to run again"
                    else -> "Tap to simulate"
                },
                fontSize = 14.sp, color = if (supported) Dim.Accent else Dim.Mid
            )
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger, modifier = Modifier.padding(top = 6.dp)) }
        result?.let { r ->
            Spacer(Modifier.height(6.dp))
            if (r.failure != null) {
                Text(r.failure, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger)
            } else {
                if (r.lines.isEmpty() && r.nfts.isEmpty()) {
                    Text("No token or coin changes for your address.", fontSize = 14.sp, color = Dim.Mid)
                }
                r.lines.forEach { l ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(if (l.incoming) "You receive" else "You send", fontSize = 12.sp, color = Dim.Mid)
                        Text(
                            (if (l.incoming) "+ " else "− ") + l.amount.removePrefix("≈ ") + " " + l.symbol,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            color = if (l.incoming) TxOk else Dim.Danger
                        )
                        l.unlistedAddress?.let {
                            Text("Not in your token list: ${shortAddress(it)}", fontSize = 12.sp, color = Dim.Mid)
                        }
                    }
                }
                r.nfts.forEach {
                    Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (it.startsWith("+")) TxOk else Dim.Danger, modifier = Modifier.padding(vertical = 4.dp))
                }
                r.approvals.forEach { a ->
                    Text(
                        "Approves ${a.spender} to spend " + (if (a.unlimited) "UNLIMITED" else a.amount) + " ${a.symbol}",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                r.gasUsed?.let { Text("Gas used: $it", fontSize = 12.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }

    if (keyDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
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
                        label = { Text(if (key != null) "New key" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    SimKey.set(ctx, text)
                    key = SimKey.get(ctx)
                    keyDialog = false
                    key?.let { run(it) }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (key != null) TextButton(onClick = {
                        SimKey.set(ctx, null); key = null; keyDialog = false
                    }) { Text("Remove", color = Dim.Danger) }
                    TextButton(onClick = { keyDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}
