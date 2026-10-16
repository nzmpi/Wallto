package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.Token
import kotlinx.coroutines.CancellationException
import java.math.BigInteger
import androidx.core.net.toUri

@Composable
fun TokenInfoDialog(
    activity: FragmentActivity,
    chain: Chain,
    token: Token,
    owner: String,
    onClose: () -> Unit
) {
    var raw by remember { mutableStateOf<BigInteger?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token.address) {
        try {
            raw = Erc20.balanceOf(chain.rpcUrl, token.address, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            balanceError = e.message ?: "error"
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(token.symbol) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name: ${token.name}")
                    Text("Symbol: ${token.symbol}")
                    Text("Decimals: ${token.decimals}")
                    val r = raw
                    when {
                        r != null -> {
                            Text("Balance: ${Rpc.formatUnits(r, token.decimals, token.decimals)} ${token.symbol}")
                            Text("Raw units: $r")
                        }
                        balanceError != null ->
                            Text("Balance: error: $balanceError", color = MaterialTheme.colorScheme.error)
                        else -> Text("Balance: loading...")
                    }
                    Text("Network: ${chain.name} (chain ID ${token.chainId})")
                    Text("Contract: ${token.address}")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    activity.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("token", token.address))
                }) { Text("Copy address") }
                if (chain.explorerUrl != null) {
                    TextButton(onClick = {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                "${chain.explorerUrl}/address/${token.address}".toUri())
                        )
                    }) { Text("Explorer") }
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}
