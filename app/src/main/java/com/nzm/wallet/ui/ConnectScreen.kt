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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService

@Composable
fun ConnectScreen(activity: FragmentActivity) {
    val ready by WcService.ready.collectAsState()
    val sessions by WcService.sessions.collectAsState()
    val status by WcService.status.collectAsState()
    var projectId by remember { mutableStateOf(WcService.projectId(activity)) }
    var idDialog by remember { mutableStateOf(false) }
    var toDisconnect by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ready) { if (ready) WcService.refreshSessions() }
    LaunchedEffect(Unit) { WcService.clearStatus() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp)
    ) {
        ScreenTitle("Connect")
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
            PrimaryButton("Scan QR", icon = Ic.Scan, enabled = ready, onClick = {
                scanError = null
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(activity, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let { WcService.pair(it) } }
                    .addOnFailureListener { scanError = it.message ?: "Scan failed" }
            })
            Spacer(Modifier.height(12.dp))
            OutlineButton("Paste link", icon = Ic.Copy, onClick = {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) scanError = "Clipboard is empty" else WcService.pair(text)
            })
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text("Starting WalletConnect…", fontSize = 14.sp, color = Dim.Mid)
            }
            (scanError ?: status)?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 14.sp, color = Dim.Danger)
            }

            Spacer(Modifier.height(32.dp))
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
                    value = s.metaData?.url?.removePrefix("https://")?.take(28)
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
            "Disconnect site?", "The site loses access to your address. Nothing on-chain changes.",
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
