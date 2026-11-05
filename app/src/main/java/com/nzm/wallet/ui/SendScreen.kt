package com.nzm.wallet.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.dbs.Contact
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.ZERO
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.formatUnits
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.parseAmount
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

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
    val token = vm.tokens.firstOrNull { it.address == tokenAddr }
    val symbol = token?.symbol ?: chain.symbol
    val decimals = token?.decimals ?: chain.decimals
    val balance: BigInteger? =
        if (token == null) vm.nativeBalance else vm.tokenBalances[token.address]

    var to by remember { mutableStateOf(initialTo) }
    var amount by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var ensError by remember { mutableStateOf<String?>(null) }
    val editor = rememberPlanEditor()
    var hash by remember { mutableStateOf<String?>(null) }
    val result = rememberTxResult(chain, hash)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chainMenu by remember { mutableStateOf(false) }
    var tokenMenu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var addToken by remember { mutableStateOf(false) }
    var knownAddrs by remember { mutableStateOf<Set<String>?>(null) }
    val batchItems = remember { mutableStateListOf<BatchItem>() }
    var batchOpen by remember { mutableStateOf(false) }
    val batchOk = rememberBatchSupport(chain, from)
    var planName by remember { mutableStateOf<String?>(null) }

    val p = editor.plan
    val h = hash
    val ensName: String? = if (resolved != null) to.trim() else planName

    val target: String? = resolved ?: to.trim().takeIf { isValidAddress(it) }
    val saved = target?.let { t -> vm.contacts.firstOrNull { it.address.equals(t, true) } }
    val typedIsName = Ens.looksLikeName(to)
    val canReview =
        !busy && !resolving && to.isNotBlank() && amount.isNotBlank() && (!typedIsName || resolved != null)

    fun committed(): BigInteger = batchItems.fold(BigInteger.ZERO) { acc, b ->
        acc + when {
            token == null -> b.value
            b.token?.address.equals(token.address, ignoreCase = true) -> b.spend
            else -> BigInteger.ZERO
        }
    }

    val remaining: BigInteger? = balance?.let { (it - committed()).max(BigInteger.ZERO) }

    fun isFirstTime(a: String?): Boolean =
        a != null &&
                vm.contacts.none { it.address.equals(a, true) } &&
                !vm.hasAddress(a) &&
                !vm.hasSentTo(a)

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
            p != null -> editor.clear()
            to.isNotBlank() || amount.isNotBlank() || batchItems.isNotEmpty() -> discardDialog =
                true

            else -> onClose(false)
        }
    }

    fun fill(pct: Int) {
        val b = remaining ?: return
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
                if (Ens.looksLikeName(typed)) require(ensName != null) {
                    ensError ?: "Name not resolved yet"
                }
                val dest = resolved ?: typed
                require(isValidAddress(dest)) { "Invalid recipient address or checksum" }
                require(
                    !dest.equals(
                        ZERO,
                        ignoreCase = true
                    )
                ) { "Recipient is the zero address" }
                val units = parseAmount(amount, decimals)
                val built = if (token == null) {
                    SendTx.plan(chain, from, dest, units)
                } else {
                    require(!dest.equals(token.address, ignoreCase = true)) {
                        "Recipient is the token contract. Tokens would be lost."
                    }
                    val bal = Erc20.balanceOf(chain.rpcUrl, token.address, from)
                    require(bal >= units) {
                        "Insufficient ${token.symbol}. You have ${
                            formatUnits(
                                bal,
                                token.decimals,
                                token.decimals
                            )
                        }"
                    }
                    SendTx.plan(
                        chain, from, token.address, BigInteger.ZERO,
                        Erc20.encodeTransfer(dest, units), dest, token, units
                    )
                }
                planName = null
                editor.load(built)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    suspend fun buildBatchItem(): BatchItem {
        val typed = to.trim()
        if (Ens.looksLikeName(typed)) require(resolved != null) {
            ensError ?: "Name not resolved yet"
        }
        val dest = resolved ?: typed
        require(isValidAddress(dest)) { "Invalid recipient address or checksum" }
        require(!dest.equals(ZERO, ignoreCase = true)) { "Recipient is the zero address" }
        val units = parseAmount(amount, decimals)
        require(units.signum() > 0) { "Enter an amount" }
        val ens = if (resolved != null) typed else null
        return if (token == null) {
            val have = balance ?: throw IllegalArgumentException("Balance not loaded yet")
            val left = (have - committed()).max(BigInteger.ZERO)
            require(units <= left) {
                "Only ${
                    shortAmount(
                        left,
                        decimals
                    )
                } ${chain.symbol} left after the batch"
            }
            BatchItem(
                "Send ${shortAmount(units, decimals)} ${chain.symbol}",
                dest,
                units,
                ByteArray(0),
                name = ens
            )
        } else {
            require(
                !dest.equals(
                    token.address,
                    ignoreCase = true
                )
            ) { "Recipient is the token contract. Tokens would be lost." }
            val bal = Erc20.balanceOf(chain.rpcUrl, token.address, from)
            val left = (bal - committed()).max(BigInteger.ZERO)
            require(units <= left) {
                "Only ${
                    shortAmount(
                        left,
                        token.decimals
                    )
                } ${token.symbol} left after the batch"
            }
            BatchItem(
                "Send ${shortAmount(units, decimals)} ${token.symbol}",
                token.address,
                BigInteger.ZERO,
                Numeric.hexStringToByteArray(Erc20.encodeTransfer(dest, units)),
                recipient = dest,
                name = ens,
                token = token,
                spend = units
            )
        }
    }

    fun addToBatch(thenOpen: Boolean = false) {
        busy = true
        error = null
        scope.launch {
            try {
                batchItems.add(buildBatchItem())
                to = ""
                amount = ""
                tokenAddr = initialToken?.address
                if (thenOpen) batchOpen = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    fun reviewSingle(b: BatchItem) {
        busy = true
        error = null
        scope.launch {
            try {
                val t = b.token
                val built = if (t == null) {
                    SendTx.plan(chain, from, b.to, b.value)
                } else {
                    SendTx.plan(
                        chain, from, t.address, BigInteger.ZERO,
                        Numeric.toHexString(b.data), b.recipient, t, b.spend
                    )
                }
                planName = b.name
                editor.load(built)
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
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp)
                ) {
                    // header (input page only; review and status use PageHeader)
                    if (h == null && p == null) Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { requestClose() },
                            modifier = Modifier.offset(x = (-12).dp)
                        ) {
                            Icon(Ic.ChevronLeft, "Back", Modifier.size(24.dp))
                        }
                        ChainChip(chain) { if (!busy && batchItems.isEmpty()) chainMenu = true }
                    }

                    when {
                        // ---------- status ----------
                        h != null -> {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                PageHeader("Sent") { requestClose() }
                                if (p != null) {
                                    val tk = p.token
                                    val ta = p.tokenAmount
                                    if (tk != null && ta != null) {
                                        AmountBlock(
                                            "Sent",
                                            fmt(ta, tk),
                                            vm.fiat(tk, ta)
                                        )
                                    } else {
                                        AmountBlock(
                                            "Sent",
                                            fmt(p.value, chain),
                                            vm.fiat(null, p.value)
                                        )
                                    }
                                    val dest = p.recipient ?: p.to
                                    TxCard {
                                        TxPartyRow("From", rememberParty(vm, p.from))
                                        TxLine()
                                        TxPartyRow(
                                            "To",
                                            if (ensName != null) Party(
                                                ensName,
                                                shortAddress(dest)
                                            ) else rememberParty(vm, dest)
                                        )
                                    }
                                }
                                TxStatusCard(result, h)
                            }
                            if (chain.explorerUrl != null) {
                                OutlineButton(
                                    "View on explorer",
                                    onClick = { openTx(activity, chain, h) })
                                Spacer(Modifier.height(12.dp))
                            }
                            PrimaryButton("Done", onClick = { onClose(true) })
                            Spacer(Modifier.height(16.dp))
                        }

                        // ---------- review ----------
                        p != null -> {
                            val dest = p.recipient ?: p.to
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                PageHeader("Review") { requestClose() }
                                val tk = p.token
                                val ta = p.tokenAmount
                                if (tk != null && ta != null) {
                                    AmountBlock(
                                        "Sending",
                                        fmt(ta, tk),
                                        vm.fiat(tk, ta)
                                    )
                                } else {
                                    AmountBlock(
                                        "Sending",
                                        fmt(p.value, chain),
                                        vm.fiat(null, p.value)
                                    )
                                }

                                // from / to: ENS name, then your name, then short address
                                TxCard {
                                    TxPartyRow("From", rememberParty(vm, p.from))
                                    TxLine()
                                    TxPartyRow(
                                        "To",
                                        if (ensName != null) Party(
                                            ensName,
                                            shortAddress(dest)
                                        ) else rememberParty(vm, dest)
                                    )
                                }
                                if (isFirstTime(dest)) {
                                    Text(
                                        "First time sending to this address. Check every character before you sign.",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Dim.Accent
                                    )
                                }
                                if (ensName != null && chain.chainId != 1L) {
                                    Text(
                                        "Resolved on Ethereum. Make sure you control this address on ${chain.name}. " +
                                                "Contract wallets (like Safe) may not exist at the same address on other chains.",
                                        fontSize = 14.sp, color = Dim.Danger
                                    )
                                }

                                TxNetworkCard(chain)
                                TxFeeCard(vm, chain, editor, busy)
                                SimulationCard(vm, chain, p)

                                // native sends: amount plus the most the fee can be
                                if (p.token == null) {
                                    TxCard {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(36.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Max total",
                                                fontSize = 15.sp,
                                                color = Dim.Mid,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    fmt(
                                                        p.maxTotalWei,
                                                        chain
                                                    ),
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }

                                TxAdvanced {
                                    TxPlanRows(editor, busy)
                                    TxLabel("Exact amount", top = 12)
                                    TxMono(
                                        if (tk != null && ta != null)
                                            fmt(ta, tk, tk.decimals)
                                        else fmt(p.value, chain, chain.decimals)
                                    )
                                    TxLabel("Exact max fee", top = 12)
                                    TxMono(fmt(p.maxFeeWei, chain, chain.decimals))
                                    if (tk != null) {
                                        Text(
                                            "Token",
                                            fontSize = 15.sp,
                                            color = Dim.Mid,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                        Text("${tk.symbol} (${tk.name})", fontSize = 14.sp)
                                        Text(
                                            "Token contract",
                                            fontSize = 15.sp,
                                            color = Dim.Mid,
                                            modifier = Modifier.padding(
                                                top = 12.dp,
                                                bottom = 8.dp
                                            )
                                        )
                                        TxMono(tk.address)
                                    }
                                    Text(
                                        "Recipient",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                                    )
                                    TxMono(dest)
                                    if (resolved != null) {
                                        Text(
                                            "ENS name",
                                            fontSize = 15.sp,
                                            color = Dim.Mid,
                                            modifier = Modifier.padding(
                                                top = 12.dp,
                                                bottom = 8.dp
                                            )
                                        )
                                        Text(ensName ?: "", fontSize = 14.sp)
                                    }
                                    Text(
                                        "Data",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                                    )
                                    if (p.data.isEmpty()) Text(
                                        "No data",
                                        fontSize = 14.sp
                                    ) else TxMono(p.data)

                                }
                            }
                            error?.let {
                                Text(
                                    it,
                                    color = Dim.Danger,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            if (busy) Text(
                                "Working…",
                                color = Dim.Mid,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlineButton(
                                    "Back",
                                    onClick = { if (!busy) editor.clear() },
                                    modifier = Modifier.weight(1f)
                                )
                                PrimaryButton(
                                    "Sign & send",
                                    onClick = { sign(p) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        // ---------- input ----------
                        else -> {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Max: " + (remaining?.let { vm.amountText(it, decimals) }
                                            ?: "…"),
                                        fontSize = 14.sp, color = Dim.Accent,
                                        modifier = Modifier.clickable(enabled = remaining != null) {
                                            fill(
                                                100
                                            )
                                        }
                                    )
                                }
                                Spacer(Modifier.height(14.dp))

                                // token + amount
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box {
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable { tokenMenu = true }
                                                .padding(vertical = 4.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            AssetIcon(
                                                chain,
                                                token,
                                                44.dp,
                                                symbol.take(1).uppercase()
                                            )
                                            Column {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        symbol,
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1
                                                    )
                                                    Icon(Ic.ChevronDown, null, Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                    BasicTextField(
                                        value = amount,
                                        onValueChange = { v ->
                                            val c = v.replace(',', '.')
                                            if (c.count { it == '.' } <= 1 && c.all { it.isDigit() || it == '.' }) amount =
                                                c
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                                            color = Dim.Ink, textAlign = TextAlign.End
                                        ),
                                        cursorBrush = SolidColor(Dim.Accent),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        decorationBox = { inner ->
                                            Box(
                                                Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                if (amount.isEmpty()) {
                                                    Text(
                                                        "0",
                                                        fontSize = 36.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Dim.Mid
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp)
                                    )
                                }

                                // dollar value + 25 / 50
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    val units = try {
                                        parseAmount(amount, decimals)
                                    } catch (_: Exception) {
                                        null
                                    }
                                    vm.fiat(token, units)
                                        ?.let { Text(it, fontSize = 14.sp, color = Dim.Mid) }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PctChip("25%", remaining != null) { fill(25) }
                                        PctChip("50%", remaining != null) { fill(50) }
                                    }
                                }

                                Spacer(Modifier.height(28.dp))
                                Hairline()
                                Spacer(Modifier.height(16.dp))
                                Text("To", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Dim.Raised)
                                        .padding(start = 12.dp, end = 4.dp),
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
                                                if (to.isEmpty()) Text(
                                                    "Address or ENS name",
                                                    fontSize = 16.sp,
                                                    color = Dim.Mid
                                                )
                                                inner()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (to.isEmpty()) {
                                        IconButton(onClick = {
                                            val cm =
                                                activity.getSystemService(ClipboardManager::class.java)
                                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }
                                                ?.getItemAt(0)?.text?.toString()
                                            if (!t.isNullOrBlank()) to = t.trim()
                                        }) {
                                            Icon(
                                                Ic.Paste,
                                                "Paste",
                                                Modifier.size(22.dp),
                                                tint = Dim.Mid
                                            )
                                        }
                                    } else {
                                        if (target != null && saved == null) {
                                            IconButton(onClick = { saveDialog = true }) {
                                                Icon(
                                                    Ic.UserPlus,
                                                    "Save contact",
                                                    Modifier.size(22.dp),
                                                    tint = Dim.Accent
                                                )
                                            }
                                        }
                                        IconButton(onClick = { to = "" }) {
                                            Icon(
                                                Ic.Close,
                                                "Clear",
                                                Modifier.size(20.dp),
                                                tint = Dim.Mid
                                            )
                                        }
                                    }
                                }
                                if (resolving) Text(
                                    "Resolving…",
                                    fontSize = 13.sp,
                                    color = Dim.Mid,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                resolved?.let {
                                    Text(
                                        "→ $it",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                saved?.let {
                                    Text(
                                        "Saved as ${it.name}",
                                        fontSize = 13.sp,
                                        color = Dim.Accent,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                if (saved == null && isFirstTime(target)) {
                                    Text(
                                        "First time sending to this address",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Dim.Danger,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                ensError?.let {
                                    Text(
                                        it,
                                        fontSize = 13.sp,
                                        color = Dim.Danger,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }

                                if (batchItems.isNotEmpty()) {
                                    Spacer(Modifier.height(24.dp))
                                    TxCard {
                                        Text(
                                            "Batch (${batchItems.size})",
                                            fontSize = 15.sp,
                                            color = Dim.Mid
                                        )
                                        batchItems.forEachIndexed { i, b ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${i + 1}. ${batchLineText(vm, b)}",
                                                    fontSize = 14.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    Ic.Trash,
                                                    "Remove",
                                                    Modifier
                                                        .size(20.dp)
                                                        .clickable { batchItems.removeAt(i) },
                                                    tint = Dim.Mid
                                                )
                                            }
                                        }
                                        Text(
                                            "The network is locked while the batch has calls.",
                                            fontSize = 12.sp,
                                            color = Dim.Mid,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }

                                // contacts, filtered by what is typed
                                val q = to.trim().lowercase()
                                val shown = vm.contacts.filter {
                                    q.isEmpty() || it.name.lowercase()
                                        .contains(q) || it.address.lowercase().startsWith(q)
                                }
                                if (shown.isNotEmpty()) {
                                    Spacer(Modifier.height(24.dp))
                                    SectionLabel("Contacts")
                                    Spacer(Modifier.height(4.dp))
                                    shown.forEach { c ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(60.dp)
                                                .clickable { to = c.address },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            SendAvatar(c.name.take(1).uppercase(), 40.dp)
                                            Column {
                                                Text(
                                                    c.name,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    shortAddress(c.address) + (c.ens?.let { " · $it" }
                                                        ?: ""),
                                                    fontSize = 13.sp, color = Dim.Mid, maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                            error?.let {
                                Text(
                                    it,
                                    color = Dim.Danger,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            if (batchOk || batchItems.isNotEmpty()) {
                                OutlineButton(
                                    "Add to batch",
                                    onClick = { addToBatch() },
                                    icon = Ic.Plus
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            val inBatch = batchItems.isNotEmpty()
                            val single = batchItems.size == 1 && !canReview
                            val count = batchItems.size + if (canReview) 1 else 0
                            PrimaryButton(
                                when {
                                    busy -> "Working…"
                                    !inBatch || single -> "Review"
                                    else -> "Review batch ($count calls)"
                                },
                                onClick = {
                                    when {
                                        !inBatch -> review()
                                        single -> reviewSingle(batchItems[0])
                                        canReview -> addToBatch(thenOpen = true)
                                        else -> batchOpen = true
                                    }
                                },
                                enabled = if (inBatch) !busy else canReview
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }

                SendChainMenu(
                    vm = vm,
                    open = chainMenu,
                    onDismiss = { chainMenu = false },
                    onSelect = {
                        vm.selectChain(it)
                        chainMenu = false
                    }
                )
                SendTokenMenu(
                    vm, chain, tokenAddr, tokenMenu, { tokenMenu = false },
                    onPick = { tokenAddr = it; tokenMenu = false },
                    onAdd = {
                        tokenMenu = false
                        knownAddrs = vm.tokens.map { it.address }.toSet()
                        addToken = true
                    }
                )
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

    if (batchOpen) {
        BatchDialog(activity, vault, vm, chain, from, sharedItems = batchItems) { sent ->
            batchOpen = false
            if (sent) onClose(true)
        }
    }

    PlanEditorDialogs(editor)

    if (addToken) TokenDialog(chain, vm.tokenRepo) { addToken = false }

    if (saveDialog && target != null) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(target, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (typedIsName) Text(
                        "Saves the address this name points to now, not the name itself.",
                        fontSize = 13.sp,
                        color = Dim.Mid
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(32) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
private fun AmountBlock(label: String, amount: String, fiat: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(label, fontSize = 15.sp, color = Dim.Mid)
        Text(amount, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        if (fiat != null) Text(fiat, fontSize = 15.sp, color = Dim.Mid)
    }
}

@Composable
private fun SendAvatar(text: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Dim.Avatar),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Dim.Ink
        )
    }
}

@Composable
private fun PctChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Dim.Raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Dim.Ink else Dim.Mid
        )
    }
}
