package com.nzm.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    )
    val bmp = createBitmap(m.width, m.height)
    for (x in 0 until m.width) for (y in 0 until m.height) {
        bmp[x, y] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return bmp.asImageBitmap()
}

@Composable
fun QrDialog(name: String, address: String, onDismiss: () -> Unit) {
    val qr = remember(address) { qrBitmap(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp)
                ) {
                    Image(
                        qr, "QR code of $address",
                        Modifier.size(220.dp), filterQuality = FilterQuality.None
                    )
                }
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(
                        address, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
