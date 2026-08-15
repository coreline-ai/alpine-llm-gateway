package dev.alpine.integrated

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.chat.backend.codex.AndroidCodexThreadLinkStore
import dev.alpine.chat.backend.codex.CodexAgentChatSession
import dev.alpine.chat.feature.backend.ChatBackendRequestContext
import dev.alpine.codex.appserver.CodexAccountState
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live account E2E. Prompts and response text are fixed, never printed, and never put in evidence. */
@RunWith(AndroidJUnit4::class)
class CodexLiveTurnInstrumentedTest {
    @Test
    fun officialAccountCompletesTwoTurnsWithoutFallbackOrReplay() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val application = context as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val client = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connect() }
        assertEquals(CodexAccountState.CHATGPT, client.accountState())
        val models = withTimeout(REQUEST_TIMEOUT_MS) { client.models() }
        val selected = models.firstOrNull { it.isDefault } ?: models.first()
        val links = AndroidCodexThreadLinkStore(context)
        links.remove(CONVERSATION_ID)
        val session = CodexAgentChatSession(
            descriptor = CodexAgentChatSession.descriptor(selected.id, models.map { it.id }),
            agent = client,
            links = links,
        )
        val missingTurnShapes = Collections.synchronizedSet(mutableSetOf<String>())
        val shapeObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.collect { notification ->
                val params = notification.params
                if (params.has("threadId") && !params.has("turnId") &&
                    params.optJSONObject("turn") == null
                ) {
                    missingTurnShapes += notification.method
                }
            }
        }

        val (first, second) = try {
            collectRedactedTurn(session, CONVERSATION_ID, "action-live-first", FIRST_PROMPT) to
                collectRedactedTurn(session, CONVERSATION_ID, "action-live-second", SECOND_PROMPT)
        } catch (failure: Throwable) {
            throw AssertionError(
                "turn correlation rejected safe methods=${missingTurnShapes.toList().sorted()}",
                failure,
            )
        } finally {
            shapeObserver.cancel()
        }

        assertTrue(first.chunks > 0 && first.utf8Bytes > 0)
        assertTrue(second.chunks > 0 && second.utf8Bytes > 0)
    }

    @Test
    fun stopInterruptsActiveTurnAndNextTurnDoesNotReplay() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val application = context as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val client = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connect() }
        assertEquals(CodexAccountState.CHATGPT, client.accountState())
        val models = withTimeout(REQUEST_TIMEOUT_MS) { client.models() }
        val selected = models.firstOrNull { it.isDefault } ?: models.first()
        val links = AndroidCodexThreadLinkStore(context)
        links.remove(STOP_CONVERSATION_ID)
        val session = CodexAgentChatSession(
            descriptor = CodexAgentChatSession.descriptor(selected.id, models.map { it.id }),
            agent = client,
            links = links,
        )
        val firstDelta = CompletableDeferred<Unit>()
        val active = launch {
            session.stream(
                request(
                    session = session,
                    conversationId = STOP_CONVERSATION_ID,
                    actionId = "action-live-stop",
                    prompt = STOP_PROMPT,
                ),
            ).events.collect {
                firstDelta.complete(Unit)
            }
        }
        withTimeout(FIRST_DELTA_TIMEOUT_MS) { firstDelta.await() }
        assertTrue("turn must still be active when Stop is issued", active.isActive)
        active.cancelAndJoin()

        val next = collectRedactedTurn(
            session,
            STOP_CONVERSATION_ID,
            "action-live-after-stop",
            AFTER_STOP_PROMPT,
        )
        assertTrue(next.chunks > 0 && next.utf8Bytes > 0)
    }

    @Test
    fun controlledRestartPreservesOfficialAccountAndModelList() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val before = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connect() }
        assertEquals(CodexAccountState.CHATGPT, before.accountState())
        assertTrue(before.models().isNotEmpty())

        val after = withTimeout(REQUEST_TIMEOUT_MS) { runtime.restart() }
        assertEquals(CodexAccountState.CHATGPT, after.accountState())
        assertTrue(after.models().isNotEmpty())
    }

    private suspend fun collectRedactedTurn(
        session: CodexAgentChatSession,
        conversationId: String,
        actionId: String,
        prompt: String,
    ): RedactedTurnResult {
        var chunks = 0
        var utf8Bytes = 0L
        withTimeout(TURN_TIMEOUT_MS) {
            session.stream(
                request(session, conversationId, actionId, prompt),
            ).events.collect { delta ->
                chunks += 1
                utf8Bytes += delta.text.toByteArray(Charsets.UTF_8).size
                check(utf8Bytes <= MAX_RESPONSE_BYTES)
            }
        }
        return RedactedTurnResult(chunks, utf8Bytes)
    }

    private fun request(
        session: CodexAgentChatSession,
        conversationId: String,
        actionId: String,
        prompt: String,
    ) = ChatBackendRequestContext(
        conversationId = conversationId,
        actionId = actionId,
        requestJson = JSONObject()
            .put("model", session.descriptor.model)
            .put("stream", true)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt),
                ),
            )
            .toString(),
    )

    private data class RedactedTurnResult(val chunks: Int, val utf8Bytes: Long)

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val TURN_TIMEOUT_MS = 180_000L
        const val FIRST_DELTA_TIMEOUT_MS = 120_000L
        const val MAX_RESPONSE_BYTES = 256L * 1024L
        const val CONVERSATION_ID = "codex-live-e2e-conversation"
        const val STOP_CONVERSATION_ID = "codex-live-stop-conversation"
        const val FIRST_PROMPT = "Reply with one short acknowledgement."
        const val SECOND_PROMPT = "Reply with a different short acknowledgement."
        const val STOP_PROMPT = "Write a long structured explanation with many short sections."
        const val AFTER_STOP_PROMPT = "Reply with one short acknowledgement after interruption."
    }
}
