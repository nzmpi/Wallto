package com.nzm.wallet.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.nzm.wallet.R

object Dim {
    val Bg = Color(0xFF272A31)
    val Nav = Color(0xFF2C2F36)
    val Raised = Color(0xFF31353D)
    val RaisedSelected = Color(0xFF3B404A)
    val Avatar = Color(0xFF363A43)
    val Line = Color(0xFF3A3E47)
    val Outline = Color(0xFF3E434C)
    val MenuLine = Color(0xFF474C56)
    val Ink = Color(0xFFE8E9EC)
    val Mid = Color(0xFF9A9FA9)
    val Accent = Color(0xFF8FA8FF)
    val OnAccent = Color(0xFF14171C)
    val Danger = Color(0xFFFF9A8F)
    val Scrim = Color(0x940E1014)
}

private fun grotesk(weight: FontWeight) = Font(
    resId = R.font.schibsted_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

private val Grotesk = FontFamily(
    grotesk(FontWeight.Normal),
    grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold),
    grotesk(FontWeight.Bold)
)

private val Scheme = darkColorScheme(
    primary = Dim.Accent,
    onPrimary = Dim.OnAccent,
    background = Dim.Bg,
    onBackground = Dim.Ink,
    surface = Dim.Bg,
    onSurface = Dim.Ink,
    surfaceVariant = Dim.Raised,
    onSurfaceVariant = Dim.Mid,
    surfaceContainer = Dim.Raised,
    surfaceContainerHigh = Dim.Raised,
    surfaceContainerHighest = Dim.RaisedSelected,
    outline = Dim.Outline,
    outlineVariant = Dim.Line,
    error = Dim.Danger,
    onError = Dim.OnAccent
)

private fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography().withFont(Grotesk)) {
        Surface(Modifier.fillMaxSize(), color = Dim.Bg, contentColor = Dim.Ink, content = content)
    }
}