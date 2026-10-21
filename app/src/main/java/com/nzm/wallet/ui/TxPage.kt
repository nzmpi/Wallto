package com.nzm.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.nzm.wallet.ui.theme.Dim

@Composable
internal fun TxPage(
    onDismiss: () -> Unit,
    bottom: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Dim.Bg, contentColor = Dim.Ink) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    content = bottom
                )
            }
        }
    }
}

/** Big amount block: small label, large value, optional fiat. */
@Composable
internal fun TxAmount(label: String, amount: String, fiat: String? = null, color: Color = Dim.Ink) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(label, fontSize = 15.sp, color = Dim.Mid)
        Text(amount, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = color)
        if (fiat != null) Text(fiat, fontSize = 16.sp, color = Dim.Mid)
    }
}

/** One label / value line inside a card. */
@Composable
internal fun TxRow(label: String, value: String, valueColor: Color = Dim.Ink) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(end = 16.dp))
        Text(
            value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = valueColor,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/** Small gray label above a value inside a card. */
@Composable
internal fun TxLabel(text: String, top: Int = 8) {
    Text(text, fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.padding(top = top.dp, bottom = 6.dp))
}

/** Collapsible "Advanced details" card. */
@Composable
internal fun TxAdvanced(content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    TxCard(Modifier.clickable { open = !open }) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced details", fontSize = 15.sp, color = Dim.Mid, modifier = Modifier.weight(1f))
            Icon(Ic.ChevronDown, null, Modifier.size(18.dp).rotate(if (open) 180f else 0f), tint = Dim.Mid)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
