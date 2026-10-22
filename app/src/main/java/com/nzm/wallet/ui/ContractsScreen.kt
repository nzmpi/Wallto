package com.nzm.wallet.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.storage.SavedContract
import com.nzm.wallet.storage.SavedContracts
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim

private class CallSession(val saved: SavedContract?)

@SuppressLint("RememberReturnType")
@Composable
fun ContractsScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    onChainMenu: () -> Unit
) {
    remember { SavedContracts.load(activity) }
    var session by remember { mutableStateOf<CallSession?>(null) }
    var toRemove by remember { mutableStateOf<SavedContract?>(null) }
    val chain = vm.selected
    val saved = SavedContracts.items

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp)
    ) {
        TabHeader("Contracts", chain, onChainMenu)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("New call", onClick = { session = CallSession(null) }, icon = Ic.Plus, enabled = chain != null)
        Spacer(Modifier.height(36.dp))
        Text("Saved contracts", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (saved.isEmpty()) {
            Text(
                "Save a contract from the call screen. It stays here with its ABI.",
                fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Spacer(Modifier.height(8.dp))
            saved.forEach { c ->
                val cName = vm.chains.firstOrNull { it.chainId == c.chainId }?.name ?: "Chain ${c.chainId}"
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (vm.selected?.chainId != c.chainId) vm.selectChain(c.chainId)
                        session = CallSession(c)
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Dim.Avatar),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Code, null, Modifier.size(20.dp), tint = Dim.Mid) }
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            shortAddress(c.address) + " · " + cName + if (c.abi != null) " · ABI" else "",
                            fontSize = 13.sp, color = Dim.Mid, maxLines = 1
                        )
                    }
                    IconButton(onClick = { toRemove = c }) {
                        Icon(Ic.Trash, "Remove", Modifier.size(20.dp), tint = Dim.Mid)
                    }
                }
            }
            Hairline()
        }
    }

    toRemove?.let { c ->
        ConfirmDialog(
            title = "Remove ${c.name}?",
            text = "Only removes it from this list on this phone.",
            confirmLabel = "Remove",
            onConfirm = { SavedContracts.remove(c.id); toRemove = null },
            onDismiss = { toRemove = null }
        )
    }

    val s = session
    // a saved contract on another chain opens once the chain switch has happened
    if (s != null && chain != null && (s.saved == null || s.saved.chainId == chain.chainId)) {
        key(s) {
            ContractDialog(activity, vault, vm, chain, address, s.saved) { sent ->
                session = null
                if (sent) vm.refresh()
            }
        }
    }
}
