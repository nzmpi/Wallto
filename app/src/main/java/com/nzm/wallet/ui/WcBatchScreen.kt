package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.MAX_UINT
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import com.nzm.wallet.wc.WcService
import com.reown.walletkit.client.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds

private sealed interface Req {
    class Ok(val version: String, val id: String, val chain: Chain, val items: List<BatchItem>) :
        Req

    class Bad(val why: String, val code: Int = 5700) : Req
}

private fun hexBig(s: String?): BigInteger {
    val t = s?.removePrefix("0x")?.takeIf { it.isNotEmpty() } ?: return BigInteger.ZERO
    return BigInteger(t, 16)
}

private fun decodeCall(
    c: Chain,
    tokens: List<Token>,
    to: String,
    value: BigInteger,
    data: ByteArray
): BatchItem {
    if (data.isEmpty()) {
        return BatchItem("Send ${shortAmount(value, c.decimals)} ${c.symbol}", to, value, data)
    }
    val hex = Numeric.toHexStringNoPrefix(data)
    val sel = hex.take(8)
    fun word(i: Int) = hex.drop(8 + i * 64).take(64)
    fun addr(i: Int) = Keys.toChecksumAddress("0x" + word(i).takeLast(40))
    val tk = tokens.firstOrNull { it.address.equals(to, true) }
    try {
        if (sel == "a9059cbb" && hex.length >= 8 + 128) {
            val dest = addr(0)
            val amt = BigInteger(word(1), 16)
            return if (tk != null) BatchItem(
                "Send ${shortAmount(amt, tk.decimals)} ${tk.symbol}", to, value, data,
                recipient = dest, token = tk, spend = amt
            ) else BatchItem(
                "Send $amt raw units of an unlisted token", to, value, data,
                listOf("Token is not in your list: ${shortAddress(to)}"), recipient = dest
            )
        }
        if (sel == "095ea7b3" && hex.length >= 8 + 128) {
            val spender = addr(0)
            val amt = BigInteger(word(1), 16)
            val unlimited = amt == MAX_UINT
            val shown = when {
                unlimited -> "UNLIMITED"
                tk != null -> shortAmount(amt, tk.decimals)
                else -> "$amt raw units of"
            }
            return BatchItem(
                "Approve $shown ${tk?.symbol ?: "token"}", to, value, data,
                buildList {
                    if (unlimited) add("Unlimited approval lets ${shortAddress(spender)} spend all your tokens.")
                    if (tk == null) add("Token is not in your list: ${shortAddress(to)}")
                },
                recipient = spender, link = "for"
            )
        }
    } catch (_: Exception) {
    }
    return BatchItem(
        "Call 0x$sel",
        to,
        value,
        data,
        listOf("Unknown call. Check the calldata under Advanced details."),
        link = "on"
    )
}

private fun parseRequest(item: WcService.Request, vm: WalletViewModel, signer: String): Req {
    return try {
        val o = JSONArray(item.request.request.params).getJSONObject(0)
        val version = o.optString("version", "1.0")
        val cid = hexBig(o.optString("chainId")).toLong()
        val chain = vm.chains.firstOrNull { it.chainId == cid }
            ?: return Req.Bad("Chain $cid is not saved in this wallet. Add it first.", 5710)
        val from = o.optString("from", "")
        if (from.isNotBlank() && !from.equals(signer, true))
            return Req.Bad(
                "The site asked for a different account than the one it connected with.",
                4100
            )
        o.optJSONObject("capabilities")?.let { caps ->
            for (k in caps.keys()) {
                val optional = caps.optJSONObject(k)?.optBoolean("optional", false) == true
                if (!optional) return Req.Bad(
                    "The site needs a feature this wallet does not support: $k",
                    5700
                )
            }
        }
        val calls = o.getJSONArray("calls")
        if (calls.length() == 0) return Req.Bad("The batch has no calls.", 5700)
        if (calls.length() > 20) return Req.Bad("The batch has too many calls (max 20).", 5700)
        val tokens = if (chain.chainId == vm.selected?.chainId) vm.tokens else emptyList()
        val items = (0 until calls.length()).map { i ->
            val c = calls.getJSONObject(i)
            val toRaw = c.optString("to", "")
            require(isValidAddress(toRaw)) { "Call ${i + 1} has an invalid address" }
            val to = Keys.toChecksumAddress(toRaw)
            val value = hexBig(c.optString("value", "0x0"))
            val data = Numeric.hexStringToByteArray(c.optString("data", "0x").ifBlank { "0x" })
            decodeCall(chain, tokens, to, value, data)
        }
        Req.Ok(version, o.optString("id", ""), chain, items)
    } catch (e: Exception) {
        Req.Bad("Could not read the request: ${e.message}", -32602)
    }
}

@Composable
fun WcBatchScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    item: WcService.Request
) {
    val req = item.request
    val site = req.peerMetaData
    val (warn, blocked) = verdict(item.verify)
    val verified =
        item.verify.isScam != true && item.verify.validation == Wallet.Model.Validation.VALID
    val scope = rememberCoroutineScope()

    val signer by produceState(WcService.sessionAddress(req.topic), item) {
        var tries = 0
        while (value == null && tries < 10) {
            delay(300.milliseconds)
            value = WcService.sessionAddress(req.topic)
            tries++
        }
    }
    val signerNow = signer
    val signerRow = vm.wallets.firstOrNull { it.address.equals(signerNow, ignoreCase = true) }
        ?: if (signerNow != null && signerNow.equals(
                address,
                ignoreCase = true
            )
        ) vm.activeWallet else null
    val isActive = signerNow != null && signerNow.equals(address, ignoreCase = true)

    val parsed = remember(item, signerRow?.id, signerNow) {
        when {
            signerNow == null -> Req.Bad(
                "Could not read which wallet this site connected with. Disconnect the site, then connect again.",
                4100
            )

            signerRow == null -> Req.Bad(
                "The wallet ${shortAddress(signerNow)} this site connected with is not on this phone.",
                4100
            )

            else -> parseRequest(item, vm, signerNow)
        }
    }

    var status by remember { mutableStateOf<BatchStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<TxPlan?>(null) }
    var basePlan by remember { mutableStateOf<TxPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var siteInfo by remember { mutableStateOf(false) }
    var feeEdit by remember { mutableStateOf(false) }
    var nonceEdit by remember { mutableStateOf(false) }
    var gasEdit by remember { mutableStateOf(false) }

    if (parsed is Req.Ok && signerNow != null) {
        LaunchedEffect(item) {
            try {
                val s = Batch.status(parsed.chain, signerNow)
                status = s
                if (s.supportsBatch) {
                    val built = SendTx.plan(
                        parsed.chain,
                        signerNow,
                        signerNow,
                        BigInteger.ZERO,
                        Batch.encode(parsed.items, true)
                    )
                    plan = built
                    basePlan = built
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusError = e.message ?: "Could not prepare the batch"
            }
        }
    }

    var guesses by remember(item) { mutableStateOf<Map<Int, SelectorLookup.Guess>>(emptyMap()) }
    if (parsed is Req.Ok) {
        LaunchedEffect(item) {
            val found = HashMap<Int, SelectorLookup.Guess>()
            parsed.items.forEachIndexed { i, b ->
                if (b.data.isNotEmpty() && b.line.startsWith("Call 0x")) {
                    try {
                        SelectorLookup.guess(Numeric.toHexString(b.data))?.let { found[i] = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
            guesses = found
        }
    }

    fun reject() {
        if (busy) return
        val b = parsed as? Req.Bad
        if (b != null) WcService.respondError(item, b.code, b.why)
        else if (status != null && status?.supportsBatch == false)
            WcService.respondError(
                item,
                5760,
                "This account cannot run atomic batches on this chain"
            )
        else WcService.respondError(item, 4001, "User rejected")
    }

    val canSend = !blocked && !busy && plan != null && parsed is Req.Ok

    fun approve() {
        if (!canSend) return
        val pl = plan ?: return
        val row = signerRow ?: return
        scope.launch {
            busy = true
            error = null
            try {
                vm.annotateNext(site?.name ?: site?.url, "Batch of ${parsed.items.size} calls")
                val hash = vault.withCredentials(activity, row.id) { c: Credentials ->
                    check(
                        c.address.equals(
                            signerNow,
                            ignoreCase = true
                        )
                    ) { "Wallet key does not match the site's account" }
                    SendTx.signAndSend(pl, c)
                }
                val bundleId = parsed.id.ifBlank {
                    "0x" + ByteArray(32).also { SecureRandom().nextBytes(it) }
                        .joinToString("") { "%02x".format(it) }
                }
                WcService.saveCall(bundleId, parsed.chain.chainId, hash)
                // EIP-5792 version 1 answers with the id alone, version 2 with an object
                val result = if (parsed.version.startsWith("1")) bundleId else JSONObject().put(
                    "id",
                    bundleId
                ).toString()
                WcService.respondResult(item, result)
                if (isActive) vm.refresh()
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
        onDismiss = { reject() },
        bottom = {
            OutlineButton(
                if (parsed is Req.Bad) "Close" else "Reject",
                { reject() },
                Modifier.weight(1f)
            )
            if (parsed is Req.Ok) {
                PrimaryButton(
                    if (busy) "Working…" else "Send",
                    { approve() },
                    Modifier
                        .weight(1f)
                        .alpha(if (canSend || busy) 1f else 0.4f)
                )
            }
        }
    ) {
        PageHeader("Send batch") { reject() }

        Column(
            Modifier
                .clickable { siteInfo = true }
                .padding(top = 4.dp)) {
            Text(
                site?.name?.ifBlank { null } ?: "Unknown site",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (verified) TxOk else Dim.Danger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                site?.url ?: "",
                fontSize = 14.sp,
                color = Dim.Mid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (blocked) Text(warn, fontSize = 14.sp, color = Dim.Danger)
        if (signerRow != null && signerNow != null && !isActive) {
            Text(
                "Not your active wallet. This will be signed by ${signerRow.name} (${
                    shortAddress(
                        signerNow
                    )
                }).",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger
            )
        }

        when (parsed) {
            is Req.Bad -> Text(parsed.why, fontSize = 15.sp, color = Dim.Danger)
            is Req.Ok -> {
                val c = parsed.chain
                TxAmount(
                    "Sending",
                    "${parsed.items.size} call${if (parsed.items.size == 1) "" else "s"} in one transaction"
                )
                if (signerNow != null) TxCard {
                    TxPartyRow(
                        "Account",
                        rememberParty(vm, signerNow)
                    )
                }

                val s = status
                when {
                    statusError != null -> Text(
                        statusError ?: "",
                        fontSize = 14.sp,
                        color = Dim.Danger
                    )

                    s == null -> Text("Checking account…", fontSize = 14.sp, color = Dim.Mid)
                    !s.supportsBatch -> Text(
                        "This account cannot run batches on ${c.name}. Delegate it to a batch contract first (Settings → Smart account).",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dim.Danger
                    )

                    else -> {}
                }

                parsed.items.forEachIndexed { i, b ->
                    TxCard {
                        val g = guesses[i]
                        Text("Call ${i + 1}", fontSize = 13.sp, color = Dim.Mid)
                        Text(
                            g?.let { "Call " + it.signature.substringBefore('(') } ?: b.line,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        val target = b.recipient ?: b.to
                        TxPartyRow(
                            b.link.replaceFirstChar { ch -> ch.uppercase() },
                            rememberParty(vm, target)
                        )
                        if (b.recipient != null) TxRow("Token contract", shortAddress(b.to))
                        if (b.value.signum() > 0 && b.data.isNotEmpty()) TxRow(
                            "Value",
                            fmt(b.value, c)
                        )
                        b.warnings.filter { !(g != null && it.startsWith("Unknown call")) }
                            .forEach { w ->
                                Text(
                                    "WARNING: $w",
                                    fontSize = 13.sp,
                                    color = Dim.Danger,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        if (g != null) {
                            Spacer(Modifier.height(10.dp))
                            GuessInfo(g)
                        }
                    }
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
                        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (c.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
                    }
                }

                val p = plan
                val base = basePlan
                TxCard(Modifier.clickable(enabled = p != null && !busy) { feeEdit = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Max fee", fontSize = 15.sp, color = Dim.Mid)
                            if (p != null && base != null && p.fee != base.fee) Text(
                                "Edited",
                                fontSize = 12.sp,
                                color = Dim.Accent
                            )
                        }
                        if (p == null) {
                            Text(
                                if (statusError == null && status?.supportsBatch != false) "Preparing…" else "Unavailable",
                                fontSize = 15.sp,
                                color = Dim.Mid
                            )
                        } else {
                            val fiat = if (c.chainId == vm.selected?.chainId) vm.fiat(
                                null,
                                p.maxFeeWei
                            ) else null
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    fiat ?: fmt(p.maxFeeWei, c),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (fiat != null) Text(
                                    fmt(p.maxFeeWei, c),
                                    fontSize = 12.sp,
                                    color = Dim.Mid
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Icon(Ic.Edit, "Edit fee", Modifier.size(18.dp), tint = Dim.Mid)
                        }
                    }
                }

                if (p != null) {
                    SimulationCard(vm, c, p)
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
                            Text(
                                p.nonce.toString(),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
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
                        status?.delegate?.let {
                            TxLabel("Delegated to", top = 12)
                            TxMono(it)
                        }
                        TxLabel("Calldata", top = 12)
                        TxMono(p.data)
                    }
                }
                Text(
                    "All calls run together: if one fails, none are applied. Each call runs with your account's authority.",
                    fontSize = 13.sp, color = Dim.Mid
                )
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = Dim.Danger) }
    }

    if (siteInfo) {
        AlertDialog(
            onDismissRequest = { siteInfo = false },
            title = { Text("Domain check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(warn, color = if (blocked || !verified) Dim.Danger else TxOk)
                    Text("Site says: ${site?.url ?: "unknown"}", fontSize = 14.sp, color = Dim.Mid)
                }
            },
            confirmButton = { TextButton(onClick = { siteInfo = false }) { Text("Close") } }
        )
    }

    val p = plan
    val base = basePlan
    if (feeEdit && p != null && base != null) {
        TxFeeDialog(
            plan = p,
            base = base,
            onSave = { plan = p.copy(fee = it); feeEdit = false },
            onDismiss = { feeEdit = false })
    }
    if (nonceEdit && p != null) {
        TxNonceDialog(
            current = p.nonce,
            onSave = { plan = p.copy(nonce = it); nonceEdit = false },
            onDismiss = { nonceEdit = false })
    }
    if (gasEdit && p != null && base != null) {
        TxGasDialog(
            current = p.gasLimit,
            base = base.gasLimit,
            onSave = { plan = p.copy(gasLimit = it); gasEdit = false },
            onDismiss = { gasEdit = false }
        )
    }
}
