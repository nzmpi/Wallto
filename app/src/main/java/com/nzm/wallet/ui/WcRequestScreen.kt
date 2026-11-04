package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.MAX_UINT
import com.nzm.wallet.utils.Helpers.fmt
import com.nzm.wallet.utils.Helpers.shortAddress
import com.nzm.wallet.utils.Helpers.shortAmount
import com.nzm.wallet.wc.PersonalMsg
import com.nzm.wallet.wc.TypedReq
import com.nzm.wallet.wc.WcService
import com.nzm.wallet.wc.WcSigner
import com.nzm.wallet.wc.WcTx
import com.reown.walletkit.client.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

/** Returns (line, blocked). Blocked sites cannot be approved. */
internal fun verdict(v: Wallet.Model.VerifyContext): Pair<String, Boolean> = when {
    v.isScam == true -> "WARNING: this site is flagged as a scam." to true
    v.validation == Wallet.Model.Validation.INVALID ->
        "WARNING: the site's domain does not match what it claims. Likely phishing." to true

    v.validation == Wallet.Model.Validation.VALID -> "Verified domain: ${v.origin}" to false
    else -> "Domain could not be verified. Check the name and address yourself." to false
}

private fun isVerified(v: Wallet.Model.VerifyContext) =
    v.isScam != true && v.validation == Wallet.Model.Validation.VALID

private sealed interface Parsed {
    class Msg(val m: PersonalMsg) : Parsed
    class Typed(val t: TypedReq) : Parsed
    class Tx(val t: WcTx) : Parsed
    class Bad(val why: String) : Parsed
}

/** What a transaction does, in words. Only the common calls are decoded. */
private class Summary(
    val verb: String,
    val amount: String?,
    val fiatToken: Token?,
    val fiatNative: Boolean,
    val fiatRaw: BigInteger?,
    val party: String?,
    val partyLabel: String,
    val danger: Boolean,
    val plain: Boolean,
    val isTransfer: Boolean,
    val exact: String? = null
)

private fun analyze(tx: WcTx, chain: Chain, tokens: List<Token>): Summary {
    if (tx.data.isEmpty()) {
        return Summary(
            "Sending", shortAmount(tx.value, chain.decimals) + " " + chain.symbol,
            null, true, tx.value, tx.to, "To", danger = false, plain = true, isTransfer = true,
            exact = fmt(tx.value, chain, chain.decimals)
        )
    }
    val d = tx.data.drop(2).lowercase()
    val sel = d.take(8)
    fun word(i: Int) = d.drop(8 + i * 64).take(64)
    fun addr(i: Int) = Keys.toChecksumAddress("0x" + word(i).takeLast(40))
    val token = tokens.firstOrNull { it.address.equals(tx.to, true) }
    fun amountText(w: String): Pair<String, BigInteger> {
        val v = BigInteger(w, 16)
        if (v == MAX_UINT) return "Unlimited" + (token?.let { " ${it.symbol}" } ?: "") to v
        val dec = token?.decimals ?: return "$v (raw units)" to v
        return shortAmount(v, dec) + " ${token.symbol}" to v
    }

    fun exactOf(v: BigInteger): String =
        if (v == MAX_UINT) "Unlimited"
        else token?.let { fmt(v, it, it.decimals) }
            ?: "$v (raw units)"
    return try {
        when (sel) {
            "a9059cbb" if d.length >= 8 + 128 -> {
                val (t, raw) = amountText(word(1))
                Summary(
                    "Sending", t, token, false, raw, addr(0), "To",
                    danger = false,
                    plain = false,
                    isTransfer = true,
                    exact = exactOf(raw)
                )
            }

            "095ea7b3" if d.length >= 8 + 128 -> {
                val (t, raw) = amountText(word(1))
                Summary(
                    "Approving", t, null, false, null, addr(0), "Spender",
                    danger = true,
                    plain = false,
                    isTransfer = false,
                    exact = exactOf(raw)
                )
            }

            "a22cb465" if d.length >= 8 + 128 ->
                Summary(
                    "Approving all tokens", null, null, false, null, addr(0), "Operator",
                    danger = true,
                    plain = false,
                    isTransfer = false
                )

            "23b872dd" if d.length >= 8 + 192 -> {
                val (t, raw) = amountText(word(2))
                Summary(
                    "Transfer from ${addr(0)}", t, token, false, raw, addr(1), "To",
                    danger = false,
                    plain = false,
                    isTransfer = false,
                    exact = exactOf(raw)
                )
            }

            else -> other(tx, chain)
        }
    } catch (_: Exception) {
        other(tx, chain)
    }
}

private fun other(tx: WcTx, chain: Chain) = Summary(
    "Contract call",
    if (tx.value.signum() > 0) shortAmount(tx.value, chain.decimals) + " " + chain.symbol else null,
    null, true, tx.value.takeIf { it.signum() > 0 }, tx.to, "Contract",
    danger = false,
    plain = false,
    isTransfer = false,
    exact = if (tx.value.signum() > 0) fmt(
        tx.value,
        chain,
        chain.decimals
    ) else null
)

/** Full-screen page for a request from a connected site. Signs with the wallet the site connected with. */
@Composable
fun WcRequestScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    item: WcService.Request
) {
    val req = item.request
    val cid = req.chainId?.removePrefix("eip155:")?.toLongOrNull()
    val chain = vm.chains.firstOrNull { it.chainId == cid }
    val (warn, blocked) = verdict(item.verify)
    val verified = isVerified(item.verify)
    val site = req.peerMetaData
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
    val signerRow = vm.wallets.firstOrNull { it.address.equals(signer, ignoreCase = true) }
        ?: if (signer != null && signer.equals(
                address,
                ignoreCase = true
            )
        ) vm.activeWallet else null
    val isActive = signer != null && signer.equals(address, ignoreCase = true)

    val parsed = remember(item, signerRow?.id, signerNow) {
        if (chain == null) Parsed.Bad("Chain ${req.chainId} is not saved in this wallet. Add it first.")
        else if (signerNow == null)
            Parsed.Bad("Could not read which wallet this site connected with. Disconnect the site, then connect again.")
        else if (signerRow == null)
            Parsed.Bad("The wallet ${shortAddress(signerNow)} this site connected with is not on this phone. Disconnect the site, then connect again.")
        else try {
            when (req.request.method) {
                WcService.PERSONAL_SIGN -> Parsed.Msg(
                    WcSigner.parsePersonal(
                        req.request.params,
                        signerNow
                    )
                )

                WcService.TYPED_V4 -> {
                    val t = WcSigner.parseTyped(req.request.params, signerNow)
                    if (t.domainChainId != null && t.domainChainId != chain.chainId)
                        Parsed.Bad("Signed data is for chain ${t.domainChainId}, request is for ${chain.chainId}.")
                    else Parsed.Typed(t)
                }

                WcService.SEND_TX -> Parsed.Tx(WcSigner.parseTx(req.request.params, signerNow))
                else -> Parsed.Bad("Unsupported method")
            }
        } catch (e: Exception) {
            Parsed.Bad(e.message ?: "Could not read request")
        }
    }

    val editor = rememberPlanEditor()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var siteInfo by remember { mutableStateOf(false) }

    if (parsed is Parsed.Tx && chain != null && signerNow != null) {
        LaunchedEffect(item) {
            try {
                val p =
                    SendTx.plan(chain, signerNow, parsed.t.to, parsed.t.value, data = parsed.t.data)
                editor.load(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not prepare transaction"
            }
        }
    }

    suspend fun <T> asSigner(block: suspend (Credentials) -> T): T {
        val row = signerRow ?: error("Wallet not found")
        return vault.withCredentials(activity, row.id) { c ->
            check(
                c.address.equals(
                    signer,
                    ignoreCase = true
                )
            ) { "Wallet key does not match the site's account" }
            block(c)
        }
    }

    val tokens =
        if (chain != null && chain.chainId == vm.selected?.chainId) vm.tokens else emptyList()
    val summary =
        if (parsed is Parsed.Tx && chain != null) analyze(parsed.t, chain, tokens) else null

    var guess by remember(item) { mutableStateOf<SelectorLookup.Guess?>(null) }
    if (parsed is Parsed.Tx && summary?.verb == "Contract call" && parsed.t.data.isNotEmpty()) {
        LaunchedEffect(item) {
            guess = try {
                SelectorLookup.guess(parsed.t.data)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    val title = when (parsed) {
        is Parsed.Msg -> "Sign message"
        is Parsed.Typed -> "Sign typed data"
        is Parsed.Tx -> "Send transaction"
        is Parsed.Bad -> "Request"
    }
    val canApprove = !blocked && !busy && when (parsed) {
        is Parsed.Tx -> editor.plan != null
        is Parsed.Bad -> false
        else -> true
    }

    fun reject() {
        if (!busy) WcService.respondError(item)
    }

    fun approve() {
        if (!canApprove) return
        scope.launch {
            busy = true
            error = null
            try {
                val result: String = when (parsed) {
                    is Parsed.Msg -> asSigner { WcSigner.signPersonal(it, parsed.m.bytes) }
                    is Parsed.Typed -> asSigner { WcSigner.signTyped(it, parsed.t) }
                    is Parsed.Tx -> {
                        val pl = editor.plan ?: return@launch
                        val desc = WcSigner.describe(parsed.t, tokens).first
                        vm.annotateNext(site?.name ?: site?.url, desc.lineSequence().first())
                        asSigner { SendTx.signAndSend(pl, it) }
                    }

                    is Parsed.Bad -> return@launch
                }
                WcService.respondResult(item, result)
                if (parsed is Parsed.Tx && isActive) vm.refresh()
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
        onDismissRequest = { reject() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // dialogs are separate windows: FLAG_SECURE on the activity does not cover them
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Dim.Bg, contentColor = Dim.Ink) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PageHeader(title) { reject() }

                    // site: green when the domain is verified, red otherwise. Tap for the check.
                    Column(
                        Modifier
                            .clickable { siteInfo = true }
                            .padding(top = 4.dp)) {
                        Text(
                            site?.name?.ifBlank { null } ?: "Unknown site",
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            color = if (verified) TxOk else Dim.Danger, maxLines = 1,
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

                    // amount
                    if (summary != null && chain != null) {
                        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                            Text(
                                if (summary.amount == null) "" else summary.verb,
                                fontSize = 15.sp,
                                color = if (summary.danger) Dim.Danger else Dim.Mid
                            )
                            Text(
                                summary.amount ?: summary.verb,
                                fontSize = if (summary.amount == null) 24.sp else 30.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (summary.danger) Dim.Danger else Dim.Ink
                            )
                            val raw = summary.fiatRaw
                            val fiat = when {
                                raw == null || chain.chainId != vm.selected?.chainId -> null
                                summary.fiatNative -> vm.fiat(null, raw)
                                summary.fiatToken != null -> vm.fiat(summary.fiatToken, raw)
                                else -> null
                            }
                            fiat?.let { Text(it, fontSize = 16.sp, color = Dim.Mid) }
                        }
                    }

                    when (parsed) {
                        is Parsed.Bad -> Text(parsed.why, color = Dim.Danger, fontSize = 15.sp)
                        else -> {}
                    }

                    // from / to
                    if (parsed !is Parsed.Bad && signerNow != null) {
                        TxCard {
                            TxPartyRow(
                                if (parsed is Parsed.Tx) "From" else "Wallet",
                                rememberParty(vm, signerNow)
                            )
                            if (summary?.party != null) {
                                TxLine()
                                TxPartyRow(summary.partyLabel, rememberParty(vm, summary.party))
                            }
                        }
                    }

                    // value sent along with a contract call
                    if (summary != null && chain != null && !summary.plain && parsed is Parsed.Tx && parsed.t.value.signum() > 0
                        && !(summary.fiatNative && summary.amount != null)
                    ) {
                        TxCard {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Also sends", fontSize = 15.sp, color = Dim.Mid)
                                Text(
                                    shortAmount(
                                        parsed.t.value,
                                        chain.decimals
                                    ) + " " + chain.symbol,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (chain != null) TxNetworkCard(chain)

                    // what the request is, in words
                    when (parsed) {
                        is Parsed.Msg -> TxCard {
                            Text("Message", fontSize = 14.sp, color = Dim.Mid)
                            Spacer(Modifier.height(6.dp))
                            if (!parsed.m.isText) Text(
                                "Binary data, not readable text:",
                                color = Dim.Danger,
                                fontSize = 14.sp
                            )
                            TxMono(parsed.m.text)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Signing proves you own this address. It does not move funds.",
                                fontSize = 13.sp,
                                color = Dim.Mid
                            )
                        }

                        is Parsed.Typed -> TxCard {
                            Text("Type: ${parsed.t.primaryType}", fontWeight = FontWeight.SemiBold)
                            parsed.t.domainName?.let { Text("Domain: $it", fontSize = 14.sp) }
                            parsed.t.verifyingContract?.let {
                                Text(
                                    "Contract: $it",
                                    fontSize = 13.sp,
                                    color = Dim.Mid
                                )
                            }
                            if (parsed.t.looksLikePermit) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "This kind of signature can authorize spending your tokens without a transaction. " +
                                            "Only sign it on a site you trust.",
                                    color = Dim.Danger, fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TxMono(parsed.t.pretty)
                        }

                        is Parsed.Tx -> {
                            if (summary != null && !summary.plain && !summary.isTransfer) {
                                val (desc, danger) = WcSigner.describe(parsed.t, tokens)
                                TxCard {
                                    Text(
                                        desc,
                                        color = if (danger) Dim.Danger else Dim.Ink,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            guess?.let { GuessCard(it) }
                        }

                        else -> {}
                    }

                    if (parsed is Parsed.Tx && chain != null) {
                        TxFeeCard(
                            vm,
                            chain,
                            editor,
                            busy,
                            waiting = if (error == null) "Preparing…" else "Unavailable"
                        )
                    }

                    if (parsed is Parsed.Tx && chain != null) editor.plan?.let {
                        SimulationCard(
                            vm,
                            chain,
                            it
                        )
                    }

                    if (parsed !is Parsed.Bad) {
                        TxAdvanced {
                            when (parsed) {
                                is Parsed.Tx -> {
                                    if (editor.plan != null) {
                                        TxPlanRows(editor, busy)
                                        summary?.exact?.let {
                                            TxLabel("Exact amount", top = 12)
                                            TxMono(it)
                                        }
                                    }
                                    Text(
                                        "To",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                    TxMono(parsed.t.to)
                                    Text(
                                        "Data",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                    if (parsed.t.data.isEmpty()) Text(
                                        "No data",
                                        fontSize = 14.sp
                                    ) else TxMono(parsed.t.data)
                                    if (summary != null && !summary.plain) {
                                        Text(
                                            "Decoded",
                                            fontSize = 15.sp,
                                            color = Dim.Mid,
                                            modifier = Modifier.padding(
                                                top = 12.dp,
                                                bottom = 4.dp
                                            )
                                        )
                                        Text(
                                            WcSigner.describe(parsed.t, tokens).first,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                is Parsed.Msg -> {
                                    Text(
                                        "Raw message (hex)",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                    TxMono(Numeric.toHexString(parsed.m.bytes))
                                }

                                is Parsed.Typed -> {
                                    Text(
                                        "Raw typed data",
                                        fontSize = 15.sp,
                                        color = Dim.Mid,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                    TxMono(parsed.t.json)
                                }
                            }
                        }
                    }

                    error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp) }
                }

                // buttons
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (parsed is Parsed.Bad) {
                        OutlineButton("Close", { reject() }, Modifier.weight(1f))
                    } else {
                        OutlineButton("Reject", { reject() }, Modifier.weight(1f))
                        PrimaryButton(
                            if (busy) "Working…" else if (parsed is Parsed.Tx) "Send" else "Sign",
                            { approve() },
                            Modifier
                                .weight(1f)
                                .alpha(if (canApprove || busy) 1f else 0.4f)
                        )
                    }
                }
            }
        }
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

    PlanEditorDialogs(editor)
}
