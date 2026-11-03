package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.chain.Erc20
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Helpers.fmt
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

@Composable
fun TokenInfoDialog(
    activity: FragmentActivity,
    chain: Chain,
    token: Token,
    owner: String,
    onSend: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    var raw by remember { mutableStateOf<BigInteger?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(token.address) {
        try {
            raw = Erc20.balanceOf(chain.rpcUrl, token.address, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            balanceError = e.message ?: "error"
        }
    }

    TxPage(
        onDismiss = onClose,
        bottom = {
            OutlineButton("Close", onClose, Modifier.weight(1f))
            if (onSend != null) PrimaryButton("Send", onSend, Modifier.weight(1f))
        }
    ) {
        PageHeader(token.symbol, onClose)

        val r = raw
        when {
            r != null -> TxAmount(
                "Balance",
                fmt(r, token, token.decimals)
            )

            balanceError != null -> {
                TxAmount("Balance", "Unavailable")
                Text(balanceError ?: "", fontSize = 14.sp, color = Dim.Danger)
            }

            else -> TxAmount("Balance", "Loading…", color = Dim.Mid)
        }

        TxCard {
            TxRow("Name", token.name)
            TxLine()
            TxRow("Symbol", token.symbol)
            TxLine()
            TxRow("Decimals", token.decimals.toString())
        }

        TxCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Network", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
                Text(chain.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (chain.isTestnet) Text("  testnet", fontSize = 13.sp, color = Dim.Mid)
            }
        }

        // tap to copy
        TxCard(Modifier.clickable {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("token", token.address))
            copied = true
        }) {
            Row(Modifier.fillMaxWidth()) {
                Text("Contract", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
                Text(
                    if (copied) "Copied" else "Tap to copy",
                    fontSize = 12.sp,
                    color = if (copied) Dim.Accent else Dim.Mid
                )
            }
            Spacer(Modifier.height(6.dp))
            TxMono(token.address)
        }

        TxAdvanced {
            TxRow("Chain ID", token.chainId.toString())
            val r2 = raw
            if (r2 != null) TxRow("Raw units", r2.toString())
        }

        if (chain.explorerUrl != null) {
            OutlineButton("View on explorer", {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "${chain.explorerUrl}/address/${token.address}".toUri()
                    )
                )
            })
        }
    }
}
