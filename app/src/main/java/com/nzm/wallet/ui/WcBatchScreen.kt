package com.nzm.wallet.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
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
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom

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

    val session = rememberSessionSigner(
        vault = vault,
        activity = activity,
        vm = vm,
        address = address,
        topic = req.topic,
        key = item
    )
    val signerNow = session.address
    val signerRow = session.row
    val isActive = session.isActive

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
    val editor = rememberPlanEditor()
    val runner = rememberBusyRunner()
    val busy = runner.busy
    val error = runner.error

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
                    editor.load(built)
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

    val canSend = !blocked && !busy && editor.plan != null && parsed is Req.Ok

    fun approve() {
        if (!canSend) return
        val pl = editor.plan ?: return
        runner.launch {
            vm.annotateNext(site?.name ?: site?.url, "Batch of ${parsed.items.size} calls")
            val hash = session.sign { c -> SendTx.signAndSend(pl, c) }
            val bundleId = parsed.id.ifBlank {
                "0x" + ByteArray(32).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
            }
            WcService.saveCall(bundleId, parsed.chain.chainId, hash)
            // EIP-5792 version 1 answers with the id alone, version 2 with an object
            val result = if (parsed.version.startsWith("1")) bundleId
            else JSONObject().put("id", bundleId).toString()
            WcService.respondResult(item, result)
            if (isActive) vm.refresh()
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
        WcSiteHeader(
            name = site?.name,
            url = site?.url,
            verified = verified,
            warn = warn,
            blocked = blocked,
            otherWallet = session.otherWallet
        )

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

                TxNetworkCard(c)

                TxFeeCard(
                    vm, c, editor, busy,
                    waiting = if (statusError == null && status?.supportsBatch != false) "Preparing…" else "Unavailable"
                )

                val p = editor.plan
                if (p != null) {
                    SimulationCard(vm, c, p)
                    TxAdvanced {
                        TxPlanRows(editor, busy)
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

    PlanEditorDialogs(editor)
}
