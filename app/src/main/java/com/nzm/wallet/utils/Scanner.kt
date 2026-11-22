package com.nzm.wallet.utils

import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.utils.Helpers.isValidAddress

sealed interface ScanResult {
    class WalletConnect(val uri: String) : ScanResult
    class Address(val value: String) : ScanResult
    class Unsupported(val why: String) : ScanResult
    object Unknown : ScanResult
}

fun parseScan(raw: String): ScanResult {
    val s = raw.trim()
    if (s.startsWith("wc:", ignoreCase = true)) return ScanResult.WalletConnect(s)

    // plain address, or EIP-681 payment link: ethereum:<target>[@chain][/function][?params]
    var body = s
    if (body.startsWith("ethereum:", ignoreCase = true)) {
        body = body.substring("ethereum:".length).removePrefix("pay-")
    }
    val target = body.takeWhile { it != '@' && it != '/' && it != '?' }
    // Send uses the selected network and coin: a link that names a chain or a token call
    // would silently become a different payment, so refuse it instead
    val head = body.substringBefore('?')
    if ('@' in head || '/' in head) return ScanResult.Unsupported(
        "This payment link names a network or token. Paste the recipient address and choose them yourself."
    )

    return if (isValidAddress(target) || Ens.looksLikeName(target)) {
        ScanResult.Address(target)
    } else {
        ScanResult.Unknown
    }
}

/** Opens Google's QR scanner. No camera permission needed. Cancelling does nothing. */
fun scanQr(activity: FragmentActivity, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options =
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(activity, options).startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(onResult) }
        .addOnFailureListener { onError(it.message ?: "Scan failed") }
}
