package com.nzm.wallet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.crypto.WalletCore
import com.nzm.wallet.storage.SecretType
import com.nzm.wallet.storage.Vault
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nzm.wallet.chain.ChainRepository
import com.nzm.wallet.storage.KeystoreVault
import com.nzm.wallet.ui.ChainSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // blocks screenshots, screen recording, recents thumbnail
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val vault = Vault(applicationContext)
        setContent { MaterialTheme { WalletTestScreen(this, vault) } }
    }
}

private const val LOCK_AFTER_MS = 60_000L

@Composable
fun RevealDialog(type: SecretType, secret: String, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(60_000.milliseconds)
        onClose()
    }
    AlertDialog(
        onDismissRequest = onClose,
        // dialogs are separate windows: FLAG_SECURE on the activity does NOT cover them
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (type == SecretType.MNEMONIC) "Recovery phrase" else "Private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anyone with this can take all funds. Never share it. No real support will ask for it.")
                Text(secret)
                Text("Hides in 60 seconds.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Hide") } }
    )
}

@Composable
fun WalletTestScreen(activity: FragmentActivity, vault: Vault) {
    val scope = rememberCoroutineScope()
    val appCtx = activity.applicationContext
    val repo = remember { ChainRepository(appCtx) }
    val prefs = remember { appCtx.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE) }

    var hasWallet by remember { mutableStateOf(vault.exists()) }
    var keyLost by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (hasWallet) "Locked" else "No wallet") }
    var address by rememberSaveable { mutableStateOf("") }
    var autoTried by rememberSaveable { mutableStateOf(false) }
    var backup by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<Pair<SecretType, String>?>(null) }
    var unlockTick by remember { mutableIntStateOf(0) }

    suspend fun unlock() {
        vault.withCredentials(activity) { creds -> address = WalletCore.addressOf(creds) }
        status = "Unlocked"
    }

    fun launchSafe(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (KeystoreVault.isKeyGone(e)) {
                    keyLost = true
                    status = "Wallet key lost: biometrics changed or screen lock removed. " +
                            "Reset, then restore from your phrase or private key."
                } else {
                    status = "Error: ${e.message}"
                }
            }
        }
    }

    // auto unlock: once per app launch, and again after a lock
    LaunchedEffect(unlockTick) {
        if (hasWallet && address.isEmpty() && !keyLost) {
            if (unlockTick == 0 && autoTried) return@LaunchedEffect
            autoTried = true
            launchSafe { unlock() }
        }
    }

    // lock after LOCK_AFTER_MS in background
    DisposableEffect(activity) {
        var stoppedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (stoppedAt != 0L &&
                        SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS &&
                        vault.exists()
                    ) {
                        backup = null
                        revealed = null
                        input = ""
                        confirmWipe = false
                        address = ""
                        status = "Locked"
                        unlockTick++
                    }
                    stoppedAt = 0L
                }

                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).systemBarsPadding().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Status: $status")

        if (keyLost) {
            Button(onClick = {
                vault.delete()
                hasWallet = false
                keyLost = false
                address = ""
                status = "No wallet. Create or import."
            }) { Text("Reset wallet on this device") }
        }

        if (address.isNotEmpty()) {
            Text("Address: $address")
            Button(onClick = {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("address", address))
                status = "Address copied"
            }) { Text("Copy address") }
            ChainSection(address, repo, prefs, activity, vault)
        }

        backup?.let {
            Text("WRITE THIS DOWN. You can show it again later with Reveal.")
            Text(it)
            Button(onClick = { backup = null }) { Text("I saved it") }
        }

        if (!hasWallet) {
            Button(onClick = {
                launchSafe {
                    val m = WalletCore.generateMnemonic()
                    val bytes = m.toByteArray()
                    try { vault.save(activity, SecretType.MNEMONIC, bytes) } finally { bytes.fill(0) }
                    hasWallet = true
                    address = WalletCore.addressOf(WalletCore.deriveCredentials(m))
                    backup = m
                    status = "Created"
                }
            }) { Text("Create wallet") }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Mnemonic or private key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                launchSafe {
                    val pk = WalletCore.normalizePrivateKey(input)
                    val (type, bytes, addr) = when {
                        WalletCore.isValidMnemonic(input) -> Triple(
                            SecretType.MNEMONIC,
                            WalletCore.normalize(input).toByteArray(),
                            WalletCore.addressOf(WalletCore.deriveCredentials(input))
                        )
                        pk != null -> Triple(
                            SecretType.PRIVATE_KEY,
                            pk.toByteArray(),
                            WalletCore.addressOf(WalletCore.credentialsFromPrivateKey(pk))
                        )
                        else -> { status = "Invalid mnemonic or key"; return@launchSafe }
                    }
                    try { vault.save(activity, type, bytes) } finally { bytes.fill(0) }
                    hasWallet = true
                    address = addr
                    input = ""
                    activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                    status = "Imported"
                }
            }) { Text("Import") }
        } else if (!keyLost) {
            if (address.isEmpty()) {
                Button(onClick = { launchSafe { unlock() } }) { Text("Unlock") }
            } else {
                OutlinedButton(onClick = {
                    launchSafe {
                        val (type, bytes) = vault.load(activity)
                        try { revealed = type to String(bytes) } finally { bytes.fill(0) }
                    }
                }) { Text("Reveal recovery secret") }
            }
            OutlinedButton(onClick = { confirmWipe = true }) { Text("Wipe wallet") }
        }
    }

    revealed?.let { (type, secret) -> RevealDialog(type, secret) { revealed = null } }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe wallet?") },
            text = {
                Text(
                    "This deletes the wallet from this device. " +
                            "Without your written phrase or private key, it cannot be recovered."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vault.delete()
                    hasWallet = false
                    address = ""
                    backup = null
                    status = "No wallet"
                    confirmWipe = false
                }) { Text("Wipe") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
        )
    }
}