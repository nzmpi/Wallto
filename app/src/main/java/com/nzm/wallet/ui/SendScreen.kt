package com.nzm.wallet.ui

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.*
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

private const val SEND_ZERO = "0x0000000000000000000000000000000000000000"

@Composable
fun SendScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    from: String,
    initialToken: Token?,
    initialTo: String = "",
    onClose: (sent: Boolean) -> Unit
) {
    val chain = vm.selected ?: return
    val scope = rememberCoroutineScope()

    var tokenAddr by remember { mutableStateOf(initialToken?.address) }
    // falls back to the native coin by itself when the chain changes and the token is not found
    val token = vm.tokens.firstOrNull { it.address == tokenAddr }
    val symbol = token?.symbol ?: chain.symbol
    val decimals = token?.decimals ?: chain.decimals
    val balance: BigInteger? = if (token == null) vm.nativeBalance else vm.tokenBalances[token.address]

    var to by remember { mutableStateOf(initialTo) }
    var amount by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var ensError by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("Pending...") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chainMenu by remember { mutableStateOf(false) }
    var tokenMenu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var addToken by remember { mutableStateOf(false) }
    var knownAddrs by remember { mutableStateOf<Set<String>?>(null) }

    val p = plan
    val h = hash

    // the address that will receive funds, if the input is valid so far
    val target: String? = resolved ?: to.trim().takeIf { SendTx.isValidAddress(it) }
    val saved = target?.let { t -> vm.contacts.firstOrNull { it.address.equals(t, true) } }
    val typedIsName = Ens.looksLikeName(to)
    val canReview = !busy && !resolving && to.isNotBlank() && amount.isNotBlank() && (!typedIsName || resolved != null)

    // chain or token changed: the old amount no longer applies
    LaunchedEffect(chain.chainId, tokenAddr) { amount = ""; error = null }

    // ENS name typed in the To field
    LaunchedEffect(to) {
        resolved = null
        ensError = null
        resolving = false
        val name = to.trim()
        if (!Ens.looksLikeName(name)) return@LaunchedEffect
        val rpc = vm.ensRpc
        if (rpc == null) {
            ensError = "Add Ethereum Mainnet (chain ID 1) to resolve ENS names"
            return@LaunchedEffect
        }
        delay(600.milliseconds)
        resolving = true
        try {
            resolved = Ens.resolve(rpc, name) ?: run { ensError = "This name has no address"; null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensError = e.message ?: "Could not resolve"
        } finally {
            resolving = false
        }
    }

    LaunchedEffect(hash) {
        val tx = hash ?: return@LaunchedEffect
        result = try {
            when (SendTx.waitForReceipt(chain.rpcUrl, tx)) {
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

    // after adding a token in the dialog, select it automatically
    LaunchedEffect(vm.tokens) {
        val known = knownAddrs ?: return@LaunchedEffect
        vm.tokens.firstOrNull { it.address !in known }?.let {
            tokenAddr = it.address
            knownAddrs = null
        }
    }

    fun requestClose() {
        when {
            busy -> {}
            h != null -> onClose(true)
            p != null -> plan = null
            to.isNotBlank() || amount.isNotBlank() -> discardDialog = true
            else -> onClose(false)
        }
    }

    fun fill(pct: Int) {
        val b = balance ?: return
        scope.launch {
            var v = b * BigInteger.valueOf(pct.toLong()) / BigInteger.valueOf(100)
            if (token == null && pct == 100) {
                try {
                    // leave room for the fee, with headroom
                    val reserve = SendTx.feeReserve(chain, from)
                    v = (b - reserve).max(BigInteger.ZERO)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Could not estimate the fee: ${e.message}"
                    return@launch
                }
            }
            error = null
            amount = BigDecimal(v).movePointLeft(decimals).stripTrailingZeros().toPlainString()
        }
    }

    fun review() {
        busy = true
        error = null
        scope.launch {
            try {
                val typed = to.trim()
                if (Ens.looksLikeName(typed)) require(resolved != null) { ensError ?: "Name not resolved yet" }
                val dest = resolved ?: typed
                require(SendTx.isValidAddress(dest)) { "Invalid recipient address or checksum" }
                require(!dest.equals(SEND_ZERO, ignoreCase = true)) { "Recipient is the zero address" }
                val units = SendTx.parseAmount(amount, decimals)
                plan = if (token == null) {
                    SendTx.plan(chain, from, dest, units)
                } else {
                    require(!dest.equals(token.address, ignoreCase = true)) {
                        "Recipient is the token contract. Tokens would be lost."
                    }
                    val bal = Erc20.balanceOf(chain.rpcUrl, token.address, from)
                    require(bal >= units) {
                        "Insufficient ${token.symbol}. You have ${Rpc.formatUnits(bal, token.decimals, token.decimals)}"
                    }
                    SendTx.plan(
                        chain, from, token.address, BigInteger.ZERO,
                        Erc20.encodeTransfer(dest, units), dest, token, units
                    )
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

    fun sign(pl: TxPlan) {
        busy = true
        error = null
        scope.launch {
            try {
                hash = vault.withCredentials(activity) { creds -> SendTx.signAndSend(pl, creds) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    fun fmt(v: BigInteger, dec: Int, sym: String) = "${Rpc.formatUnits(v, dec, dec)} $sym"
    fun fmtNative(v: BigInteger) = fmt(v, chain.decimals, chain.symbol)

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Dim.Bg, contentColor = Dim.Ink) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)
            ) {
                // header
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { requestClose() }, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Ic.ChevronLeft, "Back", Modifier.size(24.dp))
                    }
                    if (h == null && p == null) {
                        Box {
                            ChainChip(chain.name) { if (!busy) chainMenu = true }
                            DropdownMenu(expanded = chainMenu, onDismissRequest = { chainMenu = false }) {
                                vm.chains.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(c.name + if (c.isTestnet) "  (testnet)" else "") },
                                        onClick = { vm.selectChain(c.chainId); chainMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    // ---------- status ----------
                    h != null -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(16.dp))
                            ScreenTitle("Sent")
                            Field("Status", result)
                            Field("Transaction hash", h, mono = true)
                        }
                        if (chain.explorerUrl != null) {
                            OutlineButton("View on explorer", onClick = {
                                activity.startActivity(
                                    Intent(Intent.ACTION_VIEW, "${chain.explorerUrl}/tx/$h".toUri())
                                )
                            })
                            Spacer(Modifier.height(12.dp))
                        }
                        PrimaryButton("Done", onClick = { onClose(true) })
                        Spacer(Modifier.height(16.dp))
                    }

                    // ---------- review ----------
                    p != null -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(16.dp))
                            ScreenTitle("Review")
                            Spacer(Modifier.height(8.dp))
                            if (chain.isTestnet) Field("Network", "${chain.name} (testnet)")
                            else Field("Network", "REAL FUNDS on ${chain.name}", color = Dim.Danger)
                            Field("From", p.from, mono = true)
                            val dest = p.recipient ?: p.to
                            val contact = vm.contacts.firstOrNull { it.address.equals(dest, true) }
                            Field("To" + (contact?.let { " · ${it.name}" } ?: ""), dest, mono = true)
                            if (resolved != null) {
                                Field("ENS name", to.trim())
                                if (chain.chainId != 1L) {
                                    Text(
                                        "Resolved on Ethereum. Make sure you control this address on ${chain.name}. " +
                                                "Contract wallets (like Safe) may not exist at the same address on other chains.",
                                        fontSize = 14.sp, color = Dim.Danger
                                    )
                                }
                            }
                            if (p.token != null && p.tokenAmount != null) {
                                Field("Token", "${p.token.symbol} (${p.token.name})")
                                Field("Token contract", p.token.address, mono = true)
                                Field("Amount", fmt(p.tokenAmount, p.token.decimals, p.token.symbol))
                                Field("Max network fee", fmtNative(p.maxFeeWei))
                            } else {
                                Field("Amount", fmtNative(p.value))
                                Field("Max network fee", fmtNative(p.maxFeeWei))
                                Field("Max total", fmtNative(p.maxTotalWei))
                            }
                            Text(
                                "Check the full recipient address, not only the first and last characters.",
                                fontSize = 13.sp, color = Dim.Mid
                            )
                        }
                        error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                        if (busy) Text("Working…", color = Dim.Mid, modifier = Modifier.padding(bottom = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlineButton("Back", onClick = { if (!busy) plan = null }, modifier = Modifier.weight(1f))
                            PrimaryButton("Sign & send", onClick = { sign(p) }, enabled = !busy, modifier = Modifier.weight(2f))
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // ---------- input ----------
                    else -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Max: " + (balance?.let { vm.amountText(it, decimals) } ?: "…"),
                                    fontSize = 14.sp, color = Dim.Accent,
                                    modifier = Modifier.clickable(enabled = balance != null) { fill(100) }
                                )
                            }
                            Spacer(Modifier.height(14.dp))

                            // token + amount
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Row(
                                        Modifier.clip(RoundedCornerShape(16.dp)).clickable { tokenMenu = true }
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        SendAvatar(symbol.take(1).uppercase(), 44.dp)
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                Icon(Ic.ChevronDown, null, Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                    DropdownMenu(expanded = tokenMenu, onDismissRequest = { tokenMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("${chain.symbol}   ${vm.amountText(vm.nativeBalance, chain.decimals)}") },
                                            onClick = { tokenAddr = null; tokenMenu = false }
                                        )
                                        vm.tokens
                                            .filter { it.address == tokenAddr || vm.tokenBalances[it.address]?.signum() != 0 }
                                            .forEach { t ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text("${t.symbol}   ${vm.amountText(vm.tokenBalances[t.address], t.decimals)}")
                                                    },
                                                    onClick = { tokenAddr = t.address; tokenMenu = false }
                                                )
                                            }
                                        HorizontalDivider(color = Dim.Line)
                                        DropdownMenuItem(
                                            text = { Text("Add token…", color = Dim.Accent) },
                                            onClick = {
                                                tokenMenu = false
                                                knownAddrs = vm.tokens.map { it.address }.toSet()
                                                addToken = true
                                            }
                                        )
                                    }
                                }
                                BasicTextField(
                                    value = amount,
                                    onValueChange = { v ->
                                        val c = v.replace(',', '.')
                                        if (c.count { it == '.' } <= 1 && c.all { it.isDigit() || it == '.' }) amount = c
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                                        color = Dim.Ink, textAlign = TextAlign.End
                                    ),
                                    cursorBrush = SolidColor(Dim.Accent),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                            if (amount.isEmpty()) {
                                                Text("0", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = Dim.Mid)
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                                )
                            }

                            // dollar value + 25 / 50
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End) {
                                val units = try { SendTx.parseAmount(amount, decimals) } catch (_: Exception) { null }
                                vm.fiat(token, units)?.let { Text(it, fontSize = 14.sp, color = Dim.Mid) }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PctChip("25%", balance != null) { fill(25) }
                                    PctChip("50%", balance != null) { fill(50) }
                                }
                            }

                            Spacer(Modifier.height(28.dp))
                            Hairline()
                            Spacer(Modifier.height(16.dp))
                            Text("To", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                                    .background(Dim.Raised).padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SendAvatar(saved?.name?.take(1)?.uppercase() ?: "", 28.dp)
                                BasicTextField(
                                    value = to,
                                    onValueChange = { to = it.trimStart() },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 16.sp, color = Dim.Ink),
                                    cursorBrush = SolidColor(Dim.Accent),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (to.isEmpty()) Text("Address or ENS name", fontSize = 16.sp, color = Dim.Mid)
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (to.isEmpty()) {
                                    IconButton(onClick = {
                                        val cm = activity.getSystemService(ClipboardManager::class.java)
                                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                        if (!t.isNullOrBlank()) to = t.trim()
                                    }) { Icon(Ic.Paste, "Paste", Modifier.size(22.dp), tint = Dim.Mid) }
                                } else {
                                    if (target != null && saved == null) {
                                        IconButton(onClick = { saveDialog = true }) {
                                            Icon(Ic.UserPlus, "Save contact", Modifier.size(22.dp), tint = Dim.Accent)
                                        }
                                    }
                                    IconButton(onClick = { to = "" }) {
                                        Icon(Ic.Close, "Clear", Modifier.size(20.dp), tint = Dim.Mid)
                                    }
                                }
                            }
                            if (resolving) Text("Resolving…", fontSize = 13.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp))
                            resolved?.let {
                                Text("→ $it", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp))
                            }
                            saved?.let {
                                Text("Saved as ${it.name}", fontSize = 13.sp, color = Dim.Accent, modifier = Modifier.padding(top = 8.dp))
                            }
                            ensError?.let { Text(it, fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 8.dp)) }

                            // contacts, filtered by what is typed
                            val q = to.trim().lowercase()
                            val shown = vm.contacts.filter {
                                q.isEmpty() || it.name.lowercase().contains(q) || it.address.lowercase().startsWith(q)
                            }
                            if (shown.isNotEmpty()) {
                                Spacer(Modifier.height(24.dp))
                                SectionLabel("Contacts")
                                Spacer(Modifier.height(4.dp))
                                shown.forEach { c ->
                                    Row(
                                        Modifier.fillMaxWidth().height(60.dp).clickable { to = c.address },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        SendAvatar(c.name.take(1).uppercase(), 40.dp)
                                        Column {
                                            Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(
                                                shortAddress(c.address) + (c.ens?.let { " · $it" } ?: ""),
                                                fontSize = 13.sp, color = Dim.Mid, maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                        PrimaryButton(if (busy) "Working…" else "Review", onClick = { review() }, enabled = canReview)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (discardDialog) {
        ConfirmDialog(
            title = "Discard this transfer?",
            text = "The recipient and amount you entered will be cleared.",
            confirmLabel = "Discard",
            onConfirm = { discardDialog = false; onClose(false) },
            onDismiss = { discardDialog = false }
        )
    }

    if (addToken) TokenDialog(chain, vm.tokenRepo) { addToken = false }

    if (saveDialog && target != null) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(target, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (typedIsName) Text("Saves the address this name points to now, not the name itself.", fontSize = 13.sp, color = Dim.Mid)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(32) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.saveContact(
                        Contact(
                            address = Keys.toChecksumAddress(target),
                            name = name.trim(),
                            ens = to.trim().takeIf { Ens.looksLikeName(it) }
                        )
                    )
                    saveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SendAvatar(text: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Dim.Avatar),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, color = Dim.Ink)
    }
}

@Composable
private fun PctChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Dim.Raised)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Dim.Ink else Dim.Mid)
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false, color: Color = Dim.Ink) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, color = Dim.Mid)
        Text(
            value,
            fontSize = if (mono) 14.sp else 16.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = color
        )
    }
}
