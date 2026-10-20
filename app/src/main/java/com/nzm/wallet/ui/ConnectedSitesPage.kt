package com.nzm.wallet.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService

@Composable
fun ConnectedSitesPage(activity: FragmentActivity, vm: WalletViewModel, onBack: () -> Unit) {
    val ready by WcService.ready.collectAsState()
    val sessions by WcService.sessions.collectAsState()
    var projectId by remember { mutableStateOf(WcService.projectId(activity)) }
    var idDialog by remember { mutableStateOf(false) }
    var toDisconnect by remember { mutableStateOf<String?>(null) }
    var pasteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ready) { if (ready) WcService.refreshSessions() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp)
    ) {
        PageHeader("Connected sites", onBack)
        Spacer(Modifier.height(20.dp))

        if (projectId == null) {
            Text(
                "To connect to sites, add a free Reown project ID (dashboard.reown.com). " +
                        "It is a public identifier, not a secret. The app must restart once after you add it.",
                fontSize = 15.sp, color = Dim.Mid
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Add project ID", onClick = { idDialog = true })
        } else {
            Text(
                "To connect a site, tap Connect in the bottom bar and scan its QR code. " +
                        "On the same phone, copy the site's link and paste it here.",
                fontSize = 15.sp, color = Dim.Mid
            )
            Spacer(Modifier.height(16.dp))
            OutlineButton("Paste link", icon = Ic.Copy, onClick = {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) pasteError = "Clipboard is empty"
                else { pasteError = null; WcService.pair(text) }
            })
            pasteError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 14.sp, color = Dim.Danger)
            }
            if (!ready) {
                Spacer(Modifier.height(10.dp))
                Text("Starting WalletConnect…", fontSize = 14.sp, color = Dim.Mid)
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Connected sites")
            Spacer(Modifier.height(6.dp))
            if (sessions.isEmpty()) {
                Hairline()
                Spacer(Modifier.height(12.dp))
                Text("No connected sites.", fontSize = 15.sp, color = Dim.Mid)
            }
            sessions.forEach { s ->
                SettingsRow(
                    label = s.metaData?.name?.ifBlank { null } ?: "Unknown site",
                    value = if (vm.wallets.size > 1)
                        vm.wallets.firstOrNull { it.address.equals(WcService.accountOf(s), true) }?.name ?: "Removed wallet"
                    else s.metaData?.url?.removePrefix("https://")?.take(28)
                ) { toDisconnect = s.topic }
            }
            if (sessions.isNotEmpty()) Hairline()

            Spacer(Modifier.height(28.dp))
            SectionLabel("Setup")
            Spacer(Modifier.height(6.dp))
            SettingsRow("Reown project ID", "Set") { idDialog = true }
            Hairline()
        }
    }

    toDisconnect?.let { topic ->
        ConfirmDialog(
            "Disconnect site?", "The site loses access to your address. Nothing onchain changes.",
            "Disconnect",
            onConfirm = { WcService.disconnect(topic); toDisconnect = null },
            onDismiss = { toDisconnect = null }
        )
    }

    if (idDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { idDialog = false },
            title = { Text("Reown project ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Restart the app after saving. Leave empty and save to remove it.")
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Project ID") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    WcService.setProjectId(activity, text)
                    projectId = WcService.projectId(activity)
                    idDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { idDialog = false }) { Text("Cancel") } }
        )
    }
}