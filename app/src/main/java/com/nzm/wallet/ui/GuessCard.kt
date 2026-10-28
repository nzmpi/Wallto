package com.nzm.wallet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nzm.wallet.abi.SelectorLookup
import com.nzm.wallet.ui.theme.Dim

@Composable
fun GuessInfo(g: SelectorLookup.Guess) {
    Column {
        Text("Looks like", fontSize = 13.sp, color = Dim.Mid)
        Text(g.signature.substringBefore('(') + "()", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        g.risks.forEach {
            Text("WARNING: $it", fontSize = 13.sp, color = Dim.Danger, modifier = Modifier.padding(top = 4.dp))
        }
        val args = g.args
        if (args == null) {
            Text(
                "Arguments could not be decoded. Check the call data under Advanced details.",
                fontSize = 13.sp, color = Dim.Mid, modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            args.take(8).forEach { (type, value) ->
                Text(type, fontSize = 12.sp, color = Dim.Mid, modifier = Modifier.padding(top = 6.dp))
                Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            if (args.size > 8) Text("+ ${args.size - 8} more", fontSize = 12.sp, color = Dim.Mid)
        }
        Text(
            "Name from a public database. Anyone can add names, so treat it as a hint, not proof." +
                    if (g.others > 0) " ${g.others} other name${if (g.others == 1) "" else "s"} share this selector." else "",
            fontSize = 12.sp, color = Dim.Mid, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun GuessCard(g: SelectorLookup.Guess) = TxCard { GuessInfo(g) }
