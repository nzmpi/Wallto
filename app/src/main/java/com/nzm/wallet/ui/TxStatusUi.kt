package com.nzm.wallet.ui

import android.content.Intent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.SendTx
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.ui.theme.Dim
import kotlinx.coroutines.CancellationException

@Composable
internal fun rememberTxResult(chain: Chain, hash: String?): String {
    val result by produceState("Pending...", chain.rpcUrl, hash) {
        val h = hash ?: return@produceState
        value = try {
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
    return result
}

@Composable
internal fun TxStatusCard(result: String, hash: String) {
    TxCard {
        TxRow(
            "Status", result,
            when {
                result == "Confirmed" -> TxOk
                result.startsWith("FAILED") -> Dim.Danger
                else -> Dim.Ink
            }
        )
        TxLine()
        TxLabel("Transaction hash")
        TxMono(hash)
        Spacer(Modifier.height(4.dp))
    }
}

internal fun openTx(activity: FragmentActivity, chain: Chain, hash: String) {
    val base = chain.explorerUrl ?: return
    activity.startActivity(Intent(Intent.ACTION_VIEW, "$base/tx/$hash".toUri()))
}

@Composable
internal fun RowScope.TxSentButtons(
    activity: FragmentActivity,
    chain: Chain,
    hash: String,
    onDone: () -> Unit
) {
    if (chain.explorerUrl != null) {
        OutlineButton("Explorer", { openTx(activity, chain, hash) }, Modifier.weight(1f))
    }
    PrimaryButton("Done", onDone, Modifier.weight(1f))
}
