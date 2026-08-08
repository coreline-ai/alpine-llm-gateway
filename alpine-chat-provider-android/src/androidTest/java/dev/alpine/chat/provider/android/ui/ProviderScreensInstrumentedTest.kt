package dev.alpine.chat.provider.android.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderSaveAction
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ProviderConnection
import dev.alpine.chat.provider.android.session.ProviderConnectionIssue
import dev.alpine.chat.provider.android.session.ProviderConnectionState
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProviderScreensInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ProviderTestActivity>

    @Before
    fun launchHostActivity() {
        scenario = ActivityScenario.launch(ProviderTestActivity::class.java)
    }

    @After
    fun closeHostActivity() {
        scenario.close()
    }

    @Test
    fun emptyStateChooserRemainsReachableAtTwoHundredPercentFont() {
        var selected: ProviderType? = null
        scenario.onActivity { activity ->
            activity.setContent {
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        }

        compose.onNodeWithTag("save_and_login").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ProviderSaveAction.SAVE_AND_LOGIN, action) }
        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ProviderSaveAction.SAVE_FOR_LATER, action) }
    }

    @Test
    fun unchangedEditorBackDoesNotRequireDiscardConfirmation() {
        var backCount = 0
        val profile = ProviderProfile.draft(ProviderType.GEMINI, "Google Gemini")
        scenario.onActivity { activity ->
            activity.setContent {
                AlpineProductTheme {
                    ProviderEditScreen(
                        initialProfile = profile,
                        isEditing = false,
                        onBack = { backCount += 1 },
                        onSave = { _, _ -> emptyMap() },
                    )
                }
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        scenario.onActivity { activity ->
            activity.setContent {
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
        }

        compose.onNodeWithTag("connection_action_compact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_action_compact").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("edit_profile_compact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("edit_profile_compact").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun providerChooserRemainsReachableInCompactLandscapeViewport() {
        var selected: ProviderType? = null
        scenario.onActivity { activity ->
            activity.setContent {
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
        }

        compose.onNodeWithTag("add_provider").performScrollTo().performClick()
        compose.onNodeWithTag("dismiss_provider_chooser")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("provider_card_xai").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ProviderType.XAI, selected) }
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

class ProviderTestActivity : ComponentActivity()
