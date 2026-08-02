package dev.alpine.integrated

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.provider.android.ProviderDependencies
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.llm.OAuthAuthenticationState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
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
        ProviderDependencies.installSessionFactoryForTests(null)
    }

    @After
    fun cleanup() {
        ProviderDependencies.installSessionFactoryForTests(null)
        currentActivity?.let { activity ->
            instrumentation.runOnMainSync { activity.finish() }
        }
        currentActivity = null
        clearState()
    }

    @Test
    fun loginModelStreamModeNavigationStopAndRetryUseDirectProvider() {
        val profile = codexProfile("integrated-flow")
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
        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(5_000) { scenario.authorizeCount.get() == 1 }
        compose.onNode(hasContentDescription("Back")).performClick()

        compose.onNodeWithText(profile.label).assertIsDisplayed()
        compose.onNodeWithTag("quick_model_gpt-5.6-sol").performClick()
        compose.waitUntil(5_000) {
            ProviderProfileStore(context).find(profile.id)?.model == "gpt-5.6-sol"
        }

        compose.onNodeWithTag("message_input").performTextInput("first integrated request")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("stop_button").assertIsDisplayed()

        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("mode_fast_chat").performClick()
        compose.onNodeWithTag("stop_button").performClick()
        compose.onNodeWithText("Slow partial answer", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("message_input").performTextInput("retry integrated request")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("retry_button").assertIsDisplayed()
        compose.onNodeWithTag("retry_button").performClick()
        compose.onNodeWithText("Integrated recovered answer").assertIsDisplayed()

        assertEquals(3, scenario.requestCount.get())
    }

    @Test
    fun conversationAndPerConversationModeRestoreAfterActivityRestart() {
        val profile = codexProfile("integrated-restore")
        val scenario = IntegratedProviderScenario(startSignedOut = false, immediate = true)
        ProviderProfileStore(context).upsert(profile)
        ProviderDependencies.installSessionFactoryForTests { _, selected ->
            scenario.create(selected)
        }

        launch()
        compose.onNodeWithTag("message_input").performTextInput("persist integrated answer")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithText("Integrated recovered answer").assertIsDisplayed()

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithTag("message_input").performTextInput("second conversation draft")
        compose.onNodeWithTag("conversation_history").performClick()
        compose.onNodeWithText("persist integrated answer").performClick()
        compose.onNodeWithText("Integrated recovered answer").assertIsDisplayed()

        compose.onNodeWithTag("mode_alpine_workspace").performClick()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()

        closeCurrentActivity()
        launch()
        compose.onNodeWithTag("mode_alpine_workspace").assertIsSelected()
        compose.onNodeWithTag("mode_fast_chat").performClick()
        compose.onNodeWithText("Integrated recovered answer").assertIsDisplayed()
        assertEquals("gpt-5.6-luna", ProviderProfileStore(context).find(profile.id)?.model)
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

    private fun clearState() {
        context.getSharedPreferences(ProviderProfileStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ConversationStore(context).clear()
        AssistantDefaultsStore(context).clear()
    }

    private fun codexProfile(id: String): ProviderProfile =
        ProviderProfile.draft(ProviderType.CODEX, "Integrated Codex").copy(
            id = id,
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
