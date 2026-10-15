package com.nzm.wallet.ui

import android.content.SharedPreferences
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
import com.nzm.wallet.chain.Chain
import com.nzm.wallet.chain.ChainRepository
import com.nzm.wallet.chain.Rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Token
import com.nzm.wallet.chain.TokenRepository
import com.nzm.wallet.storage.Vault

@Composable
fun ChainSection(
    address: String,
    repo: ChainRepository,
    tokenRepo: TokenRepository,
    prefs: SharedPreferences,
    activity: FragmentActivity,
    vault: Vault
) {
    val scope = rememberCoroutineScope()
    val chains by repo.chains.collectAsState(initial = emptyList())
    var selectedId by remember { mutableLongStateOf(prefs.getLong("chain_id", 11155111L)) }
    val selected = chains.firstOrNull { it.chainId == selectedId } ?: chains.firstOrNull()
    var balance by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    val tokens by remember(selected?.chainId) { tokenRepo.observe(selected?.chainId ?: -1L) }
        .collectAsState(initial = emptyList())
    var tokenBalances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sendToken by remember { mutableStateOf<Token?>(null) }
    var showAddToken by remember { mutableStateOf(false) }
    var tokenToRemove by remember { mutableStateOf<Token?>(null) }

    LaunchedEffect(Unit) { repo.seedDefaults() }

    LaunchedEffect(address, selected?.chainId, selected?.rpcUrl, tick) {
        val c = selected ?: return@LaunchedEffect
        balance = "loading..."
        balance = try {
            "${Rpc.formatUnits(Rpc.balance(c.rpcUrl, address), c.decimals)} ${c.symbol}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    LaunchedEffect(address, selected?.chainId, selected?.rpcUrl, tokens, tick) {
        val c = selected ?: return@LaunchedEffect
        tokenBalances = emptyMap()
        for ((_, tokenAddress, _, _, decimals) in tokens) {
            val text = try {
                Rpc.formatUnits(Erc20.balanceOf(c.rpcUrl, tokenAddress, address), decimals)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "error"
            }
            tokenBalances = tokenBalances + (tokenAddress to text)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text("${selected.name} (${selected.chainId})")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    chains.forEach { c ->
                        DropdownMenuItem(
                            text = { Text("${c.name} (${c.chainId})") },
                            onClick = {
                                selectedId = c.chainId
                                prefs.edit { putLong("chain_id", c.chainId) }
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            Text("Balance: $balance")
            Button(onClick = { sendToken = null; showSend = true }) { Text("Send") }

            tokens.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${t.symbol}: ${tokenBalances[t.address] ?: "..."}", Modifier.weight(1f))
                    TextButton(onClick = { sendToken = t; showSend = true }) { Text("Send") }
                    TextButton(onClick = { tokenToRemove = t }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { showAddToken = true }) { Text("Add token") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
                OutlinedButton(onClick = { showEdit = true }) { Text("Edit chain") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAdd = true }) { Text("Add chain") }
                if (chains.size > 1) {
                    OutlinedButton(onClick = { scope.launch { repo.remove(selected.chainId) } }) { Text("Remove chain") }
                }
            }
        }
    }

    if (showAdd) {
        ChainDialog(repo, null) { added ->
            showAdd = false
            if (added != null) {
                selectedId = added.chainId
                prefs.edit { putLong("chain_id", added.chainId) }
            }
        }
    }
    if (showEdit && selected != null) {
        ChainDialog(repo, selected) { showEdit = false }
    }

    if (showSend && selected != null) {
        SendDialog(activity, vault, selected, address, sendToken) { sent ->
            showSend = false
            if (sent) tick++
        }
    }
    if (showAddToken && selected != null) {
        TokenDialog(selected, tokenRepo) { showAddToken = false }
    }

    tokenToRemove?.let { t ->
        AlertDialog(
            onDismissRequest = { tokenToRemove = null },
            title = { Text("Remove ${t.symbol}?") },
            text = {
                Text(
                    "Removes it from this list only. Your tokens stay in your wallet. " +
                            "Add it again with the contract address:\n${t.address}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { tokenRepo.remove(t.chainId, t.address) }
                    tokenToRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { tokenToRemove = null }) { Text("Cancel") } }
        )
    }
}

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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(rpc, { rpc = it }, label = { Text("RPC URL (https)") }, singleLine = true)
                OutlinedTextField(
                    id, { id = it }, label = { Text("Chain ID") }, singleLine = true,
                    enabled = existing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(symbol, { symbol = it }, label = { Text("Currency symbol") }, singleLine = true)
                OutlinedTextField(explorer, { explorer = it }, label = { Text("Explorer URL (optional)") }, singleLine = true)
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
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(null) }) { Text("Cancel") } }
    )
}