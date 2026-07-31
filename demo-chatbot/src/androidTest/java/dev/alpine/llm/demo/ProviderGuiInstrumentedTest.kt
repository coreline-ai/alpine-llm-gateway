package dev.alpine.llm.demo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.model.ProviderType
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
    }

    @Test
    fun mainScreenStartsWithProviderManagementAvailable() {
        launch(MainActivity::class.java)

        compose.onNodeWithTag("chat_screen").assertIsDisplayed()
        compose.onNodeWithTag("manage_providers").assertIsDisplayed()
        compose.onNodeWithTag("provider_selector").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsDisplayed()
        compose.onAllNodesWithText("Connect an LLM to start", substring = true)
            .assertCountEquals(1)
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

        scrollToFormTag("authorization_endpoint")
        compose.onNodeWithTag("authorization_endpoint")
            .performTextInput("https://identity.example.test/oauth/authorize")
        scrollToFormTag("token_endpoint")
        compose.onNodeWithTag("token_endpoint")
            .performTextInput("https://identity.example.test/oauth/token")
        scrollToFormTag("client_id")
        compose.onNodeWithTag("client_id").performTextInput("android-public-client")
        scrollToFormTag("model")
        compose.onNodeWithTag("model").performTextInput("gemini-test")
        scrollToFormTag("save_profile")
        compose.onNodeWithTag("save_profile").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            currentActivity?.isFinishing == true
        }

        val saved = ProviderProfileStore(context).load().single()
        assertEquals(ProviderType.GEMINI, saved.type)
        assertEquals("gemini-test", saved.model)
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
            label = "Claude One",
            type = ProviderType.ANTHROPIC,
            model = "claude-one",
            createdAtMs = 1,
        )
        val second = testProfile(
            id = "token-second",
            label = "Claude Two",
            type = ProviderType.ANTHROPIC,
            model = "claude-two",
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
        compose.onNodeWithText("Claude Personal · claude-test").assertExists()
        compose.onNodeWithText("Gemini Work · gemini-test").assertExists()
    }

    @Test
    fun activeStreamLocksMutationsAndStopRestoresChatActions() {
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
        compose.onNodeWithTag("provider_selector").assertIsNotEnabled()
        compose.onNodeWithTag("new_chat").assertIsNotEnabled()
        compose.onNodeWithTag("manage_providers").assertIsNotEnabled()
        compose.onNodeWithTag("message_input").assertIsNotEnabled()

        compose.onNodeWithTag("stop_button").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("send_button").assertExists()
        compose.onNodeWithTag("message_input").assertIsEnabled()
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
        compose.onAllNodesWithText("Retry this request").assertCountEquals(1)
        compose.onAllNodesWithText("Failed partial answer").assertCountEquals(0)
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

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("messages_list").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
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
}
