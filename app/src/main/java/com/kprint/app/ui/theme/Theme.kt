package com.kprint.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KPrintGreen = Color(0xFF16A66A)
val KPrintGreenDark = Color(0xFF08794A)
val KPrintNavy = Color(0xFF142B35)
val KPrintAmber = Color(0xFFFFB547)
val KPrintBackground = Color(0xFFF4F7F6)
val KPrintError = Color(0xFFBA1A1A)

private val LightColors = lightColorScheme(
    primary = KPrintGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F5E2),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    background = KPrintBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFE5EAE7),
    onSurface = KPrintNavy,
    error = KPrintError,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DDB9F),
    primaryContainer = Color(0xFF005233),
    secondary = Color(0xFFB4CCBD),
    background = Color(0xFF0D1512),
    surface = Color(0xFF14201B),
    onSurface = Color(0xFFE2E9E4),
)

@Composable
fun KPrintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
