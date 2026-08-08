package dev.alpine.llm.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme
import dev.alpine.chat.feature.ui.theme.AlpineDesignTokens
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun alpineLightThemeUsesBrandPrimaryWhenDynamicColorIsOff() {
        assertThemePrimary(
            darkTheme = false,
            expectedArgb = AlpineDesignTokens.Acid.toArgb(),
        )
    }

    @Test
    fun alpineDarkThemeUsesAccessibleBrandPrimaryWhenDynamicColorIsOff() {
        assertThemePrimary(
            darkTheme = true,
            expectedArgb = AlpineDesignTokens.Acid.toArgb(),
        )
    }

    private fun assertThemePrimary(darkTheme: Boolean, expectedArgb: Int) {
        val observedArgb = AtomicInteger()
        compose.setContent {
            AlpineChatTheme(
                darkTheme = darkTheme,
                dynamicColor = false,
            ) {
                val primary = MaterialTheme.colorScheme.primary
                SideEffect { observedArgb.set(primary.toArgb()) }
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("theme_probe"),
                )
            }
        }

        compose.onNodeWithTag("theme_probe").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(expectedArgb, observedArgb.get())
    }
}
