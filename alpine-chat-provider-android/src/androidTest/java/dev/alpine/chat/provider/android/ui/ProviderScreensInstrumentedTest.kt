package dev.alpine.chat.provider.android.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.ui.ChatUiState
import dev.alpine.chat.feature.ui.ConnectedProviderOption
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderModelSource
import dev.alpine.chat.provider.android.model.ProviderSaveAction
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ProviderConnection
import dev.alpine.chat.provider.android.session.ProviderConnectionIssue
import dev.alpine.chat.provider.android.session.ProviderConnectionState
import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProviderScreensInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var currentActivity: ProviderTestActivity? = null

    @Before
    fun launchHostActivity() {
        // createEmptyComposeRule does not own an Activity.  Samsung can briefly retain the
        // previous test Activity's composition when ActivityScenario launches a new host, so
        // launch synchronously and wait for RESUMED before registering this test's content.
        finishLingeringProviderTestActivities()
        currentActivity = instrumentation.startActivitySync(
            Intent(context, ProviderTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ProviderTestActivity
        waitForActivityStage(checkNotNull(currentActivity), Stage.RESUMED)
        instrumentation.waitForIdleSync()
    }

    @After
    fun closeHostActivity() {
        currentActivity?.let { activity -> finishActivities(listOf(activity)) }
        waitForNoProviderTestActivities()
        currentActivity = null
    }

    @Test
    fun emptyStateChooserRemainsReachableAtTwoHundredPercentFont() {
        var selected: ProviderType? = null
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                AlpineProductTheme {
                    ProviderProfilesScreen(
                        connections = emptyList(),
                        authorizingProfileId = null,
                        deleteCandidate = null,
                        onBack = {},
                        onAddProvider = { selected = it },
                        onEdit = {},
                        onConnectionAction = {},
                        onDelete = {},
                        onConfirmDelete = {},
                        onDismissDelete = {},
                    )
                }
            }
        }

        compose.onNodeWithText("연결된 LLM이 없습니다").assertExists()
        compose.onNodeWithTag("add_provider").performClick()
        compose.onNodeWithText("LLM Provider 선택").assertExists()
        compose.onNodeWithText("취소").assertIsDisplayed()
        compose.onNodeWithTag("provider_card_xai").performScrollTo().assertExists()
        compose.onNodeWithTag("provider_card_gemini").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ProviderType.GEMINI, selected) }
    }

    @Test
    fun connectionStatesExposeActionableKoreanStatus() {
        val connections = listOf(
            connection("connected", ProviderConnectionState.AUTHENTICATED),
            connection("signed-out", ProviderConnectionState.SIGNED_OUT),
            connection("reauth", ProviderConnectionState.REAUTHENTICATION_REQUIRED),
        )
        render {
            AlpineProductTheme {
                ProviderProfilesScreen(
                    connections = connections,
                    authorizingProfileId = null,
                    deleteCandidate = null,
                    onBack = {},
                    onAddProvider = {},
                    onEdit = {},
                    onConnectionAction = {},
                    onDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        compose.onNodeWithTag("profile_card_connected").performScrollTo()
        compose.onNodeWithText("연결됨").assertExists()
        compose.onNodeWithTag("profile_card_signed-out").performScrollTo()
        compose.onNodeWithText("연결 안 됨").assertExists()
        compose.onNodeWithTag("profile_card_reauth").performScrollTo()
        compose.onNodeWithText("재로그인 필요").assertExists()
    }

    @Test
    fun authorizingCardOwnsProgressAndCancellation() {
        var cancelCount = 0
        val active = connection("authorizing", ProviderConnectionState.SIGNED_OUT)
        render {
            AlpineProductTheme {
                ProviderProfilesScreen(
                    connections = listOf(active),
                    authorizingProfileId = active.profile.id,
                    deleteCandidate = null,
                    onBack = {},
                    onAddProvider = {},
                    onEdit = {},
                    onConnectionAction = {},
                    onDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onCancelAuthorization = { cancelCount += 1 },
                )
            }
        }

        compose.onNodeWithTag("authorization_progress_authorizing").assertIsDisplayed()
        compose.onNode(hasContentDescription("authorizing 로그인 진행 중")).assertExists()
        compose.onNode(hasContentDescription("authorizing 로그인 취소"))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("cancel_authorization_authorizing").performClick()
        compose.runOnIdle { assertEquals(1, cancelCount) }
    }

    @Test
    fun connectionIssueShowsOnlyStableCodeAndFixedGuidance() {
        val failed = connection("failed", ProviderConnectionState.SIGNED_OUT)
        render {
            AlpineProductTheme {
                ProviderProfilesScreen(
                    connections = listOf(failed),
                    authorizingProfileId = null,
                    deleteCandidate = null,
                    onBack = {},
                    onAddProvider = {},
                    onEdit = {},
                    onConnectionAction = {},
                    onDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    connectionIssues = mapOf(
                        failed.profile.id to ProviderConnectionIssue.from(
                            OAuthException("must-not-be-visible", OAuthFailureKind.NETWORK),
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithTag("connection_issue_failed").assertIsDisplayed()
        compose.onNodeWithText("오류 · AUTH_NETWORK").assertExists()
        compose.onNodeWithText("로그인 서버에 연결하지 못했습니다.\n네트워크를 확인한 뒤 다시 시도하세요.")
            .assertExists()
    }

    @Test
    fun editorSeparatesSaveAndLoginFromSaveForLaterAtTwoHundredPercentFont() {
        var action: ProviderSaveAction? = null
        val profile = ProviderProfile.draft(ProviderType.GEMINI, "Google Gemini").copy(
            clientId = "owned-public-client-id",
        )
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                AlpineProductTheme {
                    ProviderEditScreen(
                        initialProfile = profile,
                        isEditing = false,
                        onBack = {},
                        onSave = { _, selectedAction ->
                            action = selectedAction
                            emptyMap()
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("save_and_login").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ProviderSaveAction.SAVE_AND_LOGIN, action) }
        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ProviderSaveAction.SAVE_FOR_LATER, action) }
    }

    @Test
    fun configurableProviderAddsPersistsAndDisablesNonDefaultModelCandidates() {
        var saved: ProviderProfile? = null
        val profile = ProviderProfile.draft(ProviderType.OPENAI_COMPATIBLE, "Custom OAuth")
        render {
            AlpineProductTheme {
                ProviderEditScreen(
                    initialProfile = profile,
                    isEditing = false,
                    onBack = {},
                    onSave = { edited, _ ->
                        saved = edited
                        emptyMap()
                    },
                )
            }
        }

        scrollEditorTo("model_candidate_input")
        scrollEditorTo("add_model_candidate")
        compose.onNodeWithTag("add_model_candidate").performClick()
        scrollEditorTo("model_candidate_input")
        assertTrue(
            compose.onAllNodesWithText("추가할 모델 ID를 입력하세요.")
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        compose.onNodeWithTag("model_candidate_input").performTextInput(" model-alpha ")
        scrollEditorTo("add_model_candidate")
        compose.onNodeWithTag("add_model_candidate").performClick()
        scrollEditorTo("model_candidate_model-alpha")
        compose.onNodeWithTag("model_candidate_model-alpha").assertExists()
        scrollEditorTo("toggle_model_model-alpha")
        compose.onNodeWithTag("toggle_model_model-alpha").assertIsNotEnabled()

        scrollEditorTo("model_candidate_input")
        compose.onNodeWithTag("model_candidate_input").performTextInput("MODEL-ALPHA")
        scrollEditorTo("add_model_candidate")
        compose.onNodeWithTag("add_model_candidate").performClick()
        assertTrue(
            compose.onAllNodesWithText("이미 추가된 모델 ID입니다.")
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        scrollEditorTo("model_candidate_input")
        compose.onNodeWithTag("model_candidate_input").performTextClearance()
        compose.onNodeWithTag("model_candidate_input").performTextInput("model-beta")
        scrollEditorTo("add_model_candidate")
        compose.onNodeWithTag("add_model_candidate").performClick()
        scrollEditorTo("toggle_model_model-beta")
        compose.onNodeWithTag("toggle_model_model-beta").performClick()
        compose.onNodeWithText("사용자 추가 · 사용 중지").assertExists()

        scrollEditorTo("save_for_later")
        compose.onNodeWithTag("save_for_later").performClick()
        compose.runOnIdle {
            assertEquals("model-alpha", saved?.model)
            assertEquals(
                listOf(
                    Triple("model-alpha", ProviderModelSource.USER_ADDED, true),
                    Triple("model-beta", ProviderModelSource.USER_ADDED, false),
                ),
                saved?.modelCatalog?.map { Triple(it.modelId, it.source, it.enabled) },
            )
        }
    }

    @Test
    fun codexEditorUsesOAuthFieldsAndPinsTheSafeContract() {
        val profile = ProviderProfile.draft(ProviderType.CODEX, "OpenAI Responses")
        render {
            AlpineProductTheme {
                ProviderEditScreen(
                    initialProfile = profile,
                    isEditing = false,
                    onBack = {},
                    onSave = { _, _ -> emptyMap() },
                )
            }
        }

        compose.onNodeWithTag("client_id").assertExists()
        compose.onNodeWithTag("api_key").assertDoesNotExist()
        scrollEditorTo("model_candidate_input")
        compose.onNodeWithTag("model").assertDoesNotExist()
        compose.onNodeWithTag("model_candidate_input").assertExists()
        scrollEditorTo("authorization_endpoint")
        compose.onNodeWithTag("authorization_endpoint")
            .assertTextContains(CodexOAuthContract.AUTHORIZATION_ENDPOINT)
        scrollEditorTo("callback_port")
        compose.onNodeWithTag("callback_port")
            .assertTextContains(CodexOAuthContract.CALLBACK_PORT.toString())
    }

    @Test
    fun unchangedEditorBackDoesNotRequireDiscardConfirmation() {
        var backCount = 0
        val profile = ProviderProfile.draft(ProviderType.GEMINI, "Google Gemini")
        render {
            AlpineProductTheme {
                ProviderEditScreen(
                    initialProfile = profile,
                    isEditing = false,
                    onBack = { backCount += 1 },
                    onSave = { _, _ -> emptyMap() },
                )
            }
        }

        compose.onNode(hasContentDescription("뒤로")).performClick()
        compose.onNodeWithText("변경사항을 버릴까요?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test
    fun longProviderContentKeepsFullSemanticsAndProviderSpecificActions() {
        val longLabel = "업무 자동화와 보안 검증을 위한 매우 긴 OpenAI 호환 Provider 연결 이름"
        val longModel = "organization/team/research/experimental-model-with-a-very-long-version-name-2026-08"
        val active = connection(
            id = "long-content",
            state = ProviderConnectionState.SIGNED_OUT,
            label = longLabel,
            model = longModel,
        )
        render {
            AlpineProductTheme {
                ProviderProfilesScreen(
                    connections = listOf(active),
                    authorizingProfileId = null,
                    deleteCandidate = null,
                    onBack = {},
                    onAddProvider = {},
                    onEdit = {},
                    onConnectionAction = {},
                    onDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        compose.onNodeWithTag("profile_card_long-content").performScrollTo()
        compose.onNodeWithTag("profile_label_long-content").assertTextContains(longLabel)
        compose.onNodeWithText("MODEL · $longModel", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("profile_card_long-content").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "연결 안 됨. 모델 $longModel",
            ),
        )
        compose.onNode(hasContentDescription("$longLabel 로그인"))
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("$longLabel 설정"))
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("$longLabel 작업 메뉴"))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun providerActionsRemainReachableInCompactViewportAtTwoHundredPercentFont() {
        val active = connection(
            id = "compact",
            state = ProviderConnectionState.REAUTHENTICATION_REQUIRED,
            label = "긴 이름을 사용하는 Compact Provider 연결",
            model = "compact-provider-model-with-long-context-version",
        )
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(800.dp),
                ) {
                    AlpineProductTheme {
                        ProviderProfilesScreen(
                            connections = listOf(active),
                            authorizingProfileId = null,
                            deleteCandidate = null,
                            onBack = {},
                            onAddProvider = {},
                            onEdit = {},
                            onConnectionAction = {},
                            onDelete = {},
                            onConfirmDelete = {},
                            onDismissDelete = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("connection_action_compact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_action_compact").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("edit_profile_compact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("edit_profile_compact").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun providerChooserRemainsReachableInCompactLandscapeViewport() {
        var selected: ProviderType? = null
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(
                    modifier = Modifier
                        .width(800.dp)
                        .height(360.dp),
                ) {
                    AlpineProductTheme {
                        ProviderProfilesScreen(
                            connections = emptyList(),
                            authorizingProfileId = null,
                            deleteCandidate = null,
                            onBack = {},
                            onAddProvider = { selected = it },
                            onEdit = {},
                            onConnectionAction = {},
                            onDelete = {},
                            onConfirmDelete = {},
                            onDismissDelete = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("add_provider").performScrollTo().performClick()
        compose.onNodeWithTag("dismiss_provider_chooser")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("provider_card_xai").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ProviderType.XAI, selected) }
    }

    @Test
    fun compactChatKeepsMessagesAndComposerInsideParentAtTwoHundredPercentFont() {
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CompactChatUnderTest(Modifier.width(360.dp).height(800.dp))
            }
        }

        compose.onNodeWithTag("chat_context_toggle").performClick()
        compose.onNodeWithTag("provider_selector").assertIsDisplayed()
        compose.onNodeWithTag("model_quick_switcher").assertIsDisplayed()
        compose.onNodeWithTag("assistant_mode_selector").assertIsDisplayed()
        compose.onNodeWithTag("messages_list").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsDisplayed()

        assertCompactChatBounds()
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun compactChatKeepsComposerVisibleWhenImeOpensAndClosesAtTwoHundredPercentFont() {
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CompactChatUnderTest(Modifier.fillMaxSize())
            }
        }

        compose.onNodeWithTag("message_input").performClick()
        compose.waitUntil(ACTIVITY_LIFECYCLE_TIMEOUT_MS) { isImeVisible() }
        compose.onNodeWithTag("message_input").assertIsDisplayed()
        assertCompactChatBounds()

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(ACTIVITY_LIFECYCLE_TIMEOUT_MS) { !isImeVisible() }
        compose.onNodeWithTag("message_input").assertIsDisplayed()
        assertCompactChatBounds()
    }

    @Composable
    private fun CompactChatUnderTest(modifier: Modifier) {
        Box(modifier = modifier.testTag("compact_chat_root")) {
            AlpineProductTheme {
                AlpineChatScreen(
                    state = ChatUiState(
                        activeConversationId = "conversation",
                        conversationTitle = "긴 대화 제목과 큰 글꼴 검증",
                        messages = listOf(
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                text = "큰 글꼴에서도 메시지 목록과 입력창이 겹치지 않습니다.",
                            ),
                        ),
                        providers = listOf(
                            ConnectedProviderOption(
                                profileId = "provider",
                                label = "긴 이름의 Provider",
                                model = "model-a",
                                modelOptions = listOf("model-a", "model-b"),
                            ),
                        ),
                        selectedProfileId = "provider",
                        selectedModel = "model-a",
                    ),
                    onSelectProvider = {},
                    onSelectModel = { _, _ -> },
                    onSelectAssistantMode = { _, _, _ -> },
                    onResetAssistantMode = {},
                    onNewChat = {},
                    onSelectConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onManageProviders = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun assertCompactChatBounds() {
        val parent = compose.onNodeWithTag("compact_chat_root").fetchSemanticsNode().boundsInRoot
        val messages = compose.onNodeWithTag("messages_list").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot
        assertTrue(messages.left >= parent.left && messages.right <= parent.right)
        assertTrue(messages.top >= parent.top && messages.bottom <= parent.bottom)
        assertTrue(input.left >= parent.left && input.right <= parent.right)
        assertTrue(input.top >= parent.top && input.bottom <= parent.bottom)
        assertTrue(messages.bottom <= input.top)
    }

    @SuppressLint("NewApi")
    private fun isImeVisible(): Boolean = currentActivity
        ?.window
        ?.decorView
        ?.rootWindowInsets
        ?.isVisible(WindowInsets.Type.ime()) == true

    private fun render(content: @Composable () -> Unit) {
        val activity = checkNotNull(currentActivity)
        instrumentation.runOnMainSync {
            activity.setContent {
                Box(modifier = Modifier.testTag(PROVIDER_TEST_ROOT_TAG)) {
                    content()
                }
            }
        }
        // setContent returns before Compose registers a root with an empty rule on some physical
        // devices.  Wait for the explicit wrapper instead of letting the first UI assertion race
        // that registration.
        compose.waitUntil(ACTIVITY_LIFECYCLE_TIMEOUT_MS) {
            runCatching {
                compose.onNodeWithTag(PROVIDER_TEST_ROOT_TAG).assertExists()
                true
            }.getOrDefault(false)
        }
    }

    private fun scrollEditorTo(tag: String) {
        repeat(8) {
            val found = runCatching {
                compose.onNodeWithTag(tag).assertExists()
                true
            }.getOrDefault(false)
            if (found) return
            compose.onNodeWithTag("provider_edit_screen").performTouchInput {
                swipe(
                    start = Offset(center.x, visibleSize.height * 0.72f),
                    end = Offset(center.x, visibleSize.height * 0.28f),
                )
            }
        }
        repeat(12) {
            val found = runCatching {
                compose.onNodeWithTag(tag).assertExists()
                true
            }.getOrDefault(false)
            if (found) return
            compose.onNodeWithTag("provider_edit_screen").performTouchInput {
                swipe(
                    start = Offset(center.x, visibleSize.height * 0.28f),
                    end = Offset(center.x, visibleSize.height * 0.72f),
                )
            }
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    private fun finishLingeringProviderTestActivities() {
        val activities = providerTestActivities()
        if (activities.isEmpty()) return
        finishActivities(activities)
        waitForNoProviderTestActivities()
    }

    private fun finishActivities(activities: List<ProviderTestActivity>) {
        instrumentation.runOnMainSync {
            activities.forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finish()
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitForActivityStage(activity: ProviderTestActivity, stage: Stage) {
        waitForCondition("ProviderTestActivity reaches $stage") {
            activitiesInStage(stage).any { it === activity }
        }
    }

    private fun waitForNoProviderTestActivities() {
        waitForCondition("all ProviderTestActivity instances finish") {
            providerTestActivities().isEmpty()
        }
    }

    private fun providerTestActivities(): List<ProviderTestActivity> =
        ACTIVE_ACTIVITY_STAGES.flatMap(::activitiesInStage).distinct()

    private fun activitiesInStage(stage: Stage): List<ProviderTestActivity> {
        var activities = emptyList<ProviderTestActivity>()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(stage)
                .filterIsInstance<ProviderTestActivity>()
        }
        return activities
    }

    private fun waitForCondition(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ACTIVITY_LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(ACTIVITY_LIFECYCLE_POLL_MS)
        }
        check(predicate()) { "Timed out while waiting for $description" }
    }

    private fun connection(
        id: String,
        state: ProviderConnectionState,
        label: String = id,
        model: String? = null,
    ): ProviderConnection {
        val draft = ProviderProfile.draft(ProviderType.GEMINI, label)
        val profile = draft.copy(
            id = id,
            clientId = "test-client-id",
            model = model ?: draft.model,
        )
        val session = object : ChatCompletionSession {
            override val profile: ProviderProfile = profile

            override fun authenticationState(): OAuthAuthenticationState =
                OAuthAuthenticationState.SignedOut

            override suspend fun authorize(activity: Activity) = Unit

            override suspend fun stream(requestJson: String): ChatBackendStreamResult =
                ChatBackendStreamResult()

            override fun logout() = Unit

            override fun cancelAuthorization() = Unit
        }
        return ProviderConnection(profile, state, session)
    }
}

private val ACTIVE_ACTIVITY_STAGES = listOf(
    Stage.CREATED,
    Stage.STARTED,
    Stage.RESUMED,
    Stage.PAUSED,
    Stage.STOPPED,
    Stage.RESTARTED,
)

private const val ACTIVITY_LIFECYCLE_TIMEOUT_MS = 5_000L
private const val ACTIVITY_LIFECYCLE_POLL_MS = 25L
private const val PROVIDER_TEST_ROOT_TAG = "provider_test_root"

class ProviderTestActivity : ComponentActivity()
