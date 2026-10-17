package com.nzm.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim

@Composable
fun ComingSoonScreen(title: String, message: String, vm: WalletViewModel, onChainMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        TabHeader(title, vm.selected?.name, onChainMenu)
        Spacer(Modifier.height(32.dp))
        Text(message, fontSize = 15.sp, color = Dim.Mid)
    }
}

@Composable
fun ContractsScreen(
    activity: FragmentActivity,
    vault: Vault,
    vm: WalletViewModel,
    address: String,
    onChainMenu: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val chain = vm.selected
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        TabHeader("Contracts", chain?.name, onChainMenu)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("New call", onClick = { open = true }, icon = Ic.Plus, enabled = chain != null)
        Spacer(Modifier.height(36.dp))
        Text("Saved contracts", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Contracts you save will be listed here.",
            fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
        )
    }
    if (open && chain != null) {
        ContractDialog(activity, vault, chain, address) { sent ->
            open = false
            if (sent) vm.refresh()
        }
    }
}
