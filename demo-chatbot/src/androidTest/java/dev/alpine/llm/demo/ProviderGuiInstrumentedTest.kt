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
import dev.alpine.llm.AnthropicOAuthContract
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.data.AssistantDefaultsStore
import dev.alpine.llm.demo.data.ConversationCrypto
import dev.alpine.llm.demo.data.ConversationIndex
import dev.alpine.llm.demo.data.ConversationStore
import dev.alpine.llm.demo.model.ChatConversation
import dev.alpine.llm.demo.model.ChatMessage
import dev.alpine.llm.demo.model.ChatRole
import dev.alpine.llm.demo.model.AnthropicProfileDefaults
import dev.alpine.llm.demo.model.CodexProfileDefaults
import dev.alpine.llm.demo.model.GeminiProfileDefaults
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.model.XaiProfileDefaults
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
        clearConversations()
        clearAssistantDefaults()
        DemoDependencies.installSessionFactoryForTests(null)
    }

    @After
    fun cleanupTestState() {
        DemoDependencies.installSessionFactoryForTests(null)
        currentActivity?.let { activity ->
            instrumentation.runOnMainSync { activity.finish() }
        }
        currentActivity = null
        val tokenStore = OAuthTokenStore(context)
        oauthTokenIds.forEach(tokenStore::delete)
        oauthTokenIds.clear()
        clearProfiles()
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
        compose.onAllNodesWithText("Connect an LLM to start", substring = true)
            .assertCountEquals(1)
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
        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performScrollTo().performClick()
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
    fun eachProviderFormShowsOnlyItsAdditionalField() {
        val expectations = listOf(
            Triple(ProviderType.ANTHROPIC, true, false),
            Triple(ProviderType.GEMINI, false, true),
            Triple(ProviderType.OPENAI_COMPATIBLE, false, false),
            Triple(ProviderType.CODEX, false, false),
            Triple(ProviderType.XAI, false, false),
        )

        expectations.forEach { (type, hasAnthropic, hasGoogle) ->
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
            if (hasAnthropic) {
                scrollToFormTag("anthropic_beta")
                compose.onNodeWithTag("anthropic_beta").assertExists()
            } else {
                compose.onNodeWithTag("anthropic_beta").assertDoesNotExist()
            }
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
    fun codexFormPrefillsClientIdAndAllowsModelSelection() {
        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.CODEX.wireName,
                ),
        )

        scrollToFormTag("client_id")
        assertEditableTextContains("client_id", CodexProfileDefaults.PUBLIC_CLIENT_ID)
        scrollToFormTag("model")
        assertEditableTextContains("model", CodexProfileDefaults.DEFAULT_MODEL)
        compose.onNodeWithTag("model").performClick()
        compose.onNodeWithTag("model_option_gpt-5.6-sol").performClick()
        assertEditableTextContains("model", "gpt-5.6-sol")

        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(CodexProfileDefaults.PUBLIC_CLIENT_ID, saved.clientId)
        assertEquals("gpt-5.6-sol", saved.model)
    }

    @Test
    fun anthropicFormPrefillsReferenceContractAndAllowsModelSelection() {
        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.ANTHROPIC.wireName,
                ),
        )

        scrollToFormTag("authorization_endpoint")
        assertEditableTextContains(
            "authorization_endpoint",
            AnthropicOAuthContract.AUTHORIZATION_ENDPOINT,
        )
        scrollToFormTag("client_id")
        assertEditableTextContains("client_id", AnthropicOAuthContract.PUBLIC_CLIENT_ID)
        scrollToFormTag("callback_port")
        assertEditableTextContains("callback_port", "54545")
        scrollToFormTag("model")
        assertEditableTextContains("model", AnthropicProfileDefaults.DEFAULT_MODEL)
        compose.onNodeWithTag("model").performClick()
        compose.onNodeWithTag("model_option_claude-sonnet-4-6").performClick()
        scrollToFormTag("anthropic_beta")
        assertEditableTextContains("anthropic_beta", AnthropicOAuthContract.OAUTH_BETA)

        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.ANTHROPIC, saved.type)
        assertEquals("claude-sonnet-4-6", saved.model)
        assertTrue(saved.validationErrors().isEmpty())
    }

    @Test
    fun xaiFormPrefillsPublicClientAndAllowsGrokModelSelection() {
        launch(
            ProviderEditActivity::class.java,
            Intent(context, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROVIDER_TYPE,
                    ProviderType.XAI.wireName,
                ),
        )

        scrollToFormTag("client_id")
        assertEditableTextContains("client_id", XaiProfileDefaults.PUBLIC_CLIENT_ID)
        scrollToFormTag("callback_port")
        assertEditableTextContains("callback_port", "56121")
        scrollToFormTag("model")
        assertEditableTextContains("model", XaiProfileDefaults.DEFAULT_MODEL)
        compose.onNodeWithTag("model").performClick()
        compose.onNodeWithTag("model_option_grok-3-mini-fast").performClick()
        assertEditableTextContains("model", "grok-3-mini-fast")

        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.XAI, saved.type)
        assertEquals("grok-3-mini-fast", saved.model)
    }

    @Test
    fun profileStoreSupportsCrudLabelsAndMalformedDataRecovery() {
        val first = testProfile(
            id = "crud-first",
            label = "Claude",
            type = ProviderType.ANTHROPIC,
            model = "claude-test",
            createdAtMs = 1,
        )
        val second = testProfile(
            id = "crud-second",
            label = "Claude 2",
            type = ProviderType.ANTHROPIC,
            model = "claude-test-2",
            createdAtMs = 2,
        )
        val store = ProviderProfileStore(context)

        store.upsert(first)
        assertEquals("Claude 2", store.nextLabel(ProviderType.ANTHROPIC))
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
        DemoDependencies.installSessionFactoryForTests { _, profile -> scenario.create(profile) }

        launch(ProviderProfilesActivity::class.java)
        compose.onNode(
            hasText("Logout") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.logoutCount(first.id) == 1
        }
        compose.onNode(
            hasText("Not connected") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).assertExists()
        assertEquals(0, scenario.logoutCount(second.id))

        compose.onNode(
            hasText("Connect") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.authorizeCount(first.id) == 1
        }
        compose.onNode(
            hasText("Connected") and hasAnyAncestor(hasTestTag("profile_card_${first.id}")),
        ).assertExists()

        compose.onNode(
            hasText("Reconnect") and hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.authorizeCount(second.id) == 1
        }
        compose.onNode(
            hasText("Connected") and hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).assertExists()

        compose.onNode(
            hasContentDescription("Profile actions") and
                hasAnyAncestor(hasTestTag("profile_card_${second.id}")),
        ).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            store.find(second.id) == null
        }

        compose.onNodeWithTag("profile_card_${second.id}").assertDoesNotExist()
        assertEquals(1, scenario.logoutCount(first.id))
        assertEquals(1, scenario.logoutCount(second.id))
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
        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performClick()
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
        DemoDependencies.installSessionFactoryForTests { _, profile ->
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
        DemoDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("Render this response")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Bold answer.")

        compose.onNodeWithText("Result").assertExists()
        compose.onNodeWithText("**Bold** answer.").assertDoesNotExist()
        compose.onNodeWithTag("message_code_block").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertExists()
        compose.onNodeWithTag("message_markdown_table").assertIsDisplayed()
        compose.onNodeWithText("Alpine").assertExists()
        compose.onNodeWithText("[unsafe](javascript:alert(1))", substring = true).assertExists()
        compose.onNodeWithText("Official docs").performClick()
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
        DemoDependencies.installSessionFactoryForTests { _, _ -> session }

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
        DemoDependencies.installSessionFactoryForTests { _, _ -> session }

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
    fun codexModelCanBeSwitchedAndPersistedDirectlyFromChat() {
        val profile = testProfile(
            id = "codex-quick-switch",
            label = "Codex",
            type = ProviderType.CODEX,
            model = "gpt-5.6-luna",
            createdAtMs = 1,
        )
        val store = ProviderProfileStore(context)
        store.upsert(profile)
        val scenario = FakeProviderScenario().apply {
            respond(profile.id, "Quick model answer")
        }
        DemoDependencies.installSessionFactoryForTests { _, refreshedProfile ->
            scenario.create(refreshedProfile)
        }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("model_quick_switcher").assertIsDisplayed()
        compose.onNodeWithTag("model_quick_switcher")
            .performScrollToNode(hasTestTag("quick_model_gpt-5.4-mini"))
        compose.onNodeWithTag("quick_model_gpt-5.4-mini").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            store.find(profile.id)?.model == "gpt-5.4-mini"
        }
        compose.onNodeWithTag("quick_model_gpt-5.4-mini").assertIsSelected()

        compose.onNodeWithTag("message_input").performTextInput("Use the faster model")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Quick model answer")

        assertEquals(1, scenario.requestCount(profile.id))
        compose.onNodeWithText("Codex · gpt-5.4-mini", substring = true).assertExists()
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
        DemoDependencies.installSessionFactoryForTests { _, _ -> session }

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

        compose.onNodeWithTag("assistant_mode_selector").performClick()
        compose.onNodeWithText(
            "Changes apply to the next message. The current response continues unchanged.",
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
        DemoDependencies.installSessionFactoryForTests { _, _ -> session }

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
        DemoDependencies.installSessionFactoryForTests { _, selected ->
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
        DemoDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

        launch(MainActivity::class.java)
        compose.onNodeWithTag("message_input").performTextInput("First saved topic")
        compose.onNodeWithTag("send_button").performClick()
        hideKeyboard()
        waitForText("Saved history answer")

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithText("Delete conversation?").assertDoesNotExist()
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
        DemoDependencies.installSessionFactoryForTests { _, selected -> scenario.create(selected) }

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
            hasContentDescription("Conversation actions") and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("Rename").performClick()
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
            hasContentDescription("Conversation actions") and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete conversation?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("conversation_item_$conversationId").assertExists()

        compose.onNode(
            hasContentDescription("Conversation actions") and
                hasAnyAncestor(hasTestTag("conversation_item_$conversationId")),
        ).performClick()
        compose.onNodeWithText("Delete").performClick()
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
        sessionFactory: (dev.alpine.llm.demo.model.ProviderProfile) ->
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
        DemoDependencies.installSessionFactoryForTests { _, _ -> session }

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

    private fun clearProfiles() {
        context.getSharedPreferences(
            ProviderProfileStore.FILE_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    private fun clearConversations() {
        ConversationStore(context).clear()
    }

    private fun clearAssistantDefaults() {
        AssistantDefaultsStore(context).clear()
    }
}
