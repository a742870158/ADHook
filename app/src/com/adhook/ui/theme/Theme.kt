package com.adhook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueContainer,
    onPrimaryContainer = OnPrimaryBlueContainer,
    secondary = EmeraldGreen,
    onSecondary = Color.White,
    secondaryContainer = EmeraldGreenContainer,
    onSecondaryContainer = OnEmeraldGreen,
    background = BackgroundLight,
    surface = SurfaceLight,
    onBackground = DarkGrayText,
    onSurface = DarkGrayText,
    surfaceVariant = OutlineVariantLight,
    onSurfaceVariant = LightGraySubtext,
    outline = OutlineLight
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF1E3A8A),
    primaryContainer = Color(0xFF1E40AF),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF064E3B),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFF9FAFB),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF374151)
)

@Composable
fun ADHookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
