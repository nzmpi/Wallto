package com.nzm.wallet.ui

import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SendDialog(
    activity: FragmentActivity,
    vault: Vault,
    chain: Chain,
    from: String,
    tokens: List<Token>,
    initialToken: Token?, // null = native coin
    ensRpc: String? = null,
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

    var token by remember { mutableStateOf(initialToken) }
    var pickerOpen by remember { mutableStateOf(false) }
    val symbol = token?.symbol ?: chain.symbol
    val decimals = token?.decimals ?: chain.decimals

    var resolved by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var ensError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(to) {
        resolved = null
        ensError = null
        resolving = false
        val name = to.trim()
        if (!Ens.looksLikeName(name)) return@LaunchedEffect
        if (ensRpc == null) {
            ensError = "Add Ethereum Mainnet (chain ID 1) to resolve ENS names"
            return@LaunchedEffect
        }
        delay(600.milliseconds) // wait for typing to stop
        resolving = true
        try {
            resolved = Ens.resolve(ensRpc, name) ?: run { ensError = "This name has no address"; null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensError = e.message ?: "Could not resolve"
        } finally {
            resolving = false
        }
    }

    // review shows exact amounts: full precision, no rounding
    fun fmt(v: BigInteger, dec: Int, sym: String) = "${Rpc.formatUnits(v, dec, dec)} $sym"
    fun fmtNative(v: BigInteger) = fmt(v, chain.decimals, chain.symbol)

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
        title = { Text(if (h != null) "Sent" else if (p != null) "Review" else "Send $symbol") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    h != null -> {
                        Text("Status: $result")
                        Text("Hash: $h")
                    }
                    p != null -> {
                        if (chain.isTestnet) Text("Network: ${chain.name} (Testnet)")
                        else Text("REAL FUNDS on ${chain.name}", color = MaterialTheme.colorScheme.error)
                        Text("From: ${p.from}")
                        if (resolved != null) {
                            Text("ENS name: ${to.trim()}")
                            if (chain.chainId != 1L) {
                                Text(
                                    "Resolved on Ethereum. Make sure you control this address on ${chain.name}. " +
                                            "Contract wallets (like Safe) may not exist at the same address on other chains.",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (p.token != null && p.tokenAmount != null) {
                            Text("Token: ${p.token.symbol} (${p.token.name})")
                            Text("Token contract: ${p.token.address}")
                            Text("To: ${p.recipient}")
                            Text("Amount: ${fmt(p.tokenAmount, p.token.decimals, p.token.symbol)}")
                            Text("Max network fee: ${fmtNative(p.maxFeeWei)}")
                        } else {
                            Text("To: ${p.to}")
                            Text("Amount: ${fmtNative(p.value)}")
                            Text("Max network fee: ${fmtNative(p.maxFeeWei)}")
                            Text("Max total: ${fmtNative(p.maxTotalWei)}")
                        }
                        Text("Check the full recipient address, not only first and last characters.")
                    }
                    else -> {
                        Box {
                            OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(symbol, modifier = Modifier.weight(1f))
                                Icon(Ic.ChevronDown, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("${chain.symbol}  ${chain.name}") },
                                    onClick = { token = null; pickerOpen = false; error = null }
                                )
                                tokens.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text("${t.symbol}  ${t.name}") },
                                        onClick = { token = t; pickerOpen = false; error = null }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            to, { to = it }, label = { Text("Recipient address or ENS name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (resolving) Text("Resolving…")
                        resolved?.let { Text("→ $it") }
                        ensError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            amount, { amount = it }, label = { Text("Amount ($symbol)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                }
                if (busy) Text("Working...")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !resolving, onClick = {
                when {
                    h != null -> onClose(true)
                    p != null -> launchSafe {
                        hash = vault.withCredentials(activity) { creds -> SendTx.signAndSend(p, creds) }
                    }
                    else -> launchSafe {
                        val typed = to.trim()
                        if (Ens.looksLikeName(typed)) require(resolved != null) { ensError ?: "Name not resolved yet" }
                        val dest = resolved ?: typed
                        require(SendTx.isValidAddress(dest)) { "Invalid recipient address or checksum" }
                        require(!dest.equals(ZERO_ADDRESS, ignoreCase = true)) { "Recipient is the zero address" }
                        val units = SendTx.parseAmount(amount, decimals)
                        val tk = token
                        plan = if (tk == null) {
                            SendTx.plan(chain, from, dest, units)
                        } else {
                            require(!dest.equals(tk.address, ignoreCase = true)) {
                                "Recipient is the token contract. Tokens would be lost."
                            }
                            val bal = Erc20.balanceOf(chain.rpcUrl, tk.address, from)
                            require(bal >= units) {
                                "Insufficient ${tk.symbol}. You have ${Rpc.formatUnits(bal, tk.decimals, tk.decimals)}"
                            }
                            SendTx.plan(
                                chain, from, tk.address, BigInteger.ZERO,
                                Erc20.encodeTransfer(dest, units), dest, tk, units
                            )
                        }
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