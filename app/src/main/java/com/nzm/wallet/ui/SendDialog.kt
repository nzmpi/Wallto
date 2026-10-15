package com.nzm.wallet.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.*
import com.nzm.wallet.storage.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigInteger
import androidx.core.net.toUri

@Composable
fun SendDialog(
    activity: FragmentActivity,
    vault: Vault,
    chain: Chain,
    from: String,
    onClose: (sent: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("Pending...") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fmt(v: BigInteger) = "${Rpc.formatUnits(v, chain.decimals, 8)} ${chain.symbol}"

    fun launchSafe(block: suspend () -> Unit) {
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

    LaunchedEffect(hash) {
        val h = hash ?: return@LaunchedEffect
        result = try {
            when (SendTx.waitForReceipt(chain.rpcUrl, h)) {
                true -> "Confirmed"
                false -> "FAILED (reverted)"
                null -> "Not mined yet. Check explorer."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Status unknown: ${e.message}"
        }
    }

    val p = plan
    val h = hash

    AlertDialog(
        onDismissRequest = { if (!busy && h == null) onClose(false) },
        title = { Text(if (h != null) "Sent" else if (p != null) "Review" else "Send ${chain.symbol}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    h != null -> {
                        Text("Status: $result")
                        Text("Hash: $h")
                    }
                    p != null -> {
                        if (chain.isTestnet) Text("Network: ${chain.name} (testnet)")
                        else Text("REAL FUNDS on ${chain.name}", color = MaterialTheme.colorScheme.error)
                        Text("From: ${p.from}")
                        Text("To: ${p.to}")
                        Text("Amount: ${fmt(p.value)}")
                        Text("Max network fee: ${fmt(p.maxFeeWei)}")
                        Text("Max total: ${fmt(p.maxTotalWei)}")
                        Text("Check the full recipient address, not only first and last characters.")
                    }
                    else -> {
                        OutlinedTextField(
                            to, { to = it }, label = { Text("Recipient address") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            amount, { amount = it }, label = { Text("Amount (${chain.symbol})") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                }
                if (busy) Text("Working...")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when {
                    h != null -> onClose(true)
                    p != null -> launchSafe {
                        hash = vault.withCredentials(activity) { creds -> SendTx.signAndSend(p, creds) }
                    }
                    else -> launchSafe {
                        val dest = to.trim()
                        require(SendTx.isValidAddress(dest)) { "Invalid recipient address or checksum" }
                        val value = SendTx.parseAmount(amount, chain.decimals)
                        plan = SendTx.plan(chain, from, dest, value)
                    }
                }
            }) { Text(if (h != null) "Done" else if (p != null) "Sign & send" else "Review") }
        },
        dismissButton = {
            when {
                h != null -> if (chain.explorerUrl != null) {
                    TextButton(onClick = {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, "${chain.explorerUrl}/tx/$h".toUri())
                        )
                    }) { Text("Explorer") }
                }
                p != null -> TextButton(enabled = !busy, onClick = { plan = null }) { Text("Back") }
                else -> TextButton(enabled = !busy, onClick = { onClose(false) }) { Text("Cancel") }
            }
        }
    )
}
