package com.nzm.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.chain.Fee
import com.nzm.wallet.chain.TxPlan
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.BigInteger

/** Shared by the WalletConnect request page and the Send review page. */
internal val TxOk = Color(0xFF4CC38A)
private val DEFAULT_NAME = Regex("Wallet \\d+")

internal class Party(val main: String, val sub: String?)

/** ENS name, else your own name for it (wallet or contact), else short address. */
@Composable
internal fun rememberParty(vm: WalletViewModel, address: String): Party {
    val rpc = vm.ensRpc
    val cached = if (address.equals(vm.address, ignoreCase = true)) vm.ensName else null
    val looked by produceState<String?>(null, address, rpc) {
        value = if (rpc == null) null else try {
            Ens.reverse(rpc, address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val own = vm.wallets.firstOrNull { it.address.equals(address, true) }?.name
        ?.takeIf { !DEFAULT_NAME.matches(it) }
    val contact = vm.contacts.firstOrNull { it.address.equals(address, true) }?.name
    val short = shortAddress(address)
    val main = cached ?: looked ?: own ?: contact ?: short
    return Party(main, if (main == short) null else short)
}

@Composable
internal fun TxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(modifier)
            .background(Dim.Raised)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
internal fun TxPartyRow(label: String, p: Party) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(label, fontSize = 14.sp, color = Dim.Mid)
        Text(
            p.main,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        p.sub?.let { Text(it, fontSize = 13.sp, color = Dim.Mid) }
    }
}

@Composable
internal fun TxLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Dim.MenuLine)
    )
}

@Composable
internal fun TxMono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Dim.Ink)
}

internal fun gwei(w: BigInteger) =
    BigDecimal(w).movePointLeft(9).stripTrailingZeros().toPlainString()

internal fun weiOf(gweiText: String): BigInteger? =
    gweiText.trim().replace(',', '.').toBigDecimalOrNull()
        ?.movePointRight(9)?.toBigInteger()?.takeIf { it.signum() > 0 }

@Composable
internal fun TxFeeDialog(plan: TxPlan, base: TxPlan, onSave: (Fee) -> Unit, onDismiss: () -> Unit) {
    val f = plan.fee
    var max by remember {
        mutableStateOf(
            when (f) {
                is Fee.Eip1559 -> gwei(f.maxFeePerGas)
                is Fee.Legacy -> gwei(f.gasPrice)
            }
        )
    }
    var tip by remember {
        mutableStateOf((f as? Fee.Eip1559)?.let { gwei(it.maxPriorityFeePerGas) } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network fee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "In gwei. Max fee is the most you will pay per gas. Lower values may be slow or rejected.",
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    max, { max = it }, singleLine = true, keyboardOptions = numeric,
                    label = { Text(if (f is Fee.Eip1559) "Max fee per gas" else "Gas price") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)
                )
                if (f is Fee.Eip1559) {
                    OutlinedTextField(
                        tip,
                        { tip = it },
                        singleLine = true,
                        keyboardOptions = numeric,
                        label = { Text("Priority fee") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
                error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = weiOf(max)
                if (m == null) {
                    error = "Enter a valid number above 0"; return@TextButton
                }
                if (f is Fee.Eip1559) {
                    val t = weiOf(tip)
                    when {
                        t == null -> error = "Enter a valid priority fee"
                        t > m -> error = "Priority fee cannot be above max fee"
                        else -> onSave(Fee.Eip1559(m, t))
                    }
                } else {
                    onSave(Fee.Legacy(m))
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (plan.fee != base.fee) TextButton(onClick = { onSave(base.fee) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
internal fun TxNonceDialog(
    current: BigInteger,
    onSave: (BigInteger) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nonce") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Order number of this transaction for your wallet. Change it only to replace a stuck transaction.",
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)
                )
                error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.toBigIntegerOrNull()
                if (n == null) error = "Enter a number" else onSave(n)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun TxGasDialog(current: BigInteger, base: BigInteger, onSave: (BigInteger) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gas limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Most gas the transaction may use. Estimated: $base. Too low and the transaction fails and still costs a fee.",
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit) }, singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                val n = text.toBigIntegerOrNull()
                if (n != null && n < base) {
                    Text("Below the estimate. It will probably fail.", color = Dim.Danger, fontSize = 14.sp)
                }
                error?.let { Text(it, color = Dim.Danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.toBigIntegerOrNull()
                when {
                    n == null -> error = "Enter a number"
                    n < BigInteger.valueOf(21_000) -> error = "Minimum is 21000"
                    else -> onSave(n)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != base) TextButton(onClick = { onSave(base) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
