package com.nzm.wallet.ui

import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nzm.wallet.crypto.WalletCore
import com.nzm.wallet.storage.KeystoreVault
import com.nzm.wallet.storage.SecretType
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun WalletGate(activity: FragmentActivity, vault: Vault, vm: WalletViewModel) {
    val scope = rememberCoroutineScope()
    var hasWallet by remember { mutableStateOf(vault.exists()) }
    var keyLost by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<Pair<SecretType, String>?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var autoTried by rememberSaveable { mutableStateOf(false) }
    var unlockTick by remember { mutableIntStateOf(0) }

    fun launchSafe(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (KeystoreVault.isKeyGone(e)) {
                    keyLost = true
                } else {
                    status = e.message ?: "Something went wrong"
                    if (vm.address != null) Toast.makeText(activity, status, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    suspend fun unlock() {
        vault.withCredentials(activity) { creds -> vm.address = WalletCore.addressOf(creds) }
        status = null
    }

    fun lock() {
        vm.clearSession()
        backup = null
        revealed = null
        confirmWipe = false
        status = null
        unlockTick++
    }

    // auto unlock: once per app launch, and again after each lock
    LaunchedEffect(unlockTick) {
        if (hasWallet && vm.address == null && !keyLost) {
            if (unlockTick == 0 && autoTried) return@LaunchedEffect
            autoTried = true
            launchSafe { unlock() }
        }
    }

    // lock after the configured time in background
    DisposableEffect(activity) {
        var stoppedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (stoppedAt != 0L &&
                        SystemClock.elapsedRealtime() - stoppedAt > vm.lockAfterMs &&
                        vault.exists()
                    ) lock()
                    stoppedAt = 0L
                }
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    val addr = vm.address
    val phrase = backup
    when {
        !hasWallet -> OnboardingScreen(
            input = input,
            onInput = { input = it },
            status = status,
            onCreate = {
                status = null
                launchSafe {
                    val m = WalletCore.generateMnemonic()
                    val bytes = m.toByteArray()
                    try { vault.save(activity, SecretType.MNEMONIC, bytes) } finally { bytes.fill(0) }
                    hasWallet = true
                    vm.address = WalletCore.addressOf(WalletCore.deriveCredentials(m))
                    backup = m
                }
            },
            onImport = {
                status = null
                launchSafe {
                    val pk = WalletCore.normalizePrivateKey(input)
                    val (type, bytes, importedAddress) = when {
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
                        else -> {
                            status = "That is not a valid recovery phrase or private key."
                            return@launchSafe
                        }
                    }
                    try { vault.save(activity, type, bytes) } finally { bytes.fill(0) }
                    hasWallet = true
                    vm.address = importedAddress
                    input = ""
                    activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                }
            }
        )
        keyLost -> KeyLostScreen {
            vault.delete()
            hasWallet = false
            keyLost = false
            vm.clearSession()
            status = null
        }
        addr != null && phrase != null -> BackupScreen(phrase) { backup = null }
        addr == null -> LockedScreen(status) { launchSafe { unlock() } }
        else -> MainShell(
            activity = activity,
            vault = vault,
            vm = vm,
            address = addr,
            onReveal = {
                launchSafe {
                    val (type, bytes) = vault.load(activity)
                    try { revealed = type to String(bytes) } finally { bytes.fill(0) }
                }
            },
            onWipe = { confirmWipe = true }
        )
    }

    revealed?.let { (type, secret) -> RevealDialog(type, secret) { revealed = null } }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Wipe wallet?",
            text = "This deletes the wallet from this device. Without your written phrase or private key, it cannot be recovered.",
            confirmLabel = "Wipe",
            onConfirm = {
                confirmWipe = false
                launchSafe {
                    // biometric prompt; throws if cancelled or failed, so nothing is deleted
                    val (_, bytes) = vault.load(activity)
                    bytes.fill(0)
                    runCatching { WcService.sessions.value.forEach { WcService.disconnect(it.topic) } }
                    vault.delete()
                    hasWallet = false
                    backup = null
                    status = null
                    vm.clearSession()
                }
            },
            onDismiss = { confirmWipe = false }
        )
    }
}

@Composable
private fun OnboardingScreen(
    input: String,
    onInput: (String) -> Unit,
    status: String?,
    onCreate: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        ScreenTitle("Your wallet")
        Text(
            "Keys are created on this phone and stay on it.",
            fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(40.dp))
        PrimaryButton("Create wallet", onCreate)
        Spacer(Modifier.height(36.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))
        Text("Import an existing wallet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            label = { Text("Recovery phrase or private key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton("Import", onImport)
        status?.let {
            Text(it, fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun BackupScreen(phrase: String, onDone: () -> Unit) {
    val words = phrase.split(" ")
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        ScreenTitle("Write down your recovery phrase")
        Text(
            "Anyone with these words can take your funds. Keep them offline. You can show them again in Settings.",
            fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(28.dp))
        words.chunked(2).forEachIndexed { row, pair ->
            Hairline()
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                pair.forEachIndexed { i, w ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("${row * 2 + i + 1}", fontSize = 14.sp, color = Dim.Mid, modifier = Modifier.width(28.dp))
                        Text(w, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Hairline()
        Spacer(Modifier.height(32.dp))
        PrimaryButton("I wrote it down", onDone)
    }
}

@Composable
private fun LockedScreen(status: String?, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Ic.Lock, null, Modifier.size(40.dp), tint = Dim.Mid)
        Spacer(Modifier.height(16.dp))
        Text("Wallet locked", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        status?.let {
            Text(
                it, fontSize = 14.sp, color = Dim.Mid, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Unlock", onUnlock, Modifier.widthIn(max = 280.dp))
    }
}

@Composable
private fun KeyLostScreen(onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Wallet key lost", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Your biometrics changed or the screen lock was removed, so Android deleted the key that protects this wallet. Reset it, then restore from your recovery phrase or private key.",
            fontSize = 15.sp, color = Dim.Mid, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(28.dp))
        OutlineButton("Reset wallet on this device", onReset, Modifier.widthIn(max = 320.dp))
    }
}

@Composable
private fun RevealDialog(type: SecretType, secret: String, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(60_000.milliseconds)
        onClose()
    }
    AlertDialog(
        onDismissRequest = onClose,
        // dialogs are separate windows: FLAG_SECURE on the activity does not cover them
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (type == SecretType.MNEMONIC) "Recovery phrase" else "Private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anyone with this can take all funds. Never share it.")
                Text(secret, color = Dim.Ink)
                Text("Hides in 60 seconds.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Hide") } }
    )
}
