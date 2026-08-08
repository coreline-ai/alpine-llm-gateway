package dev.alpine.integrated

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.backend.direct.AndroidDirectChatBackend
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.provider.android.ProviderDependencies
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.routing.ChatBackend
import dev.alpine.chat.routing.ChatBackendCapabilities
import dev.alpine.chat.routing.ChatBackendFailure
import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatBackendIdempotency
import dev.alpine.chat.routing.ChatBackendKind
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatFailureStage
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.SafeChatRouter
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.llm.OAuthAuthenticationState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegratedFastChatInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var currentActivity: Activity? = null

    @Before
    fun resetState() {
        clearState()
        IntegratedModeGuideStore(context).markCompleted()
        ProviderDependencies.installSessionFactoryForTests(null)
        IntegratedAlpineDependencies.installRouterFactoryForTests(null)
    }

    @After
    fun cleanup() {
        ProviderDependencies.installSessionFactoryForTests(null)
        IntegratedAlpineDependencies.installRouterFactoryForTests(null)
        currentActivity?.let { activity ->
            instrumentation.runOnMainSync { activity.finish() }
        }
        currentActivity = null
        clearState()
    }

    @Test
    fun loginModelStreamModeNavigationStopAndRetryUseDirectProvider() {
        val profile = geminiProfile("integrated-flow")
        val scenario = IntegratedProviderScenario(startSignedOut = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected ->
            scenario.create(selected)
        }

        launch()
        compose.onNodeWithTag("chat_screen").assertIsDisplayed()
        compose.onNodeWithTag("mode_fast_chat").assertIsSelected()
        compose.onNodeWithTag("manage_providers").performClick()
        compose.onNodeWithTag("provider_list_screen").assertIsDisplayed()
        compose.onNodeWithText("로그인").performClick()
        compose.waitUntil(5_000) { scenario.authorizeCount.get() == 1 }
        compose.onNode(hasContentDescription("뒤로")).performClick()

        compose.onNodeWithText(profile.label).assertIsDisplayed()
        compose.onNodeWithTag("quick_model_gemini-3.5-flash").performClick()
        compose.waitUntil(5_000) {
            ProviderProfileStore(context).find(profile.id)?.model == "gemini-3.5-flash"
        }

        compose.onNodeWithTag("message_input").performTextInput("first integrated request")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("stop_button").assertIsDisplayed()
        compose.onNode(
            hasContentDescription("AI 답변") and
                hasText("Slow partial answer") and
                hasStateDescription("생성 중"),
        ).assertExists()

        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("mode_fast_chat").performClick()
        compose.onNodeWithTag("stop_button").performClick()
        compose.onNode(
            hasContentDescription("AI 답변") and
                hasText("Slow partial answer") and
                hasStateDescription("중지됨"),
        ).assertIsDisplayed()

        compose.onNodeWithTag("message_input").performTextInput("retry integrated request")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("retry_button").assertIsDisplayed()
        compose.onNodeWithTag("retry_button").performClick()
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertIsDisplayed()

        assertEquals(3, scenario.requestCount.get())
    }

    @Test
    fun conversationAndPerConversationModeRestoreAfterActivityRestart() {
        val profile = geminiProfile("integrated-restore")
        val scenario = IntegratedProviderScenario(startSignedOut = false, immediate = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected ->
            scenario.create(selected)
        }

        launch()
        compose.onNodeWithTag("message_input").performTextInput("persist integrated answer")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertIsDisplayed()

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithTag("message_input").performTextInput("second conversation draft")
        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNodeWithText("persist integrated answer").performClick()
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertIsDisplayed()

        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()

        closeCurrentActivity()
        launch()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("mode_fast_chat").performClick()
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertIsDisplayed()
        assertEquals("gemini-3.6-flash", ProviderProfileStore(context).find(profile.id)?.model)
    }

    @Test
    fun alpineFallbackRequiresExplicitApprovalBeforeDirectProviderDispatch() {
        val profile = geminiProfile("integrated-alpine-fallback")
        val scenario = IntegratedProviderScenario(startSignedOut = false, immediate = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }
        installUnavailableAlpineRouter()

        launch()
        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("message_input").performTextInput("approved alpine fallback")
        compose.onNodeWithTag("send_button").performClick()

        compose.onNodeWithTag("fallback_approve").assertIsDisplayed()
        assertEquals(0, scenario.requestCount.get())
        compose.onNodeWithTag("fallback_approve").performClick()

        waitForDisplayedAssistantText("Integrated recovered answer")
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertIsDisplayed()
        assertEquals(1, scenario.requestCount.get())
    }

    @Test
    fun decliningAlpineFallbackNeverDispatchesDirectProvider() {
        val profile = geminiProfile("integrated-alpine-decline")
        val scenario = IntegratedProviderScenario(startSignedOut = false, immediate = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }
        installUnavailableAlpineRouter()

        launch()
        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("message_input").performTextInput("declined alpine fallback")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("fallback_decline").assertIsDisplayed()
        compose.onNodeWithTag("fallback_decline").performClick()

        val failureMessage =
            "Fast-chat fallback was declined. Start Alpine and retry when ready."
        waitForDisplayedText(failureMessage)
        compose.onNodeWithText(failureMessage).assertIsDisplayed()
        assertEquals(0, scenario.requestCount.get())
    }

    @Test
    fun koreanImeAndTalkBackSemanticsCoverChatMessagesHistoryAndAssistant() {
        val profile = geminiProfile("integrated-accessibility")
        val scenario = IntegratedProviderScenario(startSignedOut = false, immediate = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected ->
            scenario.create(selected)
        }

        launch()
        compose.onNode(hasContentDescription("대화 기록 열기"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("새 대화 시작"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("LLM 연결 관리"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("응답 설정", substring = true))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("메시지 입력"))
            .performTextInput("접근성 한글 IME 확인")
        compose.onNode(hasContentDescription("메시지 전송"))
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("메시지 입력")).performImeAction()

        compose.waitUntil(5_000) { scenario.requestCount.get() == 1 }
        compose.onNode(
            hasContentDescription("사용자 메시지") and hasText("접근성 한글 IME 확인"),
        ).assertExists()
        compose.onNode(
            hasContentDescription("AI 답변") and hasText("Integrated recovered answer"),
        ).assertExists()

        compose.onNode(hasContentDescription("대화 기록 열기")).performClick()
        compose.onNodeWithText("대화 기록").assertIsDisplayed()
        compose.onNodeWithTag("history_new_chat")
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(
            hasContentDescription("접근성 한글 IME 확인 대화 작업"),
        ).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("대화 기록 닫기"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.onNode(hasContentDescription("응답 설정", substring = true)).performClick()
        compose.onNodeWithText("응답 설정").assertIsDisplayed()
        compose.onNode(hasContentDescription("응답 설정 닫기"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, scenario.requestCount.get())
    }

    @Test
    fun modeGuideStoreIsVersionedAndContainsOnlyCompletionVersion() {
        val store = IntegratedModeGuideStore(context)
        store.clear()
        assertTrue(store.shouldShowGuide())

        assertTrue(store.markCompleted())
        assertFalse(store.shouldShowGuide())
        assertEquals(
            mapOf(
                IntegratedModeGuideStore.KEY_COMPLETED_VERSION to
                    IntegratedModeGuideStore.CURRENT_GUIDE_VERSION,
            ),
            context.getSharedPreferences(
                IntegratedModeGuideStore.FILE_NAME,
                Context.MODE_PRIVATE,
            ).all,
        )

        context.getSharedPreferences(IntegratedModeGuideStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                IntegratedModeGuideStore.KEY_COMPLETED_VERSION,
                IntegratedModeGuideStore.CURRENT_GUIDE_VERSION - 1,
            )
            .commit()
        assertTrue(store.shouldShowGuide())
    }

    @Test
    fun firstRunGuideDismissesTemporarilyPersistsChoiceAndCanReopen() {
        val store = IntegratedModeGuideStore(context)
        store.clear()

        launch()
        compose.onNodeWithTag("mode_guide_sheet").assertIsDisplayed()
        compose.onNodeWithText("어떤 모드로 시작할까요?").assertIsDisplayed()
        compose.onNodeWithTag("dismiss_mode_guide").performClick()
        compose.onNodeWithTag("mode_guide_sheet").assertDoesNotExist()
        assertTrue(store.shouldShowGuide())

        closeCurrentActivity()
        launch()
        compose.onNodeWithTag("mode_guide_sheet").assertIsDisplayed()
        compose.onNodeWithTag("guide_start_fast_chat").performClick()
        compose.onNodeWithTag("mode_guide_sheet").assertDoesNotExist()
        compose.onNodeWithTag("mode_fast_chat").assertIsSelected()
        assertFalse(store.shouldShowGuide())

        closeCurrentActivity()
        launch()
        compose.onNodeWithTag("mode_guide_sheet").assertDoesNotExist()
        compose.onNodeWithTag("open_mode_guide").performClick()
        compose.onNodeWithTag("mode_guide_sheet").assertIsDisplayed()
    }

    @Test
    fun modeGuideOpenAndTemporaryDismissStateSurviveActivityRecreation() {
        val store = IntegratedModeGuideStore(context)
        store.clear()
        launch()
        compose.onNodeWithTag("mode_guide_sheet").assertIsDisplayed()

        recreateCurrentActivity()
        compose.onNodeWithTag("mode_guide_sheet").assertIsDisplayed()
        compose.onNodeWithTag("dismiss_mode_guide").performClick()
        compose.onNodeWithTag("mode_guide_sheet").assertDoesNotExist()

        recreateCurrentActivity()
        compose.onNodeWithTag("mode_guide_sheet").assertDoesNotExist()
        assertTrue(store.shouldShowGuide())
    }

    @Test
    fun modeGuideSelectsAlpineWithoutStartingRuntimeAndExplainsFallbackRecovery() {
        val store = IntegratedModeGuideStore(context)
        store.clear()
        val application = context.applicationContext as IntegratedApplication
        val lifecycleBefore = application.alpineLlmHost.currentState().lifecycle

        launch()
        compose.onNodeWithTag("mode_guide_content")
            .performScrollToNode(hasTestTag("guide_alpine_workspace_card"))
        compose.onNodeWithText("Runtime 설치·시작과 Provider 연결").assertExists()
        compose.onNodeWithTag("mode_guide_content")
            .performScrollToNode(hasTestTag("guide_fallback_policy"))
        compose.onNodeWithText(
            "사용자 승인 없이 빠른 채팅으로 자동 전송하지 않습니다.",
            substring = true,
        ).assertExists()
        compose.onNodeWithTag("guide_start_alpine_workspace").performClick()

        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("alpine_gateway_status").assertIsDisplayed()
        assertEquals(lifecycleBefore, application.alpineLlmHost.currentState().lifecycle)
        assertFalse(store.shouldShowGuide())
    }

    @Test
    fun modeGuideActionsRemainReachableAtCompactWidthAndTwoHundredPercentFont() {
        launch()
        val activity = checkNotNull(currentActivity) as ComponentActivity
        var selectedMode: dev.alpine.chat.routing.ChatExecutionMode? = null
        instrumentation.runOnMainSync {
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f, 2f)) {
                    AlpineProductTheme {
                        IntegratedModeGuideSheet(
                            onDismiss = {},
                            onStartMode = { selectedMode = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("mode_guide_content")
            .performScrollToNode(hasTestTag("guide_fallback_policy"))
        compose.onNodeWithTag("guide_fallback_policy").assertExists()
        compose.onNodeWithTag("guide_start_fast_chat")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("guide_start_alpine_workspace")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(dev.alpine.chat.routing.ChatExecutionMode.ALPINE_WORKSPACE, selectedMode)
        }
    }

    private fun launch() {
        val activity = instrumentation.startActivitySync(
            Intent(context, IntegratedMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        currentActivity = activity
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun closeCurrentActivity() {
        val activity = checkNotNull(currentActivity)
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        currentActivity = null
    }

    private fun recreateCurrentActivity() {
        val previous = checkNotNull(currentActivity)
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(5_000) { previous.isDestroyed }
        instrumentation.runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<IntegratedMainActivity>()
                .single()
        }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun waitForDisplayedText(text: String) {
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    private fun waitForDisplayedAssistantText(text: String) {
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNode(
                    hasContentDescription("AI 답변") and hasText(text),
                ).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    private fun clearState() {
        context.getSharedPreferences(ProviderProfileStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ConversationStore(context).clear()
        AssistantDefaultsStore(context).clear()
        IntegratedModeGuideStore(context).clear()
    }

    private fun installUnavailableAlpineRouter() {
        IntegratedAlpineDependencies.installRouterFactoryForTests { session ->
            val direct = AndroidDirectChatBackend("test-direct") { requestJson ->
                session.streamForHostBridge(requestJson)
            }
            val alpine = object : ChatBackend {
                override val id = "test-alpine"
                override val kind = ChatBackendKind.ALPINE_GATEWAY
                override val capabilities = ChatBackendCapabilities(ChatBackendIdempotency.NONE)
                override suspend fun prepare(request: ChatBackendRequest) =
                    ChatBackendPreparation.Unavailable(
                        ChatBackendFailure(
                            ChatBackendFailureCode.RUNTIME_NOT_INSTALLED,
                            ChatFailureStage.PREPARATION,
                            retryable = false,
                        ),
                    )
                override suspend fun stream(
                    request: ChatBackendRequest,
                    emitter: ChatStreamEmitter,
                ): ChatBackendResult = error("unavailable backend must not dispatch")
            }
            SafeChatRouter(direct, alpine)
        }
    }

    private fun geminiProfile(id: String): ProviderProfile =
        ProviderProfile.draft(ProviderType.GEMINI, "Integrated Gemini").copy(
            id = id,
            clientId = "integration-test-public-client",
            createdAtMs = 1L,
        )
}

private class IntegratedProviderScenario(
    startSignedOut: Boolean,
    private val immediate: Boolean = false,
) {
    val authorizeCount = AtomicInteger()
    val requestCount = AtomicInteger()
    private val signedOut = AtomicBoolean(startSignedOut)

    fun create(profile: ProviderProfile): ChatCompletionSession = object : ChatCompletionSession {
        override val profile = profile

        override fun authenticationState(): OAuthAuthenticationState =
            if (signedOut.get()) {
                OAuthAuthenticationState.SignedOut
            } else {
                OAuthAuthenticationState.Authenticated(null, emptyMap())
            }

        override suspend fun authorize(activity: Activity) {
            authorizeCount.incrementAndGet()
            signedOut.set(false)
        }

        override suspend fun stream(requestJson: String): ChatBackendStreamResult {
            val attempt = requestCount.incrementAndGet()
            if (immediate) {
                return ChatBackendStreamResult(
                    events = flowOf(ChatBackendDelta("Integrated recovered answer")),
                )
            }
            return when (attempt) {
                1 -> ChatBackendStreamResult(
                    events = flow {
                        emit(ChatBackendDelta("Slow partial answer"))
                        awaitCancellation()
                    },
                )
                2 -> ChatBackendStreamResult(statusCode = 503)
                else -> ChatBackendStreamResult(
                    events = flowOf(ChatBackendDelta("Integrated recovered answer")),
                )
            }
        }

        override fun logout() {
            signedOut.set(true)
        }

        override fun cancelAuthorization() = Unit
    }
}
