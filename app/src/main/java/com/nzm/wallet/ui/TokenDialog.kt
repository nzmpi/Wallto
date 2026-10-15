package com.nzm.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Token
import com.nzm.wallet.chain.TokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TokenDialog(chain: Chain, repo: TokenRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Token?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Add token on ${chain.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = found
                if (f == null) {
                    OutlinedTextField(
                        address, { address = it }, label = { Text("Token contract address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Symbol: ${f.symbol}")
                    Text("Name: ${f.name}")
                    Text("Decimals: ${f.decimals}")
                    Text("Contract: ${f.address}")
                    Text(
                        "Anyone can create a token with any name or symbol. " +
                                "Confirm this contract address from the project's official site or an explorer.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (busy) Text("Working...")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val f = found
                busy = true
                error = null
                scope.launch {
                    try {
                        if (f == null) {
                            found = Erc20.fetchInfo(chain.rpcUrl, chain.chainId, address)
                        } else {
                            repo.add(f)
                            onClose()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Failed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (found == null) "Look up" else "Add") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (found != null) found = null else onClose() }) {
                Text(if (found != null) "Back" else "Cancel")
            }
        }
    )
}
