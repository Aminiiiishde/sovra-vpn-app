package com.sovra.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NeonBlue,
    onPrimary = CyberDark,
    primaryContainer = CyberCard,
    secondary = ElectricCyan,
    background = CyberDark,
    surface = CyberSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyan,
    background = Color(0xFFF4F7FB),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF101828),
    onSurface = Color(0xFF101828)
)

@Composable
fun SovraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
