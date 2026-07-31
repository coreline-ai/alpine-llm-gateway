package dev.alpine.llm.demo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val AlpineBlue = Color(0xFF2855D9)
internal val AlpineBlueDark = Color(0xFFB8C4FF)
internal val AlpineBlueContainer = Color(0xFFDDE2FF)
internal val AlpineBlueContainerDark = Color(0xFF0B328F)

internal val AlpineLightColors = lightColorScheme(
    primary = AlpineBlue,
    onPrimary = Color.White,
    primaryContainer = AlpineBlueContainer,
    onPrimaryContainer = Color(0xFF00164D),
    secondary = Color(0xFF53608F),
    secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF101A43),
    tertiary = Color(0xFF745574),
    tertiaryContainer = Color(0xFFFFD7FA),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEEEEF6),
    surfaceContainerHigh = Color(0xFFE8E8F0),
    onSurface = Color(0xFF1A1B20),
    onSurfaceVariant = Color(0xFF45464F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

internal val AlpineDarkColors = darkColorScheme(
    primary = AlpineBlueDark,
    onPrimary = Color(0xFF00277A),
    primaryContainer = AlpineBlueContainerDark,
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFBBC3F9),
    secondaryContainer = Color(0xFF3B466F),
    onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = Color(0xFFE2BBDD),
    tertiaryContainer = Color(0xFF5B3E5B),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1F2026),
    surfaceContainerHigh = Color(0xFF292A30),
    onSurface = Color(0xFFE3E2E9),
    onSurfaceVariant = Color(0xFFC6C6D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

data class AlpineStatusColors(
    val connected: Color,
    val onConnected: Color,
    val warning: Color,
    val onWarning: Color,
)

internal val LightStatusColors = AlpineStatusColors(
    connected = Color(0xFFD2F8DC),
    onConnected = Color(0xFF0A5C31),
    warning = Color(0xFFFFDDB3),
    onWarning = Color(0xFF6D3B00),
)

internal val DarkStatusColors = AlpineStatusColors(
    connected = Color(0xFF0A5C31),
    onConnected = Color(0xFFD2F8DC),
    warning = Color(0xFF6D3B00),
    onWarning = Color(0xFFFFDDB3),
)
