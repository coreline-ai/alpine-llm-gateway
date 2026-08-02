package dev.alpine.runtime.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.host.RuntimeHostState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuntimeAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun talkBackStateAndTerminalOutputSemanticsExistAtTwoHundredPercentFont() {
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = "test"),
            sessionActive = true,
            terminalActive = true,
            terminalText = "Alpine ready",
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    androidx.compose.foundation.layout.Column {
                        RuntimeDashboard(state, {}, {}, {}, {}, {}, {})
                        RuntimeTerminalPanel(state, {}, {}, {}, {})
                    }
                }
            }
        }

        compose.onNode(hasStateDescription("실행 중")).assertExists()
        compose.onNode(hasContentDescription("Alpine 터미널 출력")).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun koreanImeAndExternalEnterSubmitTerminalCommands() {
        val sent = mutableListOf<String>()
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
            terminalActive = true,
        )
        compose.setContent {
            MaterialTheme {
                RuntimeTerminalPanel(state, {}, { sent += it }, {}, {})
            }
        }
        val input = compose.onNode(hasSetTextAction())

        input.performTextInput("한글 확인")
        input.performImeAction()
        compose.runOnIdle { assertEquals(listOf("한글 확인"), sent) }

        input.performTextInput("external-keyboard")
        input.performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        compose.runOnIdle { assertEquals(listOf("한글 확인", "external-keyboard"), sent) }
    }
}
