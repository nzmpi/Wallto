package com.nzm.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.ChainRepository
import com.nzm.wallet.utils.Constants.SHAPE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChainDialog(repo: ChainRepository, existing: Chain?, onDone: (Chain?) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var rpc by remember { mutableStateOf(existing?.rpcUrl ?: "") }
    var id by remember { mutableStateOf(existing?.chainId?.toString() ?: "") }
    var symbol by remember { mutableStateOf(existing?.symbol ?: "") }
    var explorer by remember { mutableStateOf(existing?.explorerUrl ?: "") }
    var testnet by remember { mutableStateOf(existing?.isTestnet ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text(if (existing == null) "Add EVM chain" else "Edit chain") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = SHAPE
                )
                OutlinedTextField(
                    rpc,
                    { rpc = it },
                    label = { Text("RPC URL (https)") },
                    singleLine = true,
                    shape = SHAPE
                )
                OutlinedTextField(
                    id,
                    { id = it },
                    label = { Text("Chain ID") },
                    singleLine = true,
                    shape = SHAPE,
                    enabled = existing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    symbol,
                    { symbol = it },
                    label = { Text("Currency symbol") },
                    singleLine = true,
                    shape = SHAPE
                )
                OutlinedTextField(
                    explorer,
                    { explorer = it },
                    label = { Text("Explorer URL (optional)") },
                    singleLine = true,
                    shape = SHAPE
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(testnet, { testnet = it })
                    Text("Testnet")
                }
                if (busy) Text("Saving...")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        onDone(repo.saveChain(existing, name, rpc, id, symbol, explorer, testnet))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Failed"
                        busy = false
                    }
                }
            }) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = { onDone(null) }) { Text("Cancel") }
        }
    )
}