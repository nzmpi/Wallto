package com.nzm.wallet.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.AbiCodec
import com.nzm.wallet.abi.AbiFn
import com.nzm.wallet.abi.FunctionSig
import com.nzm.wallet.abi.Sourcify
import com.nzm.wallet.abi.abiFunctions
import com.nzm.wallet.chain.*
import com.nzm.wallet.chain.Rpc.ethCall
import com.nzm.wallet.chain.Rpc.isContract
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.storage.SavedContract
import com.nzm.wallet.storage.SavedContracts
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.SHAPE
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.parseAmount
import com.nzm.wallet.utils.Helpers.shortAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger

private class Prepared(
    val to: String,
    val sig: FunctionSig,
    val values: List<Any>,
    val data: ByteArray
)

private suspend fun prepare(
    chain: Chain,
    addr: String,
    sigText: String,
    argsText: String
): Prepared {
    require(isValidAddress(addr.trim())) { "Invalid contract address or checksum" }
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
    require(isContract(chain.rpcUrl, to)) { "No contract at this address on this chain" }
    return Prepared(to, sig, values, AbiCodec.encodeCall(sig, values))
}

@Composable
fun ContractDialog(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    chain: Chain,
    from: String,
    saved: SavedContract? = null,
    onClose: (sent: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var write by remember { mutableStateOf(false) }
    var contract by remember { mutableStateOf(saved?.address ?: "") }
    var abi by remember { mutableStateOf(saved?.abi) }
    var abiNote by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(saved?.abi == null) }
    var fn by remember { mutableStateOf<AbiFn?>(null) }
    val argVals = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("") }
    var sigText by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<Prepared?>(null) }
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var basePlan by remember { mutableStateOf<TxPlan?>(null) }
    var feeEdit by remember { mutableStateOf(false) }
    var nonceEdit by remember { mutableStateOf(false) }
    var gasEdit by remember { mutableStateOf(false) }
    var readResult by remember { mutableStateOf<List<String>?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Pending...") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(true) }
    val focus = LocalFocusManager.current

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

    val fns = remember(abi) { abi?.let { abiFunctions(it) } }
    val useAbi = !manual && fns != null
    val effSig = if (useAbi) fn?.sigText ?: "" else sigText
    val effArgs = if (useAbi) argVals.joinToString("\n") else argsText
    val parsed = remember(effSig) {
        if (effSig.isBlank()) null else runCatching {
            AbiCodec.parseSignature(effSig)
        }
    }
    val validAddr = isValidAddress(contract.trim())
    val savedNow = if (validAddr) SavedContracts.find(chain.chainId, contract) else null
    val canGo = !busy && validAddr && effSig.isNotBlank()

    val p = plan
    val base = basePlan
    val h = hash
    val prep = prepared

    fun goBack() {
        when {
            busy -> {}
            h != null -> onClose(true)
            p != null -> {
                plan = null; prepared = null; basePlan = null
            }

            else -> onClose(false)
        }
    }

    fun act() = launchSafe {
        readResult = null
        val pr = prepare(chain, contract, effSig, effArgs)
        if (!write) {
            val raw = ethCall(chain.rpcUrl, from, pr.to, data = pr.data)
            readResult = if (pr.sig.outputs.isEmpty()) {
                listOf("Raw output: ${Numeric.toHexString(raw)}")
            } else {
                AbiCodec.decodeOutputs(pr.sig.outputs, raw)
                    .mapIndexed { i, v -> "${i + 1}. ${pr.sig.outputs[i].canonical}: $v" }
            }
        } else {
            val value =
                if (valueText.isBlank()) BigInteger.ZERO else parseAmount(valueText, chain.decimals)
            val built = SendTx.plan(chain, from, pr.to, value, Numeric.toHexString(pr.data))
            prepared = pr
            basePlan = built
            plan = built
        }
    }

    TxPage(
        onDismiss = { if (!busy && h == null) goBack() },
        bottom = {
            when {
                h != null -> {
                    if (chain.explorerUrl != null) {
                        OutlineButton("Explorer", {
                            activity.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "${chain.explorerUrl}/tx/$h".toUri()
                                )
                            )
                        }, Modifier.weight(1f))
                    }
                    PrimaryButton("Done", { onClose(true) }, Modifier.weight(1f))
                }

                p != null && prep != null -> {
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
                        if (busy) "Working…" else if (write) "Review" else "Call",
                        onClick = { act() }, enabled = canGo, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        when {
            // ---------- status ----------
            h != null -> {
                PageHeader("Sent") { onClose(true) }
                TxAmount("Called", prep?.sig?.signature?.substringBefore('(') ?: "Contract")
                TxCard {
                    TxPartyRow("From", rememberParty(vm, from))
                    TxLine()
                    TxPartyRow("Contract", contractParty(vm, p?.to ?: contract, savedNow))
                }
                TxCard {
                    TxRow(
                        "Status", status,
                        when {
                            status == "Confirmed" -> TxOk
                            status.startsWith("FAILED") -> Dim.Danger
                            else -> Dim.Ink
                        }
                    )
                    TxLine()
                    TxLabel("Transaction hash")
                    TxMono(h)
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ---------- review ----------
            p != null && prep != null -> {
                PageHeader("Review") { goBack() }
                val name = prep.sig.signature.substringBefore('(')
                TxAmount(
                    "Calling",
                    name,
                    if (p.value.signum() > 0) "with ${
                        fmt(
                            p.value,
                            chain,
                            chain.decimals
                        )
                    }" else null
                )
                TxCard {
                    TxPartyRow("From", rememberParty(vm, p.from))
                    TxLine()
                    TxPartyRow("Contract", contractParty(vm, p.to, savedNow))
                }
                TxCard {
                    TxLabel("Function", top = 4)
                    Text(prep.sig.signature, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Selector 0x${Numeric.toHexStringNoPrefix(prep.sig.selector)}",
                        fontSize = 12.sp,
                        color = Dim.Mid
                    )
                    prep.values.forEachIndexed { i, v ->
                        val t = prep.sig.inputs[i]
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Argument ${i + 1} (${t.canonical})",
                            fontSize = 13.sp,
                            color = Dim.Mid
                        )
                        TxMono(AbiCodec.display(t, v))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                AbiCodec.riskNotes(prep.sig, prep.values).forEach {
                    Text("WARNING: $it", fontSize = 14.sp, color = Dim.Danger)
                }
                TxCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Network",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
                    }
                }
                TxCard(Modifier.clickable(enabled = !busy) { feeEdit = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Max fee", fontSize = 15.sp, color = Dim.Mid)
                            if (base != null && p.fee != base.fee) Text(
                                "Edited",
                                fontSize = 12.sp,
                                color = Dim.Accent
                            )
                        }
                        val fiat = vm.fiat(null, p.maxFeeWei)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                fiat ?: fmt(
                                    p.maxFeeWei,
                                    chain,
                                    chain.decimals
                                ), fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                            )
                            if (fiat != null) Text(
                                fmt(
                                    p.maxFeeWei,
                                    chain,
                                    chain.decimals
                                ), fontSize = 12.sp, color = Dim.Mid
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit fee", Modifier.size(18.dp), tint = Dim.Mid)
                    }
                }
                TxCard {
                    TxRow(
                        "Max total",
                        fmt(p.maxTotalWei, chain, chain.decimals)
                    )
                }
                SimulationCard(vm, chain, p)
                TxAdvanced {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { nonceEdit = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Nonce",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(p.nonce.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit nonce", Modifier.size(16.dp), tint = Dim.Mid)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { gasEdit = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Gas limit",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            p.gasLimit.toString(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(Ic.Edit, "Edit gas limit", Modifier.size(16.dp), tint = Dim.Mid)
                    }
                    TxLabel("Calldata", top = 12)
                    TxMono(p.data)
                }
                Text(
                    "Only sign calls you understand. A site or chat telling you to call a contract is a common scam.",
                    fontSize = 13.sp, color = Dim.Mid
                )
            }

            // ---------- input ----------
            else -> {
                PageHeader("Contract call") { goBack() }
                TxCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Network",
                            fontSize = 15.sp,
                            color = Dim.Mid,
                            modifier = Modifier.weight(1f)
                        )
                        Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
                    }
                }

                // read / write
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModePill("Read", !write) {
                        write = false; fn = null; listOpen = true; readResult = null
                    }
                    ModePill("Write", write) {
                        write = true; fn = null; listOpen = true; readResult = null
                    }
                }

                // contract
                TxCard {
                    Text("Contract", fontSize = 15.sp, color = Dim.Mid)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        contract, { contract = it.trim(); readResult = null },
                        label = { Text("Contract address") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = SHAPE
                    )
                    if (savedNow != null) {
                        Text(
                            "Saved as ${savedNow.name}",
                            fontSize = 13.sp,
                            color = Dim.Accent,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineButton("Load ABI", {
                            launchSafe {
                                val addr = Keys.toChecksumAddress(contract.trim())
                                val r = Sourcify.fetch(chain.chainId, addr)
                                abi = r.abi
                                abiNote = r.note
                                manual = false
                                fn = null
                                listOpen = true
                                argVals.clear()
                                savedNow?.let { SavedContracts.setAbi(it.id, r.abi) }
                            }
                        }, Modifier.weight(1f))
                        if (savedNow == null) {
                            OutlineButton("Save", { saveDialog = true }, Modifier.weight(1f))
                        }
                    }
                    if (!validAddr && contract.isNotBlank()) {
                        Text(
                            "Invalid address or checksum",
                            fontSize = 13.sp,
                            color = Dim.Danger,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Text(
                        "Load ABI asks sourcify.dev for the verified ABI. Sourcify sees which address you look up.",
                        fontSize = 12.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
                    )
                    abiNote?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = Dim.Accent,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                // function
                if (useAbi) {
                    val shown = fns.list.filter { it.readOnly == !write }
                        .filter { filter.isBlank() || it.name.contains(filter.trim(), true) }
                    TxCard {
                        Text(
                            if (write) "Write functions" else "Read functions",
                            fontSize = 15.sp,
                            color = Dim.Mid
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            filter,
                            { filter = it; listOpen = true },
                            label = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) listOpen = true },
                            shape = SHAPE
                        )
                        if (listOpen) {
                            Spacer(Modifier.height(8.dp))
                            if (shown.isEmpty()) Text(
                                "No matching functions.",
                                fontSize = 14.sp,
                                color = Dim.Mid
                            )
                            shown.take(15).forEach { f ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (fn?.sigText == f.sigText) Dim.RaisedSelected else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable {
                                            fn = f
                                            filter = f.name
                                            listOpen = false
                                            focus.clearFocus()
                                            readResult = null
                                            argVals.clear()
                                            repeat(f.inputTypes.size) { argVals.add("") }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        f.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        "(" + f.inputTypes.joinToString(", ") + ")" + if (f.payable) " payable" else "",
                                        fontSize = 12.sp, color = Dim.Mid, maxLines = 1
                                    )
                                }
                            }
                            if (shown.size > 15) Text(
                                "Showing 15 of ${shown.size}. Search to narrow.",
                                fontSize = 12.sp,
                                color = Dim.Mid,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        if ((fns.skipped) > 0) Text(
                            "${fns.skipped} function(s) use types this wallet cannot encode.",
                            fontSize = 12.sp,
                            color = Dim.Mid,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            manual = true; fn = null; listOpen = true
                        }) { Text("Enter signature by hand") }
                    }
                    fn?.let { f ->
                        TxCard {
                            Text("Arguments", fontSize = 15.sp, color = Dim.Mid)
                            if (f.inputTypes.isEmpty()) Text(
                                "No arguments.",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            f.inputTypes.forEachIndexed { i, t ->
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    argVals.getOrElse(i) { "" },
                                    { v -> if (i < argVals.size) argVals[i] = v },
                                    label = {
                                        Text((f.inputNames.getOrNull(i)?.ifBlank { null }
                                            ?: "arg${i + 1}") + " ($t)")
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = SHAPE
                                )
                            }
                        }
                    }
                } else {
                    TxCard {
                        Text("Function", fontSize = 15.sp, color = Dim.Mid)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            sigText, { sigText = it },
                            label = { Text("e.g. balanceOf(address) returns (uint256)") },
                            modifier = Modifier.fillMaxWidth(), shape = SHAPE
                        )
                        parsed?.let { r ->
                            val s = r.getOrNull()
                            if (s != null) {
                                val args = if (s.inputs.isEmpty()) "no arguments"
                                else s.inputs.mapIndexed { i, t -> "${i + 1}=${t.canonical}" }
                                    .joinToString("  ")
                                Text(
                                    "0x${Numeric.toHexStringNoPrefix(s.selector)}  ${s.signature}\nArgs: $args",
                                    fontSize = 12.sp,
                                    color = Dim.Mid,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            } else {
                                Text(
                                    r.exceptionOrNull()?.message ?: "Bad signature",
                                    fontSize = 13.sp,
                                    color = Dim.Danger,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            argsText,
                            { argsText = it },
                            label = { Text("Arguments, one per line") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                            shape = SHAPE
                        )
                        if (fns != null) {
                            TextButton(onClick = { manual = false }) { Text("Pick from ABI") }
                        }
                    }
                }

                if (write && (fn?.payable != false)) {
                    TxCard {
                        OutlinedTextField(
                            valueText,
                            { valueText = it },
                            label = { Text("Value (${chain.symbol}, optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            shape = SHAPE
                        )
                    }
                }

                readResult?.let { r ->
                    TxCard {
                        Text("Result", fontSize = 15.sp, color = Dim.Mid)
                        Spacer(Modifier.height(6.dp))
                        r.forEach {
                            Text(
                                it,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
        if (busy && p == null) Text("Working…", fontSize = 14.sp, color = Dim.Mid)
    }

    if (feeEdit && p != null && base != null) {
        TxFeeDialog(
            plan = p, base = base,
            onSave = { plan = p.copy(fee = it); feeEdit = false },
            onDismiss = { feeEdit = false }
        )
    }
    if (nonceEdit && p != null) {
        TxNonceDialog(
            current = p.nonce,
            onSave = { plan = p.copy(nonce = it); nonceEdit = false },
            onDismiss = { nonceEdit = false }
        )
    }
    if (gasEdit && p != null && base != null) {
        TxGasDialog(
            current = p.gasLimit,
            base = base.gasLimit,
            onSave = { plan = p.copy(gasLimit = it); gasEdit = false },
            onDismiss = { gasEdit = false }
        )
    }

    if (saveDialog && validAddr) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save contract") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TxMono(contract)
                    Text(
                        "Saved on ${chain.name}, with its ABI if you loaded one.",
                        fontSize = 13.sp,
                        color = Dim.Mid
                    )
                    OutlinedTextField(
                        name, { name = it.take(32) }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = SHAPE
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    SavedContracts.add(
                        chain.chainId,
                        Keys.toChecksumAddress(contract.trim()),
                        name,
                        abi
                    )
                    saveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun contractParty(vm: WalletViewModel, address: String, saved: SavedContract?): Party {
    val party = rememberParty(vm, address)
    return if (party.sub == null && saved != null) Party(
        saved.name,
        shortAddress(address)
    ) else party
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Dim.RaisedSelected else Dim.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Dim.Ink else Dim.Mid
        )
    }
}
