package com.nzm.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.AbiCodec
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.chain.Rpc
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.MAX_UINT
import com.nzm.wallet.utils.Constants.SHAPE
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.parseAmount
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger

private val NO_DATA = ByteArray(0)

@Composable
fun BatchDialog(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    chain: Chain,
    from: String,
    sharedItems: SnapshotStateList<BatchItem>? = null,
    onClose: (sent: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val items = sharedItems ?: remember { mutableStateListOf() }
    var adding by remember { mutableStateOf(false) }
    var allOrNothing by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<BatchStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    val editor = rememberPlanEditor()
    var hash by remember { mutableStateOf<String?>(null) }
    val result = rememberTxResult(chain, hash)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chain.chainId, from) {
        status = null
        statusError = null
        try {
            status = Batch.status(chain, from)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusError = e.message ?: "Could not check the account"
        }
    }

    val p = editor.plan
    val h = hash
    val canBatch = status?.supportsBatch == true

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

    fun buildPlan() = launchSafe {
        val data = Batch.encode(items.toList(), allOrNothing)
        val built = SendTx.plan(chain, from, from, BigInteger.ZERO, data)
        editor.load(built)
    }

    fun goBack() {
        when {
            busy -> {}
            h != null -> onClose(true)
            p != null -> editor.clear()
            else -> onClose(false)
        }
    }

    TxPage(
        onDismiss = { if (!busy && h == null) goBack() },
        bottom = {
            when {
                h != null -> TxSentButtons(activity, chain, h) { onClose(true) }
                p != null -> {
                    OutlineButton("Back", { goBack() }, Modifier.weight(1f))
                    PrimaryButton(
                        if (busy) "Working…" else "Sign & send",
                        onClick = {
                            launchSafe {
                                hash = vault.withCredentials(activity) { creds ->
                                    SendTx.signAndSend(
                                        p,
                                        creds
                                    )
                                }
                            }
                        },
                        enabled = !busy, modifier = Modifier.weight(1f)
                    )
                }

                else -> {
                    OutlineButton("Close", { goBack() }, Modifier.weight(1f))
                    PrimaryButton(
                        if (busy) "Working…" else "Review",
                        onClick = { buildPlan() },
                        enabled = !busy && canBatch && items.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        when {
            // ---------- status ----------
            h != null -> {
                PageHeader("Sent") { onClose(true) }
                TxAmount("Batch sent", "${items.size} call${if (items.size == 1) "" else "s"}")
                TxStatusCard(result, h)
            }

            // ---------- review ----------
            p != null -> {
                PageHeader("Review batch") { goBack() }
                TxAmount(
                    "Sending",
                    "${items.size} call${if (items.size == 1) "" else "s"} in one transaction"
                )
                TxCard {
                    TxPartyRow("Account", rememberParty(vm, p.from))
                    TxLine()
                    TxRow("Mode", if (allOrNothing) "All or nothing" else "A call may fail")
                }
                items.forEachIndexed { i, it ->
                    TxCard {
                        Text("Call ${i + 1}", fontSize = 13.sp, color = Dim.Mid)
                        Text(it.line, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        val target = it.recipient ?: it.to
                        TxPartyRow(
                            it.link.replaceFirstChar { c -> c.uppercase() },
                            if (it.name != null) Party(
                                it.name,
                                shortAddress(target)
                            ) else rememberParty(vm, target)
                        )
                        if (it.recipient != null) TxRow("Token contract", shortAddress(it.to))
                        if (it.value.signum() > 0) TxRow(
                            "Value",
                            fmt(it.value, chain)
                        )
                        it.warnings.forEach { w ->
                            Text(
                                "WARNING: $w",
                                fontSize = 13.sp,
                                color = Dim.Danger,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                TxNetworkCard(chain)
                TxFeeCard(vm, chain, editor, busy)
                SimulationCard(vm, chain, p)
                TxAdvanced {
                    TxPlanRows(editor, busy)
                    status?.delegate?.let {
                        TxLabel("Delegated to", top = 12)
                        TxMono(it)
                    }
                    TxLabel("Calldata", top = 12)
                    TxMono(p.data)
                }
                Text(
                    "Every call runs with your account's authority. Only sign batches you understand.",
                    fontSize = 13.sp, color = Dim.Mid
                )
            }

            // ---------- build ----------
            else -> {
                PageHeader("Batch") { goBack() }
                TxCard {
                    TxPartyRow("Account", rememberParty(vm, from))
                    TxLine()
                    val s = status
                    when {
                        statusError != null -> Text(
                            statusError ?: "",
                            fontSize = 14.sp,
                            color = Dim.Danger,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        s == null -> Text(
                            "Checking account…",
                            fontSize = 14.sp,
                            color = Dim.Mid,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        s.delegate == null -> Text(
                            "This account is not delegated on ${chain.name}, so it cannot batch calls here.",
                            fontSize = 14.sp,
                            color = Dim.Danger,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        !s.supportsBatch -> Text(
                            "Delegated to ${shortAddress(s.delegate)}, which does not support ERC-7821 batches.",
                            fontSize = 14.sp,
                            color = Dim.Danger,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        else -> {
                            TxRow("Delegated to", shortAddress(s.delegate))
                            Text("Batches supported (ERC-7821).", fontSize = 13.sp, color = TxOk)
                        }
                    }
                }

                items.forEachIndexed { i, it ->
                    TxCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Call ${i + 1}", fontSize = 13.sp, color = Dim.Mid)
                                Text(
                                    batchLineText(vm, it),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                it.warnings.forEach { w ->
                                    Text(
                                        "WARNING: $w",
                                        fontSize = 12.sp,
                                        color = Dim.Danger
                                    )
                                }
                            }
                            Icon(
                                Ic.Trash,
                                "Remove call",
                                Modifier
                                    .size(22.dp)
                                    .clickable { items.removeAt(i) },
                                tint = Dim.Mid
                            )
                        }
                    }
                }
                if (items.isEmpty()) Text(
                    "No calls yet. Add the calls you want to run together.",
                    fontSize = 14.sp,
                    color = Dim.Mid
                )
                OutlineButton("Add call", { adding = true }, icon = Ic.Plus)

                TxCard(Modifier.clickable { allOrNothing = !allOrNothing }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("If a call fails", fontSize = 15.sp, color = Dim.Mid)
                            Text(
                                if (allOrNothing) "Cancel the whole batch" else "Keep going with the others",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text("Change", fontSize = 13.sp, color = Dim.Accent)
                    }
                }
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
    }

    if (adding) {
        AddCallDialog(
            vm,
            chain,
            onAdd = { items.add(it); adding = false },
            onDismiss = { adding = false })
    }
    PlanEditorDialogs(editor)
}

private enum class Kind(val label: String) {
    Send("Send"), Token("Token"), Approve("Approve"), Call(
        "Contract"
    )
}

@Composable
private fun AddCallDialog(
    vm: WalletViewModel,
    chain: Chain,
    onAdd: (BatchItem) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(Kind.Send) }
    var tokenAddr by remember { mutableStateOf(vm.tokens.firstOrNull()?.address) }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unlimited by remember { mutableStateOf(false) }
    var sig by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val token = vm.tokens.firstOrNull { it.address == tokenAddr }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    fun addr(text: String, what: String): String {
        val t = text.trim()
        require(isValidAddress(t)) { "Invalid $what address or checksum" }
        return Keys.toChecksumAddress(t)
    }

    fun build(): suspend () -> BatchItem = {
        when (kind) {
            Kind.Send -> {
                val dest = addr(to, "recipient")
                val units = parseAmount(amount, chain.decimals)
                require(units.signum() > 0) { "Enter an amount" }
                BatchItem(
                    "Send ${shortAmount(units, chain.decimals)} ${chain.symbol}",
                    dest,
                    units,
                    NO_DATA
                )
            }

            Kind.Token -> {
                val t = token ?: throw IllegalArgumentException("Add a token on this chain first")
                val dest = addr(to, "recipient")
                val units = parseAmount(amount, t.decimals)
                require(units.signum() > 0) { "Enter an amount" }
                require(
                    !dest.equals(
                        t.address,
                        true
                    )
                ) { "Recipient is the token contract. Tokens would be lost." }
                BatchItem(
                    "Send ${shortAmount(units, t.decimals)} ${t.symbol}",
                    t.address,
                    BigInteger.ZERO,
                    Numeric.hexStringToByteArray(Erc20.encodeTransfer(dest, units)),
                    recipient = dest,
                    token = t,
                    spend = units
                )
            }

            Kind.Approve -> {
                val t = token ?: throw IllegalArgumentException("Add a token on this chain first")
                val spender = addr(to, "spender")
                val units = if (unlimited) MAX_UINT else parseAmount(amount, t.decimals)
                BatchItem(
                    "Approve ${
                        if (unlimited) "UNLIMITED" else shortAmount(
                            units,
                            t.decimals
                        )
                    } ${t.symbol}",
                    t.address, BigInteger.ZERO, Batch.encodeApprove(spender, units),
                    if (unlimited) listOf("Unlimited approval lets ${shortAddress(spender)} spend all your ${t.symbol}.") else emptyList(),
                    recipient = spender, link = "for"
                )
            }

            Kind.Call -> {
                val dest = addr(to, "contract")
                val s = AbiCodec.parseSignature(sig)
                val lines =
                    if (s.inputs.isEmpty()) emptyList() else args.replace("\r", "").trimEnd('\n')
                        .split("\n")
                require(lines.size == s.inputs.size) { "Expected ${s.inputs.size} argument(s), one per line. Got ${lines.size}." }
                val values = s.inputs.mapIndexed { i, t ->
                    try {
                        AbiCodec.parseValue(t, lines[i])
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Argument ${i + 1} (${t.canonical}): ${e.message}")
                    }
                }
                require(
                    Rpc.isContract(
                        chain.rpcUrl,
                        dest
                    )
                ) { "No contract at this address on this chain" }
                val v = if (value.isBlank()) BigInteger.ZERO else parseAmount(value, chain.decimals)
                BatchItem(
                    "Call " + s.signature.substringBefore('('),
                    dest,
                    v,
                    AbiCodec.encodeCall(s, values),
                    AbiCodec.riskNotes(s, values).toList(),
                    link = "on"
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kind.entries.forEach { k ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (kind == k) Dim.RaisedSelected else Dim.Raised)
                                .clickable { kind = k; error = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                k.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, softWrap = false,
                                color = if (kind == k) Dim.Ink else Dim.Mid
                            )
                        }
                    }
                }
                if (kind == Kind.Token || kind == Kind.Approve) {
                    if (vm.tokens.isEmpty()) Text(
                        "No tokens on ${chain.name}. Add one in Settings.",
                        fontSize = 13.sp,
                        color = Dim.Danger
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.tokens.take(5).forEach { t ->
                            Box(
                                Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (tokenAddr == t.address) Dim.RaisedSelected else Dim.Raised)
                                    .clickable { tokenAddr = t.address }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(t.symbol, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                OutlinedTextField(
                    to,
                    { to = it.trim() },
                    singleLine = true,
                    shape = SHAPE,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            when (kind) {
                                Kind.Approve -> "Spender address"; Kind.Call -> "Contract address"; else -> "Recipient address"
                            }
                        )
                    }
                )
                if (kind == Kind.Send || kind == Kind.Token || (kind == Kind.Approve && !unlimited)) {
                    OutlinedTextField(
                        amount,
                        { amount = it.replace(',', '.') },
                        singleLine = true,
                        keyboardOptions = numeric,
                        shape = SHAPE,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                "Amount (" + (if (kind == Kind.Send) chain.symbol else token?.symbol
                                    ?: "token") + ")"
                            )
                        }
                    )
                }
                if (kind == Kind.Approve) {
                    Row(
                        Modifier.clickable { unlimited = !unlimited },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (unlimited) "Unlimited: on" else "Unlimited: off",
                            fontSize = 14.sp,
                            color = if (unlimited) Dim.Danger else Dim.Mid
                        )
                    }
                }
                if (kind == Kind.Call) {
                    OutlinedTextField(
                        sig, { sig = it }, shape = SHAPE, modifier = Modifier.fillMaxWidth(),
                        label = { Text("e.g. deposit(uint256)") }
                    )
                    OutlinedTextField(
                        args,
                        { args = it },
                        minLines = 2,
                        shape = SHAPE,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Arguments, one per line") }
                    )
                    OutlinedTextField(
                        value,
                        { value = it.replace(',', '.') },
                        singleLine = true,
                        keyboardOptions = numeric,
                        shape = SHAPE,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Value (${chain.symbol}, optional)") }
                    )
                }
                error?.let { Text(it, fontSize = 13.sp, color = Dim.Danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        onAdd(build()())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Invalid call"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Checking…" else "Add") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
