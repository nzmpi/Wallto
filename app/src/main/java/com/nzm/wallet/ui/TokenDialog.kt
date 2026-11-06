package com.nzm.wallet.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.dbs.TokenRepository
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.SHAPE
import com.nzm.wallet.utils.Helpers.isValidAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys

@Composable
fun TokenDialog(chain: Chain, repo: TokenRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Token?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val f = found

    fun next() {
        busy = true
        error = null
        scope.launch {
            try {
                if (f == null) {
                    val input = address.trim()
                    require(isValidAddress(input)) { "Invalid contract address or checksum" }
                    repo.get(chain.chainId, Keys.toChecksumAddress(input))?.let {
                        throw IllegalArgumentException("Already added: ${it.symbol} (${it.name})")
                    }
                    found = Erc20.fetchInfo(chain.rpcUrl, chain.chainId, input)
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
    }

    TxPage(
        onDismiss = { if (!busy) onClose() },
        bottom = {
            OutlineButton(
                if (f != null) "Back" else "Cancel",
                {
                    if (!busy) {
                        if (f != null) found = null else onClose()
                    }
                },
                Modifier.weight(1f)
            )
            PrimaryButton(
                if (busy) "Working…" else if (f == null) "Look up" else "Add",
                onClick = { next() },
                enabled = !busy && (f != null || address.isNotBlank()),
                modifier = Modifier.weight(1f)
            )
        }
    ) {
        PageHeader("Add token") { if (!busy) onClose() }

        TxCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Network", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
                Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
            }
        }

        if (f == null) {
            OutlinedTextField(
                address, { address = it }, label = { Text("Token contract address") },
                modifier = Modifier.fillMaxWidth(),
                shape = SHAPE
            )
        } else {
            TxAmount("Token", f.symbol)
            TxCard {
                TxRow("Name", f.name)
                TxLine()
                TxRow("Symbol", f.symbol)
                TxLine()
                TxRow("Decimals", f.decimals.toString())
            }
            TxCard {
                Text("Contract", fontSize = 15.sp, color = Dim.Mid)
                Spacer(Modifier.height(6.dp))
                TxMono(f.address)
            }
            Text(
                "Anyone can create a token with any name or symbol. " +
                        "Confirm this contract address from the project's official site or an explorer.",
                fontSize = 14.sp, color = Dim.Danger
            )
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
    }
}
