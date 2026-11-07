package com.nzm.wallet.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.nzm.wallet.core.WalletCore
import com.nzm.wallet.dbs.WalletRow
import com.nzm.wallet.storage.KeystoreVault
import com.nzm.wallet.storage.SecretType
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.utils.Constants.SHAPE
import com.nzm.wallet.wc.WcService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Disconnects every site whose session account is [address]. */
private fun disconnectSessionsOf(address: String) {
    runCatching {
        WcService.sessions.value
            .filter { s ->
                s.namespaces.values.any { ns ->
                    ns.accounts.any {
                        it.substringAfterLast(':').equals(address, ignoreCase = true)
                    }
                }
            }
            .forEach { WcService.disconnect(it.topic) }
    }
}

@Composable
fun WalletGate(activity: FragmentActivity, vault: Vault, vm: WalletViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
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
                    if (vm.address != null) Toast.makeText(activity, status, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    suspend fun unlock() {
        val id = vault.activeId ?: error("No wallet")
        vault.withCredentials(activity, id) { creds ->
            vm.onUnlocked(
                vault,
                id,
                WalletCore.addressOf(creds)
            )
        }
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

    fun createWallet() {
        status = null
        launchSafe {
            val m = WalletCore.generateMnemonic()
            val bytes = m.toByteArray()
            val id = try {
                vault.save(activity, SecretType.MNEMONIC, bytes)
            } finally {
                bytes.fill(0)
            }
            hasWallet = true
            vm.addWallet(vault, id, WalletCore.addressOf(WalletCore.deriveCredentials(m)))
            vm.addWalletRequest = false
            backup = m
        }
    }

    fun importWallet() {
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
            if (vm.hasAddress(importedAddress)) {
                bytes.fill(0)
                status = "This wallet is already added."
                return@launchSafe
            }
            val id = try {
                vault.save(activity, type, bytes)
            } finally {
                bytes.fill(0)
            }
            hasWallet = true
            vm.addWallet(vault, id, importedAddress)
            vm.addWalletRequest = false
            input = ""
            activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }

    suspend fun deleteWallet(row: WalletRow) {
        // biometric prompt; throws if canceled or failed, so nothing is deleted
        val (_, bytes) = vault.load(activity, row.id)
        bytes.fill(0)
        disconnectSessionsOf(row.address)
        vault.delete(row.id)
        vm.forgetWallet(row.id)
        if (!vault.exists()) {
            hasWallet = false
            backup = null
            status = null
            vm.clearHistory()
            vm.clearSession()
            return
        }
        if (row.id == vm.activeWalletId) {
            val next = vm.wallets.firstOrNull { it.id != row.id && it.address.isNotBlank() }
            if (next != null) vm.switchWallet(vault, next.id) else vm.clearSession()
        }
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
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val t = vm.backgroundedAt
                    if (t != 0L &&
                        vm.address != null &&
                        SystemClock.elapsedRealtime() - t > vm.lockAfterMs &&
                        vault.exists()
                    ) lock()
                    vm.backgroundedAt = 0L
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
            onCreate = ::createWallet,
            onImport = ::importWallet,
            onCancel = null
        )

        keyLost -> KeyLostScreen {
            vault.delete()
            hasWallet = false
            keyLost = false
            vm.clearSession()
            scope.launch { vm.wipeWallets() }
            status = null
        }

        addr != null && phrase != null -> BackupScreen(phrase) { backup = null }
        addr == null -> LockedScreen(status) { launchSafe { unlock() } }
        vm.addWalletRequest -> {
            val cancel = { vm.addWalletRequest = false; input = ""; status = null }
            BackHandler(onBack = cancel)
            OnboardingScreen(
                input = input,
                onInput = { input = it },
                status = status,
                onCreate = ::createWallet,
                onImport = ::importWallet,
                onCancel = cancel
            )
        }

        else -> MainShell(
            activity = activity,
            vault = vault,
            vm = vm,
            address = addr,
            onReveal = {
                launchSafe {
                    val (type, bytes) = vault.load(activity)
                    try {
                        revealed = type to String(bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            },
            onWipe = { confirmWipe = true }
        )
    }

    revealed?.let { (type, secret) -> RevealDialog(type, secret) { revealed = null } }

    vm.deleteWalletRequest?.let { id ->
        val row = vm.wallets.firstOrNull { it.id == id }
        if (row == null) {
            vm.deleteWalletRequest = null
        } else {
            ConfirmDialog(
                title = "Delete ${row.name}?",
                text = "Removes this wallet from this phone and disconnects its sites. " +
                        "Without its written phrase or private key it cannot be recovered.\n\n${row.address}",
                confirmLabel = "Delete",
                onConfirm = {
                    vm.deleteWalletRequest = null
                    launchSafe { deleteWallet(row) }
                },
                onDismiss = { vm.deleteWalletRequest = null }
            )
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Wipe all wallets?",
            text = "This deletes every wallet from this device. Without the written phrases or private keys, they cannot be recovered.",
            confirmLabel = "Wipe",
            onConfirm = {
                confirmWipe = false
                launchSafe {
                    // biometric prompt; throws if canceled or failed, so nothing is deleted
                    val (_, bytes) = vault.load(activity)
                    bytes.fill(0)
                    runCatching { WcService.sessions.value.forEach { WcService.disconnect(it.topic) } }
                    vault.delete()
                    vm.wipeWallets()
                    hasWallet = false
                    backup = null
                    status = null
                    SimKey.set(ctx, null)
                    vm.clearHistory()
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
    onImport: () -> Unit,
    onCancel: (() -> Unit)?
) {
    val adding = onCancel != null
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        ScreenTitle(if (adding) "Add wallet" else "Your wallet")
        Text(
            if (adding) "The new wallet is kept next to your others. Each has its own phrase or key."
            else "Keys are created on this phone and stay on it.",
            fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(40.dp))
        PrimaryButton(if (adding) "Create new wallet" else "Create wallet", onCreate)
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
            shape = SHAPE,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Import", onImport)
        status?.let {
            Text(it, fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 12.dp))
        }

        if (adding) {
            Spacer(Modifier.height(60.dp))
            OutlineButton("Cancel", onCancel)
        }
    }
}

@Composable
private fun BackupScreen(phrase: String, onDone: () -> Unit) {
    val words = phrase.split(" ")
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pair.forEachIndexed { i, w ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${row * 2 + i + 1}",
                            fontSize = 14.sp,
                            color = Dim.Mid,
                            modifier = Modifier.width(28.dp)
                        )
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
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp),
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
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Wallet key lost", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Your biometrics changed or the screen lock was removed, so Android deleted the key that protects your wallets. Reset, then restore each one from its recovery phrase or private key.",
            fontSize = 15.sp, color = Dim.Mid, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(28.dp))
        OutlineButton("Reset wallets on this device", onReset, Modifier.widthIn(max = 320.dp))
    }
}

@Composable
private fun RevealDialog(type: SecretType, secret: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(60_000.milliseconds)
        onClose()
    }

    // Runs on every way out: Hide, the 60 s timer, back press, or the dialog leaving composition.
    DisposableEffect(Unit) {
        onDispose { if (copied) clipboard.clearPrimaryClip() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        // dialogs are separate windows: FLAG_SECURE on the activity does not cover them
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (type == SecretType.MNEMONIC) "Recovery phrase" else "Private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anyone with this can take all funds. Never share it.")
                // tap to copy
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clip = ClipData.newPlainText("secret", secret).apply {
                                // keeps the value out of the clipboard preview (Android 13+)
                                description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                            copied = true
                        }) {
                    Text(secret, color = Dim.Ink)
                }
                Spacer(Modifier.height(8.dp))
                Text("Hides in 60 seconds.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Hide") } }
    )
}
