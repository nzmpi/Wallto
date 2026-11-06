package com.nzm.wallet.ui

import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nzm.wallet.chain.Ens
import com.nzm.wallet.utils.Helpers.isValidAddress

sealed interface ScanResult {
    class WalletConnect(val uri: String) : ScanResult
    class Address(val value: String) : ScanResult
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
    val path = body.substringAfter('/', "").substringBefore('?')
    val query = body.substringAfter('?', "")
    val recipient =
        if (path == "transfer") {
            query.split('&').firstOrNull { it.startsWith("address=") }?.substringAfter('=')
                ?: target
        } else target

    return if (isValidAddress(recipient) || Ens.looksLikeName(recipient)) {
        ScanResult.Address(recipient)
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
