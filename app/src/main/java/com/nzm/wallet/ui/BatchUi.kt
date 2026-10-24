package com.nzm.wallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nzm.wallet.chain.Chain
import kotlinx.coroutines.CancellationException

@Composable
fun batchLineText(vm: WalletViewModel, b: BatchItem): String {
    val who = b.name ?: rememberParty(vm, b.recipient ?: b.to).main
    return "${b.line} ${b.link} $who"
}

/** True when [account] is delegated on [chain] to a contract that runs ERC-7821 batches. */
@Composable
fun rememberBatchSupport(chain: Chain?, account: String): Boolean {
    var ok by remember(chain?.chainId, account) { mutableStateOf(false) }
    LaunchedEffect(chain?.chainId, account) {
        ok = false
        if (chain == null) return@LaunchedEffect
        ok = try {
            Batch.status(chain, account).supportsBatch
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    return ok
}
