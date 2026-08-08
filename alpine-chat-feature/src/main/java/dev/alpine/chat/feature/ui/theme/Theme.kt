package dev.alpine.chat.feature.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalAlpineStatusColors = staticCompositionLocalOf { LightStatusColors }

object AlpineTheme {
    val statusColors: AlpineStatusColors
        @Composable get() = LocalAlpineStatusColors.current
}

@Composable
fun AlpineChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            androidx.compose.material3.dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            androidx.compose.material3.dynamicLightColorScheme(context)
        darkTheme -> AlpineDarkColors
        else -> AlpineLightColors
    }
    val status = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalAlpineStatusColors provides status) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AlpineTypography,
            shapes = AlpineShapes,
            content = content,
        )
    }
}

/**
 * Stable product shell used by Alpine-owned Android screens.
 *
 * SDK consumers may keep using [AlpineChatTheme] with their own dark/dynamic
 * preferences. Product Activities use this wrapper so a navigation hop does
 * not unexpectedly switch to a system-generated palette.
 */
@Composable
fun AlpineProductTheme(content: @Composable () -> Unit) {
    AlpineChatTheme(
        darkTheme = false,
        dynamicColor = false,
        content = content,
    )
}
