package com.nzm.wallet.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.AbiCodec
import com.nzm.wallet.abi.FunctionSig
import com.nzm.wallet.chain.*
import com.nzm.wallet.storage.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import androidx.core.net.toUri

private class Prepared(val to: String, val sig: FunctionSig, val values: List<Any>, val data: ByteArray)

private suspend fun prepare(chain: Chain, addr: String, sigText: String, argsText: String): Prepared {
    require(SendTx.isValidAddress(addr.trim())) { "Invalid contract address or checksum" }
    val to = Keys.toChecksumAddress(addr.trim())
    val sig = AbiCodec.parseSignature(sigText)
    val lines = if (sig.inputs.isEmpty()) emptyList()
    else argsText.replace("\r", "").trimEnd('\n').split("\n")
    require(lines.size == sig.inputs.size) {
        "Expected ${sig.inputs.size} argument(s), one per line. Got ${lines.size}."
    }
    val values = sig.inputs.mapIndexed { i, t ->
        try {
            AbiCodec.parseValue(t, lines[i])
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Argument ${i + 1} (${t.canonical}): ${e.message}")
        }
    }
    require(Erc20.isContract(chain.rpcUrl, to)) { "No contract at this address on this chain" }
    return Prepared(to, sig, values, AbiCodec.encodeCall(sig, values))
}

@Composable
fun ContractDialog(
    activity: FragmentActivity,
    vault: Vault,
    chain: Chain,
    from: String,
    onClose: (sent: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var write by remember { mutableStateOf(false) }
    var contract by remember { mutableStateOf("") }
    var sigText by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<Prepared?>(null) }
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var readResult by remember { mutableStateOf<List<String>?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Pending...") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fmt(v: BigInteger) = "${Rpc.formatUnits(v, chain.decimals, chain.decimals)} ${chain.symbol}"

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
        status = try {
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

    val parsed = remember(sigText) { if (sigText.isBlank()) null else runCatching { AbiCodec.parseSignature(sigText) } }
    val p = plan
    val h = hash
    val prep = prepared

    AlertDialog(
        onDismissRequest = { if (!busy && h == null) onClose(false) },
        title = {
            Text(
                when {
                    h != null -> "Sent"
                    p != null -> "Review"
                    else -> "Contract call"
                }
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    h != null -> {
                        Text("Status: $status")
                        Text("Hash: $h")
                    }
                    p != null && prep != null -> {
                        if (chain.isTestnet) Text("Network: ${chain.name} (Testnet)")
                        else Text("REAL FUNDS on ${chain.name}", color = MaterialTheme.colorScheme.error)
                        Text("From: ${p.from}")
                        Text("Contract: ${p.to}")
                        Text("Function: ${prep.sig.signature}")
                        Text("Selector: 0x${Numeric.toHexStringNoPrefix(prep.sig.selector)}")
                        prep.values.forEachIndexed { i, v ->
                            val t = prep.sig.inputs[i]
                            Text("Arg ${i + 1} (${t.canonical}): ${AbiCodec.display(t, v)}")
                        }
                        Text("Value: ${fmt(p.value)}")
                        Text("Max network fee: ${fmt(p.maxFeeWei)}")
                        Text("Max total: ${fmt(p.maxTotalWei)}")
                        AbiCodec.riskNotes(prep.sig, prep.values).forEach {
                            Text("WARNING: $it", color = MaterialTheme.colorScheme.error)
                        }
                        Text("Calldata: ${p.data}")
                        Text("Only sign calls you understand. A site or chat telling you to call a contract is a common scam.")
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !write, onClick = { write = false; readResult = null })
                            Text("Read")
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected = write, onClick = { write = true; readResult = null })
                            Text("Write")
                        }
                        OutlinedTextField(
                            contract, { contract = it }, label = { Text("Contract address") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            sigText, { sigText = it },
                            label = { Text("Function, e.g. balanceOf(address) returns (uint256)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        parsed?.let { r ->
                            val s = r.getOrNull()
                            if (s != null) {
                                val args = if (s.inputs.isEmpty()) "no arguments"
                                else s.inputs.mapIndexed { i, t -> "${i + 1}=${t.canonical}" }.joinToString("  ")
                                Text(
                                    "0x${Numeric.toHexStringNoPrefix(s.selector)}  ${s.signature}\nArgs: $args",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(
                                    r.exceptionOrNull()?.message ?: "Bad signature",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        OutlinedTextField(
                            argsText, { argsText = it }, label = { Text("Arguments, one per line") },
                            minLines = 2, modifier = Modifier.fillMaxWidth()
                        )
                        if (write) {
                            OutlinedTextField(
                                valueText, { valueText = it },
                                label = { Text("Value (${chain.symbol}, optional)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                        readResult?.let { r ->
                            Text("Result:")
                            r.forEach { Text(it) }
                        }
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
                        readResult = null
                        val pr = prepare(chain, contract, sigText, argsText)
                        if (!write) {
                            val raw = ContractCall.read(chain.rpcUrl, from, pr.to, pr.data)
                            readResult = if (pr.sig.outputs.isEmpty()) {
                                listOf("Raw output: ${Numeric.toHexString(raw)}")
                            } else {
                                AbiCodec.decodeOutputs(pr.sig.outputs, raw)
                                    .mapIndexed { i, v -> "${i + 1}. ${pr.sig.outputs[i].canonical}: $v" }
                            }
                        } else {
                            val value =
                                if (valueText.isBlank()) BigInteger.ZERO
                                else SendTx.parseAmount(valueText, chain.decimals)
                            plan = SendTx.plan(chain, from, pr.to, value, Numeric.toHexString(pr.data))
                            prepared = pr
                        }
                    }
                }
            }) { Text(if (h != null) "Done" else if (p != null) "Sign & send" else if (write) "Review" else "Call") }
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
                p != null -> TextButton(enabled = !busy, onClick = { plan = null; prepared = null }) { Text("Back") }
                else -> TextButton(enabled = !busy, onClick = { onClose(false) }) { Text("Close") }
            }
        }
    )
}
