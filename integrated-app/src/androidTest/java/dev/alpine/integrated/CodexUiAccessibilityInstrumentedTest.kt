package dev.alpine.integrated

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.alpine.chat.feature.ui.ChatUiState
import dev.alpine.chat.feature.ui.ConnectedProviderOption
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.codex.appserver.CodexAuthState
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-destructive product UI contract checks. The test renders production composables with
 * in-memory state only; it never reads, clears, or writes Provider, conversation, workspace, or
 * Codex credential stores.
 */
@RunWith(AndroidJUnit4::class)
class CodexUiAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var activity: IntegratedMainActivity? = null

    @Before
    fun prepareUi() {
        runCatching {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
        finishLingeringActivities()
        instrumentation.waitForIdleSync()
    }

    @After
    fun closeActivity() {
        activity?.let { current ->
            if (!current.isDestroyed) {
                instrumentation.runOnMainSync { current.finish() }
                waitUntil { current.isDestroyed }
            }
        }
        activity = null
    }

    @Test
    fun codexActionsRemainAccessibleAtTwoHundredPercentFont() {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val current = launch()
        val authState = mutableStateOf<CodexAuthState>(CodexAuthState.SignedOut)
        var loginCount = 0
        var deviceCodeCount = 0
        var restartCount = 0
        var logoutCount = 0

        instrumentation.runOnMainSync {
            current.setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f, 2f)) {
                    AlpineProductTheme {
                        CodexLoginCard(
                            state = authState.value,
                            onStartBrowser = { loginCount += 1 },
                            onStartDeviceCode = { deviceCodeCount += 1 },
                            onOpenAuthorization = {},
                            onCancel = {},
                            onLogout = { logoutCount += 1 },
                            onRestart = { restartCount += 1 },
                            onRetry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Codex Agent").assertIsDisplayed()
        compose.onNodeWithTag("codex_login")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("codex_device_login")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(1, loginCount)
            assertEquals(1, deviceCodeCount)
            authState.value = CodexAuthState.SignedIn
        }

        compose.onNodeWithTag("codex_restart")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("codex_logout")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(1, restartCount)
            assertEquals(1, logoutCount)
        }
    }

    @Test
    fun koreanImeUsesProductComposerWithoutPersistentState() {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val current = launch()
        val uiState = mutableStateOf(
            ChatUiState(
                activeConversationId = "ui-contract",
                conversationTitle = "UI contract",
                providers = listOf(
                    ConnectedProviderOption(
                        profileId = "codex-agent",
                        label = "Codex Agent",
                        model = "test-model",
                        modelOptions = listOf("test-model"),
                    ),
                ),
                selectedProfileId = "codex-agent",
                selectedModel = "test-model",
            ),
        )
        var sentText: String? = null

        instrumentation.runOnMainSync {
            current.setContent {
                AlpineProductTheme {
                    AlpineChatScreen(
                        state = uiState.value,
                        onSelectProvider = {},
                        onSelectModel = { _, _ -> },
                        onSelectAssistantMode = { _, _, _ -> },
                        onResetAssistantMode = {},
                        onNewChat = {},
                        onSelectConversation = {},
                        onRenameConversation = { _, _ -> },
                        onDeleteConversation = {},
                        onManageProviders = {},
                        onDraftChange = { draft ->
                            uiState.value = uiState.value.copy(draft = draft)
                        },
                        onSend = { message -> sentText = message },
                        onStop = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val message = "삼성 한글 IME 전송 확인"
        compose.onNode(hasContentDescription("메시지 입력"))
            .assertIsDisplayed()
            .performTextInput(message)
        compose.onNode(hasContentDescription("메시지 입력")).performImeAction()
        compose.runOnIdle { assertEquals(message, sentText) }
    }

    @Test
    fun systemBackFinishesCodexUiSurfaceWithoutStoreMutation() {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val current = launch()
        instrumentation.runOnMainSync {
            current.setContent {
                AlpineProductTheme {
                    CodexLoginCard(
                        state = CodexAuthState.SignedIn,
                        onStartBrowser = {},
                        onStartDeviceCode = {},
                        onOpenAuthorization = {},
                        onCancel = {},
                        onLogout = {},
                        onRestart = {},
                        onRetry = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("codex_login_card").assertIsDisplayed()

        instrumentation.runOnMainSync { current.onBackPressedDispatcher.onBackPressed() }
        waitUntil { current.isDestroyed }
        assertTrue(current.isDestroyed)
        activity = null
    }

    private fun launch(): IntegratedMainActivity {
        val launched = instrumentation.startActivitySync(
            Intent(context, IntegratedMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as IntegratedMainActivity
        activity = launched
        waitUntil {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it === launched }
            }
            resumed
        }
        instrumentation.waitForIdleSync()
        return launched
    }

    private fun finishLingeringActivities() {
        var activities = emptyList<IntegratedMainActivity>()
        instrumentation.runOnMainSync {
            activities = ACTIVE_STAGES.flatMap { stage ->
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(stage)
                    .filterIsInstance<IntegratedMainActivity>()
            }.distinct()
            activities.forEach { current ->
                if (!current.isFinishing && !current.isDestroyed) current.finish()
            }
        }
        if (activities.isNotEmpty()) {
            waitUntil { activities.all { it.isDestroyed } }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("UI contract transition timed out", predicate())
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
        val ACTIVE_STAGES = listOf(
            Stage.CREATED,
            Stage.STARTED,
            Stage.RESUMED,
            Stage.PAUSED,
            Stage.STOPPED,
            Stage.RESTARTED,
        )
    }
}
