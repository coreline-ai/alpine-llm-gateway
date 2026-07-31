package dev.alpine.llm.demo.ui.theme

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
