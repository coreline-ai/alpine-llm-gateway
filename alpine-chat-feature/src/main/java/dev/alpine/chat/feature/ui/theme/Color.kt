package dev.alpine.chat.feature.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object AlpineDesignTokens {
    val Paper = Color(0xFFF4F3ED)
    val Ink = Color(0xFF10120F)
    val Acid = Color(0xFFB9F227)
    val Slate = Color(0xFF31372F)
    val Warning = Color(0xFFFFE5A3)
    // Keep secondary copy visibly subordinate without relying on low opacity.
    // This is intentionally darker than Material's default muted text on the paper surface.
    val Muted = Color(0xFF4E534C)
    val OutlineSoft = Color(0xFFA9ACA3)
    val SurfaceRaised = Color(0xFFFFFEF8)
}

internal val AlpineLightColors = lightColorScheme(
    primary = AlpineDesignTokens.Acid,
    onPrimary = AlpineDesignTokens.Ink,
    primaryContainer = AlpineDesignTokens.Acid,
    onPrimaryContainer = AlpineDesignTokens.Ink,
    secondary = AlpineDesignTokens.Slate,
    onSecondary = AlpineDesignTokens.Paper,
    secondaryContainer = AlpineDesignTokens.SurfaceRaised,
    onSecondaryContainer = AlpineDesignTokens.Ink,
    tertiary = Color(0xFF557A16),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1F5BC),
    onTertiaryContainer = AlpineDesignTokens.Ink,
    background = AlpineDesignTokens.Paper,
    onBackground = AlpineDesignTokens.Ink,
    surface = AlpineDesignTokens.Paper,
    surfaceContainer = Color(0xFFEEECE4),
    surfaceContainerHigh = Color(0xFFE5E3DA),
    surfaceContainerHighest = Color(0xFFDAD8CF),
    onSurface = AlpineDesignTokens.Ink,
    onSurfaceVariant = AlpineDesignTokens.Muted,
    outline = AlpineDesignTokens.Ink,
    outlineVariant = AlpineDesignTokens.OutlineSoft,
    inverseSurface = AlpineDesignTokens.Ink,
    inverseOnSurface = AlpineDesignTokens.Paper,
    inversePrimary = AlpineDesignTokens.Acid,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val AlpineDarkColors = darkColorScheme(
    primary = AlpineDesignTokens.Acid,
    onPrimary = AlpineDesignTokens.Ink,
    primaryContainer = Color(0xFF455E0C),
    onPrimaryContainer = Color(0xFFE8FFAF),
    secondary = Color(0xFFD8DBD3),
    onSecondary = AlpineDesignTokens.Ink,
    secondaryContainer = AlpineDesignTokens.Slate,
    onSecondaryContainer = AlpineDesignTokens.Paper,
    tertiary = Color(0xFFBEEA6D),
    tertiaryContainer = Color(0xFF324D00),
    background = AlpineDesignTokens.Ink,
    onBackground = AlpineDesignTokens.Paper,
    surface = AlpineDesignTokens.Ink,
    surfaceContainer = Color(0xFF1B1E1A),
    surfaceContainerHigh = AlpineDesignTokens.Slate,
    surfaceContainerHighest = Color(0xFF3C423A),
    onSurface = AlpineDesignTokens.Paper,
    onSurfaceVariant = Color(0xFFC5C8C0),
    outline = Color(0xFFE4E7DE),
    outlineVariant = Color(0xFF5B6058),
    inverseSurface = AlpineDesignTokens.Paper,
    inverseOnSurface = AlpineDesignTokens.Ink,
    inversePrimary = Color(0xFF557A16),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

data class AlpineStatusColors(
    val connected: Color,
    val onConnected: Color,
    val warning: Color,
    val onWarning: Color,
)

internal val LightStatusColors = AlpineStatusColors(
    connected = AlpineDesignTokens.Acid,
    onConnected = AlpineDesignTokens.Ink,
    warning = AlpineDesignTokens.Warning,
    onWarning = AlpineDesignTokens.Ink,
)

internal val DarkStatusColors = AlpineStatusColors(
    connected = Color(0xFF455E0C),
    onConnected = Color(0xFFE8FFAF),
    warning = Color(0xFF5A4300),
    onWarning = AlpineDesignTokens.Warning,
)
