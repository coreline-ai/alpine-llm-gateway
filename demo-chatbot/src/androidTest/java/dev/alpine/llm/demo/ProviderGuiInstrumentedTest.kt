package dev.alpine.llm.demo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.chat.provider.android.ProviderDependencies
import dev.alpine.chat.provider.android.activity.ProviderEditActivity
import dev.alpine.chat.provider.android.activity.ProviderProfilesActivity
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationCrypto
import dev.alpine.chat.feature.data.ConversationIndex
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.model.ChatConversation
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.provider.android.model.GeminiProfileDefaults
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ProviderAuthorizationRecoveryStore
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.demo.support.FakeProviderScenario
import dev.alpine.llm.demo.support.ScriptedChatCompletionSession
import dev.alpine.llm.demo.support.testProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation

@RunWith(AndroidJUnit4::class)
class ProviderGuiInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var currentActivity: Activity? = null
    private val oauthTokenIds = mutableSetOf<String>()

    @Before
    fun resetTestState() {
        clearProfiles()
        clearAuthorizationRecovery()
        clearConversations()
        clearAssistantDefaults()
        ProviderDependencies.installSessionFactoryForTests(null)
    }

    @After
    fun cleanupTestState() {
        ProviderDependencies.installSessionFactoryForTests(null)
        currentActivity?.let { activity ->
            instrumentation.runOnMainSync { activity.finish() }
        }
        currentActivity = null
        val tokenStore = OAuthTokenStore(context)
        oauthTokenIds.forEach(tokenStore::delete)
        oauthTokenIds.clear()
        clearProfiles()
        clearAuthorizationRecovery()
        clearConversations()
        clearAssistantDefaults()
    }

    @Test
    fun mainScreenStartsWithProviderManagementAvailable() {
        launch(MainActivity::class.java)

        compose.onNodeWithTag("chat_screen").assertIsDisplayed()
        compose.onNodeWithTag("manage_providers").assertIsDisplayed()
        compose.onNodeWithTag("provider_selector").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsDisplayed()
        compose.onNodeWithTag("assistant_mode_selector").assertIsDisplayed()
        compose.onNodeWithText(
            "Codex, Claude, Gemini 또는 Grok을 연결한 뒤 모델을 선택하세요.",
        ).assertIsDisplayed()
    }

    @Test
    fun assistantModeSelectionPersistsAndSavedDefaultAppliesAfterRelaunch() {
        launch(MainActivity::class.java)
        compose.onNodeWithTag("assistant_mode_selector").performClick()
        compose.onNodeWithTag("assistant_mode_sheet").assertIsDisplayed()
        compose.onNodeWithTag("skill_option_alpine_linux").performScrollTo().performClick()
        compose.onNodeWithTag("persona_option_expert_engineer").performScrollTo().performClick()
        compose.onNodeWithTag("assistant_mode_default_toggle").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            val active = ConversationStore(context).load().conversations.singleOrNull()
            active?.selectedSkillId == "alpine_linux" &&
                active.selectedPersonaId == "expert_engineer"
        }
        assertEquals("alpine_linux", AssistantDefaultsStore(context).load().skillId)
        assertEquals("expert_engineer", AssistantDefaultsStore(context).load().personaId)

        val oldActivity = checkNotNull(currentActivity)
        instrumentation.runOnMainSync { oldActivity.finish() }
        instrumentation.waitForIdleSync()
        currentActivity = null
        clearConversations()
        launch(MainActivity::class.java)

        compose.onNodeWithText("Alpine/Linux expert · Expert engineer").assertIsDisplayed()
    }

    @Test
    fun malformedAssistantDefaultsFailSafeWithoutBlockingLaunch() {
        context.getSharedPreferences(AssistantDefaultsStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("skill_id", 7)
            .putString("persona_id", "removed_persona")
            .commit()

        launch(MainActivity::class.java)

        compose.onNodeWithText("General assistant · Balanced").assertIsDisplayed()
        assertEquals("general", AssistantDefaultsStore(context).load().skillId)
        assertEquals("balanced", AssistantDefaultsStore(context).load().personaId)
    }

    @Test
    fun geminiFormShowsProjectFieldAndSavesValidatedProfile() {
        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.GEMINI.wireName,
                ),
        )

        compose.onNodeWithTag("provider_edit_screen").assertIsDisplayed()
        scrollToFormTag("google_project")
        compose.onNodeWithTag("google_project").assertExists()
        compose.onNodeWithTag("anthropic_beta").assertDoesNotExist()
        scrollToFormTag("inference_endpoint")
        assertEditableTextContains("inference_endpoint", "{model}")

        scrollToFormTag("client_id")
        compose.onNodeWithTag("client_id").performTextInput("android-public-client")
        scrollToFormTag("model")
        assertEditableTextContains("model", GeminiProfileDefaults.DEFAULT_MODEL)
        compose.onNodeWithTag("model").performClick()
        compose.onNodeWithTag("model_option_gemini-3.5-flash").performClick()
        scrollToFormTag("google_project")
        compose.onNodeWithTag("google_project").performTextInput("test-project")
        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.GEMINI, saved.type)
        assertEquals("gemini-3.5-flash", saved.model)
        assertTrue(saved.validationErrors().isEmpty())
        val storedRaw = context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).all.toString()
        assertFalse(storedRaw.contains("access_token"))
        assertFalse(storedRaw.contains("refresh_token"))
        assertFalse(storedRaw.contains("client_secret"))
    }

    @Test
    fun eachProviderFormExposesOnlySafeOwnerConfigurationFields() {
        val expectations = listOf(
            ProviderType.ANTHROPIC to false,
            ProviderType.GEMINI to true,
            ProviderType.OPENAI_COMPATIBLE to false,
            ProviderType.CODEX to false,
            ProviderType.XAI to false,
        )

        expectations.forEach { (type, hasGoogle) ->
            val activity = launch(
                ProviderEditActivity::class.java,
                Intent(context, ProviderEditActivity::class.java)
                    .putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, type.wireName),
            )

            compose.onNodeWithTag("provider_type").assertIsDisplayed()
            scrollToFormTag("inference_endpoint")
            assertEditableTextContains(
                tag = "inference_endpoint",
                expected = type.inferenceEndpointPlaceholder,
            )
            compose.onNodeWithTag("anthropic_beta").assertDoesNotExist()
            if (hasGoogle) {
                scrollToFormTag("google_project")
                compose.onNodeWithTag("google_project").assertExists()
            } else {
                compose.onNodeWithTag("google_project").assertDoesNotExist()
            }

            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            currentActivity = null
        }
    }

    @Test
    fun codexFormRequiresCompleteHostOwnedConfigurationBeforeSave() {
        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.CODEX.wireName,
                ),
        )

        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        scrollToFormTag("authorization_endpoint")
        compose.onNodeWithText("Authorization endpoint 값을 입력하세요.").assertIsDisplayed()
        compose.onNodeWithTag("authorization_endpoint")
            .performTextInput("https://identity.example.test/oauth/authorize")
        scrollToFormTag("token_endpoint")
        compose.onNodeWithTag("token_endpoint")
            .performTextInput("https://identity.example.test/oauth/token")
        scrollToFormTag("client_id")
        compose.onNodeWithTag("client_id").performTextInput("host-owned-codex-client")
        scrollToFormTag("scopes")
        compose.onNodeWithTag("scopes").performTextInput("openid profile offline_access")
        scrollToFormTag("model")
        compose.onNodeWithTag("model").performTextInput("owner-approved-model")

        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.CODEX, saved.type)
        assertEquals("owner-approved-model", saved.model)
        assertTrue(saved.validationErrors().isEmpty())
    }

    @Test
    fun providerDraftSurvivesRecreationAndProtectsDirtyBackNavigation() {
        val oldActivity = launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.OPENAI_COMPATIBLE.wireName,
                ),
        )

        scrollToFormTag("profile_label")
        compose.onNodeWithTag("profile_label").performTextClearance()
        compose.onNodeWithTag("profile_label").performTextInput("Unsaved lifecycle profile")

        // adjustResize must keep the sticky action reachable while the IME is open.
        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        scrollToFormTag("client_id")
        compose.onNodeWithText("OAuth Public Client ID를 입력하세요.").assertIsDisplayed()
        hideKeyboard()
        scrollToFormTag("protocol_details_toggle")
        compose.onNodeWithTag("protocol_details_toggle").performClick()
        compose.onNodeWithText("보기").assertIsDisplayed()

        instrumentation.runOnMainSync { oldActivity.recreate() }
        compose.waitUntil(timeoutMillis = 5_000) { oldActivity.isDestroyed }
        instrumentation.runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ProviderEditActivity>()
                .single()
        }

        scrollToFormTag("profile_label")
        assertEditableTextContains("profile_label", "Unsaved lifecycle profile")
        scrollToFormTag("client_id")
        compose.onNodeWithText("OAuth Public Client ID를 입력하세요.").assertIsDisplayed()
        scrollToFormTag("protocol_details_toggle")
        compose.onNodeWithText("보기").assertIsDisplayed()

        compose.onNode(hasContentDescription("뒤로")).performClick()
        compose.onNodeWithText("변경사항을 버릴까요?").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        scrollToFormTag("profile_label")
        assertEditableTextContains("profile_label", "Unsaved lifecycle profile")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("변경사항을 버릴까요?").assertIsDisplayed()
        compose.onNodeWithText("버리고 나가기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true || currentActivity?.isDestroyed == true
        }
        assertTrue(ProviderProfileStore(context).load().isEmpty())
    }

    @Test
    fun profileStoreSupportsCrudLabelsAndMalformedDataRecovery() {
        val first = testProfile(
            id = "crud-first",
            label = ProviderType.ANTHROPIC.displayName,
            type = ProviderType.ANTHROPIC,
            model = "claude-test",
            createdAtMs = 1,
        )
        val second = testProfile(
            id = "crud-second",
            label = "${ProviderType.ANTHROPIC.displayName} 2",
            type = ProviderType.ANTHROPIC,
            model = "claude-test-2",
            createdAtMs = 2,
        )
        val store = ProviderProfileStore(context)

        store.upsert(first)
        assertEquals("${ProviderType.ANTHROPIC.displayName} 2", store.nextLabel(ProviderType.ANTHROPIC))
        store.upsert(second)
        store.upsert(first.copy(label = "Renamed Claude"))
        assertEquals("Renamed Claude", store.find(first.id)?.label)
        assertEquals(2, store.load().size)

        store.delete(second.id)
        assertEquals(listOf(first.id), store.load().map { it.id })

        context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().putString("profiles", "{malformed-json").commit()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun logoutAndDeleteRemainScopedToTheirProviderProfile() {
        val first = testProfile(
            id = "logout-first",
            label = "Claude Personal",
            type = ProviderType.ANTHROPIC,
            model = "claude-test",
            createdAtMs = 1,
        )
        val second = testProfile(
            id = "delete-second",
            label = "Gemini Work",
            type = ProviderType.GEMINI,
            model = "gemini-test",
            createdAtMs = 2,
        )
        val store = ProviderProfileStore(context)
        listOf(first, second).forEach(store::upsert)
        val scenario = FakeProviderScenario().apply {
            respond(first.id, "unused")
            respond(second.id, "unused")
            requireReauthentication(second.id)
        }
        ProviderDependencies.installSessionFactoryForTests { _, profile -> scenario.create(profile) }

        launch(ProviderProfilesActivity::class.java)
        compose.onNode(
            hasText("로그아웃") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.logoutCount(first.id) == 1
        }
        compose.onNode(
            hasText("연결 안 됨") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).assertExists()
        assertEquals(0, scenario.logoutCount(second.id))

        compose.onNode(
            hasText("로그인") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.authorizeCount(first.id) == 1
        }
        compose.onNode(
            hasText("연결됨") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).assertExists()

        compose.onNode(
            hasText("다시 로그인") and hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.authorizeCount(second.id) == 1
        }
        compose.onNode(
            hasText("연결됨") and hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).assertExists()

        compose.onNode(
            hasContentDescription("${second.label} 작업 메뉴") and
                hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("삭제").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            store.find(second.id) == null
        }

        compose.onNodeWithTag("profile_card_${second.id}").assertDoesNotExist()
        assertEquals(1, scenario.logoutCount(first.id))
        assertEquals(1, scenario.logoutCount(second.id))
    }

    @Test
    fun authorizationActivityRecreationRequiresOneExplicitRestartAndNeverAutoResumes() {
        val profile = testProfile(
            id = "oauth-recreation-profile",
            label = "Lifecycle Login",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "lifecycle-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val authorizeCount = AtomicInteger()
        val session = object : ChatCompletionSession {
            override val profile = profile

            override fun authenticationState(): OAuthAuthenticationState =
                OAuthAuthenticationState.SignedOut

            override suspend fun authorize(activity: Activity) {
                authorizeCount.incrementAndGet()
                awaitCancellation()
            }

            override suspend fun stream(requestJson: String): ChatBackendStreamResult =
                ChatBackendStreamResult()

            override fun logout() = Unit

            override fun cancelAuthorization() = Unit
        }
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        val oldActivity = launch(ProviderProfilesActivity::class.java)
        compose.onNode(
            hasText("로그인") and hasAnyAncestor(hasTestTag("profile_card_${profile.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { authorizeCount.get() == 1 }
        compose.onNodeWithTag("authorization_progress_${profile.id}").assertIsDisplayed()

        instrumentation.runOnMainSync { oldActivity.recreate() }
        compose.waitUntil(timeoutMillis = 5_000) { oldActivity.isDestroyed }
        updateCurrentProviderActivity()

        compose.onNodeWithText("오류 · AUTH_FLOW_INTERRUPTED").assertIsDisplayed()
        compose.onNodeWithText(
            "로그인 도중 앱 실행이 중단되었습니다.\n" +
                "기존 로그인 창을 닫고 처음부터 다시 로그인하세요.",
        ).assertIsDisplayed()
        assertEquals(1, authorizeCount.get())

        compose.onNode(
            hasText("로그인") and hasAnyAncestor(hasTestTag("profile_card_${profile.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { authorizeCount.get() == 2 }
        compose.onNodeWithTag("cancel_authorization_${profile.id}").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            authorizationRecoveryPreferences().all.isEmpty()
        }

        val restartedActivity = checkNotNull(currentActivity)
        instrumentation.runOnMainSync { restartedActivity.recreate() }
        compose.waitUntil(timeoutMillis = 5_000) { restartedActivity.isDestroyed }
        updateCurrentProviderActivity()
        compose.onNodeWithText("오류 · AUTH_FLOW_INTERRUPTED").assertDoesNotExist()
        assertEquals(2, authorizeCount.get())
    }

    @Test
    fun orphanedEncryptedTransactionIsDiscardedAndShownAsRedactedInterruption() {
        val profile = testProfile(
            id = "orphaned-oauth-profile",
            label = "Orphaned Login",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "orphan-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "unused")
            startSignedOut(profile.id)
        }
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }
        oauthTokenIds += profile.id
        OAuthTokenStore(context).saveTransaction(
            profile.id,
            OAuthTokenStore.Transaction(
                state = "must-not-be-rendered-state",
                verifier = "must-not-be-rendered-verifier",
                createdAtMs = System.currentTimeMillis(),
            ),
        )

        launch(ProviderProfilesActivity::class.java)

        compose.onNodeWithText("오류 · AUTH_FLOW_INTERRUPTED").assertIsDisplayed()
        compose.onAllNodesWithText("must-not-be-rendered", substring = true).assertCountEquals(0)
        assertFalse(OAuthTokenStore(context).hasTransaction(profile.id))
        assertTrue(authorizationRecoveryPreferences().all.isNotEmpty())
        assertEquals(0, scenario.authorizeCount(profile.id))
    }

    @Test
    fun storedSuccessfulAuthenticationWinsOverStaleLifecycleMarker() {
        val profile = testProfile(
            id = "completed-oauth-profile",
            label = "Completed Login",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "completed-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply { respond(profile.id, "unused") }
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }
        val token = OAuthTokenStore.Token(
            accessToken = "successful-access-token",
            refreshToken = "successful-refresh-token",
        )
        oauthTokenIds += profile.id
        OAuthTokenStore(context).apply {
            save(profile.id, token)
            saveTransaction(
                profile.id,
                OAuthTokenStore.Transaction(
                    state = "stale-state",
                    verifier = "stale-verifier",
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
        ProviderAuthorizationRecoveryStore(context).begin(profile.id)

        launch(ProviderProfilesActivity::class.java)

        compose.onNodeWithText("연결됨").assertIsDisplayed()
        compose.onNodeWithText("오류 · AUTH_FLOW_INTERRUPTED").assertDoesNotExist()
        assertEquals(token, OAuthTokenStore(context).load(profile.id))
        assertFalse(OAuthTokenStore(context).hasTransaction(profile.id))
        assertTrue(authorizationRecoveryPreferences().all.isEmpty())
        assertEquals(0, scenario.authorizeCount(profile.id))
    }

    @Test
    fun oauthIdentityEditInvalidatesOnlyItsProfileTokenNamespace() {
        val first = testProfile(
            id = "token-first",
            label = "Compatible One",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "model-one",
            createdAtMs = 1,
        )
        val second = testProfile(
            id = "token-second",
            label = "Compatible Two",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "model-two",
            createdAtMs = 2,
        )
        val profileStore = ProviderProfileStore(context)
        listOf(first, second).forEach(profileStore::upsert)

        val firstToken = OAuthTokenStore.Token(
            accessToken = "test-only-first-access",
            refreshToken = "test-only-first-refresh",
        )
        val secondToken = OAuthTokenStore.Token(
            accessToken = "test-only-second-access",
            refreshToken = "test-only-second-refresh",
        )
        val tokenStore = OAuthTokenStore(context)
        oauthTokenIds += first.id
        oauthTokenIds += second.id
        tokenStore.save(first.id, firstToken)
        tokenStore.save(second.id, secondToken)

        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_PROFILE_ID, first.id),
        )
        scrollToFormTag("client_id")
        compose.onNodeWithTag("client_id")
            .performTextClearance()
        compose.onNodeWithTag("client_id")
            .performTextInput("replacement-public-client")
        compose.onNodeWithTag("save_for_later").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        assertNull(tokenStore.load(first.id))
        assertEquals(secondToken, tokenStore.load(second.id))
        assertEquals(
            "replacement-public-client",
            profileStore.find(first.id)?.clientId,
        )
    }

    @Test
    fun selectedProviderAloneHandlesEachChatRequest() {
        val anthropic = testProfile(
            id = "anthropic-profile",
            label = "Claude Personal",
            type = ProviderType.ANTHROPIC,
            model = "claude-test",
            createdAtMs = 1,
        )
        val gemini = testProfile(
            id = "gemini-profile",
            label = "Gemini Work",
            type = ProviderType.GEMINI,
            model = "gemini-test",
            createdAtMs = 2,
        )
        val compatible = testProfile(
            id = "openai-profile",
            label = "Compatible Lab",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "openai-test",
            createdAtMs = 3,
        )
        val store = ProviderProfileStore(context)
        listOf(anthropic, gemini, compatible).forEach(store::upsert)

        val scenario = FakeProviderScenario().apply {
            respond(anthropic.id, "Claude fixture answer")
            respond(gemini.id, "Gemini fixture answer")
            respond(compatible.id, "Compatible fixture answer")
        }
        ProviderDependencies.installSessionFactoryForTests { _, profile ->
            scenario.create(profile)
        }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("provider_selector").performClick()
        compose.onNodeWithText("Gemini Work").performClick()
        compose.onNodeWithTag("message_input").performTextInput("Use Gemini")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Gemini fixture answer")
        assertMessageSides("Use Gemini", "Gemini fixture answer")

        assertEquals(0, scenario.requestCount(anthropic.id))
        assertEquals(1, scenario.requestCount(gemini.id))
        assertEquals(0, scenario.requestCount(compatible.id))

        compose.onNodeWithTag("provider_selector").performClick()
        compose.onNodeWithText("Claude Personal").performClick()
        compose.onNodeWithTag("message_input").performTextInput("Use Claude")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Claude fixture answer")

        assertEquals(1, scenario.requestCount(anthropic.id))
        assertEquals(1, scenario.requestCount(gemini.id))
        assertEquals(0, scenario.requestCount(compatible.id))
        compose.onNodeWithText("Claude Personal · claude-test", substring = true).assertExists()
        compose.onNodeWithText("Gemini Work · gemini-test", substring = true).assertExists()
    }

    @Test
    fun assistantMarkdownRendersNativeTextAndCodeBlock() {
        val profile = testProfile(
            id = "markdown-profile",
            label = "Markdown LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "markdown-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(
                profile.id,
                "## Result\n\n**Bold** answer.\n\n" +
                    "| Item | Value |\n| --- | --- |\n| Alpine | 3.20 |\n\n" +
                    "[Official docs](https://alpinelinux.org)\n\n" +
                    "[unsafe](javascript:alert(1))\n\n" +
                    "```kotlin\nval answer = 42\n```",
            )
        }
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Render this response")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Bold answer.")

        compose.onNodeWithText("Result").assertExists()
        compose.onNodeWithText("**Bold** answer.").assertDoesNotExist()
        compose.onNodeWithTag("message_code_block", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertExists()
        compose.onNodeWithTag("message_markdown_table", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Alpine").assertExists()
        compose.onNodeWithText("[unsafe](javascript:alert(1))", substring = true).assertExists()
        compose.onNodeWithText("Official docs", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("markdown_link_dialog").assertIsDisplayed()
        compose.onNodeWithText("https://alpinelinux.org").assertIsDisplayed()
        compose.onNodeWithTag("markdown_link_cancel").performClick()
        compose.onNodeWithTag("markdown_link_dialog").assertDoesNotExist()
        assertEquals(1, scenario.requestCount(profile.id))
    }

    @Test
    fun measurableResponseConstraintCorrectsAtMostOnce() {
        val profile = testProfile(
            id = "constraint-profile",
            label = "Constraint LLM",
            type = ProviderType.CODEX,
            model = "constraint-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.constraintCorrect(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Reply in under five words.")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Done now")

        assertEquals(2, session.requestCount)
        compose.onNodeWithText("one two three four five six").assertDoesNotExist()
        compose.onNodeWithText("Response corrected to match the requested format.")
            .assertIsDisplayed()
    }

    @Test
    fun unsupportedWebVerificationClaimCorrectsAtMostOnce() {
        val profile = testProfile(
            id = "freshness-profile",
            label = "Freshness LLM",
            type = ProviderType.CODEX,
            model = "freshness-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.freshnessCorrect(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input")
            .performTextInput("Search the web and verify today's value.")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText(
            "I cannot access live web data here, so I cannot verify today's value.",
        )

        assertEquals(2, session.requestCount)
        compose.onNodeWithText("I checked the web today", substring = true).assertDoesNotExist()
        compose.onNodeWithText(
            "Response corrected to remove an unsupported verification claim.",
        ).assertIsDisplayed()
    }

    @Test
    fun verifiedGeminiModelCanBeSwitchedAndPersistedDirectlyFromChat() {
        val profile = testProfile(
            id = "gemini-quick-switch",
            label = "Gemini",
            type = ProviderType.GEMINI,
            model = "gemini-3.6-flash",
            createdAtMs = 1,
        )
        val store = ProviderProfileStore(context)
        store.upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "Quick model answer")
        }
        ProviderDependencies.installSessionFactoryForTests { _, refreshedProfile ->
            scenario.create(refreshedProfile)
        }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("model_quick_switcher").assertIsDisplayed()
        compose.onNodeWithTag("model_quick_switcher")
            .performScrollToNode(hasTestTag("quick_model_gemini-3.5-flash"))
        compose.onNodeWithTag("quick_model_gemini-3.5-flash").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            store.find(profile.id)?.model == "gemini-3.5-flash"
        }
        compose.onNodeWithTag("quick_model_gemini-3.5-flash").assertIsSelected()

        compose.onNodeWithTag("message_input").performTextInput("Use the faster model")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Quick model answer")

        assertEquals(1, scenario.requestCount(profile.id))
        compose.onNodeWithText("Gemini · gemini-3.5-flash", substring = true).assertExists()
    }

    @Test
    fun activeStreamKeepsNavigationSelectionAndNextDraftUsable() {
        val profile = testProfile(
            id = "slow-profile",
            label = "Slow LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "slow-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.slow(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Start slow stream")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Slow partial answer")

        compose.onNodeWithTag("stop_button").assertIsDisplayed()
        compose.onNodeWithTag("provider_selector").assertIsEnabled()
        compose.onNodeWithTag("new_chat").assertIsEnabled()
        compose.onNodeWithTag("manage_providers").assertIsEnabled()
        compose.onNodeWithTag("message_input").assertIsEnabled()
        compose.onNodeWithTag("message_input").performTextInput("Draft next request")
        hideKeyboard()

        compose.onNodeWithTag("assistant_mode_selector").performClick()
        compose.onNodeWithText(
            "변경 내용은 다음 메시지부터 적용되며 현재 답변은 그대로 계속됩니다.",
        ).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        compose.onNodeWithTag("stop_button").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("send_button").assertExists()
        compose.onNodeWithTag("send_button").assertIsEnabled()
        compose.onNodeWithTag("message_input").assertIsEnabled()
        assertEditableTextContains("message_input", "Draft next request")
        assertEquals(1, session.requestCount)
    }

    @Test
    fun backgroundGenerationCanBeStoppedFromHistoryWithoutLosingTheNewDraft() {
        val profile = testProfile(
            id = "background-stop-profile",
            label = "Background LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "background-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.slow(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Start background stream")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Slow partial answer")

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithTag("background_generation_count").assertIsDisplayed()
        compose.onNodeWithText("다른 대화 1개 생성 중").assertIsDisplayed()
        compose.onNodeWithTag("message_input").performTextInput("Draft survives background stop")
        hideKeyboard()

        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNodeWithTag("conversation_history_list").assertIsDisplayed()
        compose.onNodeWithText("생성 중").assertIsDisplayed()
        compose.onNode(hasContentDescription("Start background stream 답변 생성 중지"))
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("중지됨").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("conversation_history_list").assertIsDisplayed()

        compose.onNode(
            hasText("Slow partial answer") and
                hasAnyAncestor(hasTestTag("conversation_history_list")),
        ).performClick()
        compose.onNodeWithText("Slow partial answer", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNode(
            hasText("새 대화") and
                hasAnyAncestor(hasTestTag("conversation_history_list")),
        ).performClick()
        assertEditableTextContains("message_input", "Draft survives background stop")
        assertEquals(1, session.requestCount)
    }

    @Test
    fun activeStreamSurvivesProviderManagementRoundTripAndCanStillBeStopped() {
        val profile = testProfile(
            id = "background-activity-profile",
            label = "Lifecycle LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "lifecycle-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.slow(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Keep generating in background")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Slow partial answer")

        compose.onNodeWithTag("manage_providers").performClick()
        compose.onNodeWithTag("provider_list_screen").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("chat_screen")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("stop_button").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, session.requestCount)
    }

    @Test
    fun activeStreamSurvivesActivityRecreationWithoutDuplicateDispatch() {
        val profile = testProfile(
            id = "stream-recreation-profile",
            label = "Recreation LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "recreation-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.slow(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        val oldActivity = launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Keep stream across recreation")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Slow partial answer")

        instrumentation.runOnMainSync { oldActivity.recreate() }
        compose.waitUntil(timeoutMillis = 5_000) { oldActivity.isDestroyed }
        instrumentation.runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .single()
        }

        compose.onNodeWithTag("chat_screen").assertIsDisplayed()
        compose.onNodeWithText("Slow partial answer", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("stop_button").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, session.requestCount)
    }

    @Test
    fun failedPartialResponseRetriesWithoutDuplicatingTheUserMessage() {
        val profile = testProfile(
            id = "retry-profile",
            label = "Retry LLM",
            type = ProviderType.GEMINI,
            model = "retry-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = ScriptedChatCompletionSession.failThenRecover(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Retry this request")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("retry_button").assertIsDisplayed().performClick()
        waitForText("Recovered answer")

        assertEquals(2, session.requestCount)
        compose.onAllNodes(
            hasText("Retry this request") and
                hasAnyAncestor(hasTestTag("messages_list")),
        ).assertCountEquals(1)
        compose.onAllNodesWithText("Failed partial answer").assertCountEquals(0)
    }

    @Test
    fun http429ShowsRedactedBusyStateAndRetryRecovers() {
        assertInjectedFailureRecovers(
            profileId = "fault-429",
            expectedFailure = "The provider is busy. Try again shortly.",
            sessionFactory = ScriptedChatCompletionSession::overloadedThenRecover,
        )
    }

    @Test
    fun http503ShowsRedactedUnavailableStateAndRetryRecovers() {
        assertInjectedFailureRecovers(
            profileId = "fault-503",
            expectedFailure = "The provider is temporarily unavailable.",
            sessionFactory = ScriptedChatCompletionSession::unavailableThenRecover,
        )
    }

    @Test
    fun malformedStreamShowsRedactedInvalidResponseAndRetryRecovers() {
        assertInjectedFailureRecovers(
            profileId = "fault-malformed",
            expectedFailure = "The provider returned an unreadable response.",
            sessionFactory = ScriptedChatCompletionSession::malformedThenRecover,
        )
    }

    @Test
    fun interruptedStreamShowsRedactedNetworkStateAndRetryRecovers() {
        assertInjectedFailureRecovers(
            profileId = "fault-interrupted",
            expectedFailure = "The provider connection was interrupted.",
            sessionFactory = ScriptedChatCompletionSession::interruptedThenRecover,
        )
    }

    @Test
    fun timeoutShowsRedactedTimeoutStateAndRetryRecovers() {
        assertInjectedFailureRecovers(
            profileId = "fault-timeout",
            expectedFailure = "The provider took too long to respond.",
            sessionFactory = ScriptedChatCompletionSession::timeoutThenRecover,
        )
    }

    @Test
    fun chatAndSelectedProviderSurviveActivityRecreation() {
        val profile = testProfile(
            id = "rotation-profile",
            label = "Rotation LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "rotation-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "Rotation-safe answer")
        }
        ProviderDependencies.installSessionFactoryForTests { _, selected ->
            scenario.create(selected)
        }

        val oldActivity = launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Keep this conversation")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Rotation-safe answer")

        instrumentation.runOnMainSync { oldActivity.recreate() }
        compose.waitUntil(timeoutMillis = 5_000) { oldActivity.isDestroyed }
        instrumentation.runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .single()
        }

        compose.onNodeWithTag("chat_screen").assertIsDisplayed()
        compose.onNodeWithText("Rotation-safe answer").assertExists()
        compose.onNodeWithText("Rotation LLM").assertExists()
        assertEquals(1, scenario.requestCount(profile.id))
    }

    @Test
    fun newChatPreservesHistoryAndSavedConversationReturnsAfterRelaunch() {
        val profile = testProfile(
            id = "history-profile",
            label = "History LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "history-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "Saved history answer")
        }
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("First saved topic")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Saved history answer")

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithText("대화를 삭제할까요?").assertDoesNotExist()
        compose.onNodeWithTag("message_input").performTextInput("Second saved topic")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Saved history answer")

        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNodeWithTag("conversation_history_list").assertIsDisplayed()
        compose.onNode(
            hasText("First saved topic") and
                hasAnyAncestor(hasTestTag("conversation_history_list")),
        ).assertExists()
        compose.onNode(
            hasText("Second saved topic") and
                hasAnyAncestor(hasTestTag("conversation_history_list")),
        ).assertExists()
        compose.onNode(
            hasText("First saved topic") and
                hasAnyAncestor(hasTestTag("conversation_history_list")),
        ).performClick()
        compose.onAllNodes(
            hasText("First saved topic") and
                hasAnyAncestor(hasTestTag("messages_list")),
        ).assertCountEquals(1)

        val oldActivity = checkNotNull(currentActivity)
        instrumentation.runOnMainSync { oldActivity.finish() }
        instrumentation.waitForIdleSync()
        currentActivity = null
        launch(MainActivity::class.java)
        compose.onNode(
            hasText("First saved topic") and
                hasAnyAncestor(hasTestTag("messages_list")),
        ).assertExists()
        compose.onNode(
            hasText("Saved history answer") and
                hasAnyAncestor(hasTestTag("messages_list")),
        ).assertExists()
        assertEquals(2, scenario.requestCount(profile.id))
    }

    @Test
    fun conversationCanBeRenamedAndDeleteRequiresConfirmation() {
        val profile = testProfile(
            id = "history-actions-profile",
            label = "History Actions LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "history-actions-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "History actions answer")
        }
        ProviderDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Rename target")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("History actions answer")
        val store = ConversationStore(context)
        compose.waitUntil(timeoutMillis = 5_000) {
            store.load().conversations.any { it.title == "Rename target" }
        }
        val conversationId = store.load().conversations.single { it.title == "Rename target" }.id

        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNode(
            hasContentDescription("대화 작업", substring = true) and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("이름 변경").performClick()
        compose.onNodeWithTag("rename_conversation_input").performTextClearance()
        compose.onNodeWithTag("rename_conversation_input").performTextInput("Renamed local chat")
        compose.onNodeWithTag("confirm_rename_conversation").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(
                hasText("Renamed local chat") and
                    hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(
            hasText("Renamed local chat") and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
            useUnmergedTree = true,
        ).assertExists()

        compose.onNode(
            hasContentDescription("대화 작업", substring = true) and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("대화를 삭제할까요?").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("conversation_item_$conversationId").assertExists()

        compose.onNode(
            hasContentDescription("대화 작업", substring = true) and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithTag("confirm_delete_conversation").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("conversation_item_$conversationId"))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("conversation_item_$conversationId").assertDoesNotExist()
    }

    @Test
    fun conversationStoreEncryptsPayloadAndIsolatesOneCorruptFile() {
        val store = ConversationStore(context)
        val first = ChatConversation(
            id = "encrypted-first",
            title = "Secret first title",
            messages = listOf(
                ChatMessage(id = "encrypted-message-1", role = ChatRole.USER, text = "Secret body"),
            ),
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val second = ChatConversation(
            id = "encrypted-second",
            title = "Second title",
            createdAtMs = 3,
            updatedAtMs = 4,
        )
        store.writeConversation(first)
        store.writeConversation(second)
        store.writeIndex(ConversationIndex(first.id, listOf(first.summary(), second.summary())))

        val raw = store.storageDirectoryForTests().listFiles().orEmpty()
            .flatMap { it.readBytes().asIterable() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        assertFalse(raw.contains("Secret first title"))
        assertFalse(raw.contains("Secret body"))
        assertEquals(2, store.load().conversations.size)

        val firstFile = store.storageDirectoryForTests().listFiles().orEmpty()
            .first { it.name.contains(first.id) }
        firstFile.writeBytes(byteArrayOf(1, 2, 3))
        val recovered = store.load()
        assertEquals(listOf(second.id), recovered.conversations.map { it.id })
        assertTrue(recovered.failedFileCount >= 1)
    }

    @Test
    fun invalidatedConversationKeyShowsOnlySafeRecoveryWarning() {
        val store = ConversationStore(context)
        val conversation = ChatConversation(
            id = "key-invalidated",
            title = "Never expose this title",
            messages = listOf(
                ChatMessage(id = "key-message", role = ChatRole.USER, text = "Never expose body"),
            ),
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        store.writeConversation(conversation)
        store.writeIndex(ConversationIndex(conversation.id, listOf(conversation.summary())))
        ConversationCrypto.deleteKeyForTests()

        launch(MainActivity::class.java)
        compose.onNodeWithText("Some saved conversations could not be restored.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("Never expose this title").assertCountEquals(0)
        compose.onAllNodesWithText("Never expose body").assertCountEquals(0)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(
                hasText(text) and hasAnyAncestor(hasTestTag("messages_list")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("messages_list").performScrollToNode(hasText(text))
        compose.onNode(
            hasText(text) and hasAnyAncestor(hasTestTag("messages_list")),
        ).assertIsDisplayed()
    }

    private fun assertInjectedFailureRecovers(
        profileId: String,
        expectedFailure: String,
        sessionFactory: (dev.alpine.chat.provider.android.model.ProviderProfile) ->
            ScriptedChatCompletionSession,
    ) {
        val profile = testProfile(
            id = profileId,
            label = "Fault injection LLM",
            type = ProviderType.OPENAI_COMPATIBLE,
            model = "fault-model",
            createdAtMs = 1,
        )
        ProviderProfileStore(context).upsert(profile)
        val session = sessionFactory(profile)
        ProviderDependencies.installSessionFactoryForTests { _, _ -> session }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Recover this request")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(expectedFailure).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(expectedFailure).assertIsDisplayed()
        compose.onAllNodesWithText("raw-secret", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("must-never-reach-the-ui", substring = true)
            .assertCountEquals(0)

        compose.onNodeWithTag("retry_button").assertIsDisplayed().performClick()
        waitForText("Recovered answer")

        assertEquals(2, session.requestCount)
        compose.onAllNodes(
            hasText("Recover this request") and
                hasAnyAncestor(hasTestTag("messages_list")),
        ).assertCountEquals(1)
    }

    private fun assertMessageSides(userText: String, assistantText: String) {
        val listCenterX = compose.onNodeWithTag("messages_list")
            .fetchSemanticsNode().boundsInRoot.center.x
        val userCenterX = compose.onNode(
            hasText(userText) and hasAnyAncestor(hasTestTag("messages_list")),
        ).fetchSemanticsNode().boundsInRoot.center.x
        val assistantCenterX = compose.onNode(
            hasText(assistantText) and hasAnyAncestor(hasTestTag("messages_list")),
        ).fetchSemanticsNode().boundsInRoot.center.x

        assertTrue("User message must be on the right", userCenterX > listCenterX)
        assertTrue("Assistant message must be on the left", assistantCenterX < listCenterX)
    }

    private fun assertEditableTextContains(tag: String, expected: String) {
        val actual = compose.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
        assertTrue("Expected editable text to contain $expected, got $actual", actual.contains(expected))
    }

    private fun scrollToFormTag(tag: String) {
        compose.onNodeWithTag("provider_edit_screen")
            .performScrollToNode(hasTestTag(tag))
    }

    private fun hideKeyboard() {
        instrumentation.runOnMainSync {
            currentActivity?.let { activity ->
                val inputMethod = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                inputMethod.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun launch(
        activityClass: Class<out Activity>,
        intent: Intent = Intent(context, activityClass),
    ): Activity {
        val activity = instrumentation.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        currentActivity = activity
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
        return activity
    }

    private fun updateCurrentProviderActivity() {
        instrumentation.runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ProviderProfilesActivity>()
                .single()
        }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun clearProfiles() {
        context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    private fun authorizationRecoveryPreferences() = context.getSharedPreferences(
        ProviderAuthorizationRecoveryStore.FILE_NAME,
        Context.MODE_PRIVATE,
    )

    private fun clearAuthorizationRecovery() {
        authorizationRecoveryPreferences().edit().clear().commit()
    }

    private fun clearConversations() {
        ConversationStore(context).clear()
    }

    private fun clearAssistantDefaults() {
        AssistantDefaultsStore(context).clear()
    }
}
