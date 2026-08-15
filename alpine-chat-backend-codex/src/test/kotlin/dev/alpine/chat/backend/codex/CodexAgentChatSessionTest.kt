package dev.alpine.chat.backend.codex

import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import dev.alpine.chat.feature.backend.ChatBackendRequestContext
import dev.alpine.codex.appserver.CodexAgentApi
import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import dev.alpine.codex.appserver.protocol.CodexRpcNotification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexAgentChatSessionTest {
    @Test
    fun `streams stable delta and sends only current user prompt`() = runTest {
        val agent = FakeAgent().apply { script = Script.COMPLETE }
        val links = MemoryLinks()
        val session = session(agent, links)

        val events = session.stream(context("conversation-1", "current prompt"))
            .events
            .toList()

        assertEquals("hello", events.joinToString("") { it.text })
        assertEquals(listOf("current prompt"), agent.prompts)
        assertEquals("thread-1", links.get("conversation-1"))
        assertFalse(agent.resumed)
        assertEquals(0, agent.interruptCount)
    }

    @Test
    fun `existing thread must resume and resume failure never creates replacement`() = runTest {
        val agent = FakeAgent().apply { failResume = true }
        val links = MemoryLinks().apply { put("conversation-1", "thread-existing") }
        val session = session(agent, links)

        val failure = runCatching {
            session.stream(context("conversation-1", "next")).events.collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.THREAD_REATTACH_REQUIRED, failure.code)
        assertTrue(agent.resumed)
        assertEquals(0, agent.startThreadCount)
        assertEquals("thread-existing", links.get("conversation-1"))
    }

    @Test
    fun `command item fails closed and interrupts exactly once`() = runTest {
        val agent = FakeAgent().apply { script = Script.UNSAFE_COMMAND }
        val session = session(agent, MemoryLinks())

        val failure = runCatching {
            session.stream(context("conversation-1", "safe chat")).events.collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION, failure.code)
        assertEquals(1, agent.interruptCount)
    }

    @Test
    fun `collector cancellation interrupts active turn exactly once`() = runTest {
        val agent = FakeAgent().apply { script = Script.WAIT }
        val session = session(agent, MemoryLinks())
        val job = launch {
            session.stream(context("conversation-1", "wait")).events.collect()
        }
        runCurrent()

        job.cancel()
        job.join()

        assertEquals(1, agent.interruptCount)
    }

    @Test
    fun `multi turn resumes same conversation and separates another conversation`() = runTest {
        val agent = FakeAgent().apply { script = Script.COMPLETE }
        val links = MemoryLinks()
        val session = session(agent, links)

        session.stream(context("conversation-1", "first")).events.collect()
        session.stream(context("conversation-1", "second")).events.collect()
        session.stream(context("conversation-2", "separate")).events.collect()

        assertEquals(listOf("first", "second", "separate"), agent.prompts)
        assertEquals(2, agent.startThreadCount)
        assertEquals(listOf("thread-1"), agent.resumedThreadIds)
        assertEquals("thread-1", links.get("conversation-1"))
        assertEquals("thread-2", links.get("conversation-2"))
    }

    @Test
    fun `new session after process recreation resumes persisted thread without replay`() = runTest {
        val links = MemoryLinks()
        val firstAgent = FakeAgent().apply { script = Script.COMPLETE }
        session(firstAgent, links).stream(context("conversation-1", "first")).events.collect()

        val restartedAgent = FakeAgent().apply { script = Script.COMPLETE }
        session(restartedAgent, links).stream(context("conversation-1", "next")).events.collect()

        assertEquals(listOf("next"), restartedAgent.prompts)
        assertEquals(listOf("thread-1"), restartedAgent.resumedThreadIds)
        assertEquals(0, restartedAgent.startThreadCount)
    }

    @Test
    fun `partial delta followed by client eof never completes successfully`() = runTest {
        val agent = FakeAgent().apply { script = Script.CLIENT_FAILURE }
        val session = session(agent, MemoryLinks())

        val failure = runCatching {
            session.stream(context("conversation-1", "safe chat")).events.collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.PROVIDER_UNAVAILABLE, failure.code)
        assertEquals(1, agent.interruptCount)
    }

    @Test
    fun `overload and authentication failures map to stable chat failures`() = runTest {
        val overload = FakeAgent().apply {
            startTurnFailure = CodexAppServerErrorCode.SERVER_OVERLOADED
        }
        val overloadFailure = runCatching {
            session(overload, MemoryLinks())
                .stream(context("conversation-1", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException
        assertEquals(ChatBackendFailureCode.RATE_LIMITED, overloadFailure.code)

        val reauth = FakeAgent().apply {
            startTurnFailure = CodexAppServerErrorCode.AUTHENTICATION_REQUIRED
        }
        val reauthFailure = runCatching {
            session(reauth, MemoryLinks())
                .stream(context("conversation-2", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException
        assertEquals(ChatBackendFailureCode.REAUTHENTICATION_REQUIRED, reauthFailure.code)
    }

    @Test
    fun `failed terminal status is never treated as a successful completion`() = runTest {
        val agent = FakeAgent().apply { script = Script.FAILED_TURN }

        val failure = runCatching {
            session(agent, MemoryLinks())
                .stream(context("conversation-1", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.PROVIDER_UNAVAILABLE, failure.code)
        assertEquals(0, agent.interruptCount)
    }

    @Test
    fun `same-thread notification without required turn id fails closed`() = runTest {
        val agent = FakeAgent().apply { script = Script.MISSING_TURN_ID }

        val failure = runCatching {
            session(agent, MemoryLinks())
                .stream(context("conversation-1", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.INVALID_RESPONSE, failure.code)
        assertEquals(1, agent.interruptCount)
    }

    @Test
    fun `server model reroute is never a silent model switch`() = runTest {
        val agent = FakeAgent().apply { script = Script.MODEL_REROUTED }

        val failure = runCatching {
            session(agent, MemoryLinks())
                .stream(context("conversation-1", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION, failure.code)
        assertEquals(1, agent.interruptCount)
    }

    @Test
    fun `context compaction is internal metadata and never becomes chat output`() = runTest {
        val agent = FakeAgent().apply { script = Script.CONTEXT_COMPACTION }

        val events = session(agent, MemoryLinks())
            .stream(context("conversation-1", "safe chat"))
            .events
            .toList()

        assertEquals("hello", events.joinToString("") { it.text })
        assertEquals(0, agent.interruptCount)
    }

    @Test
    fun `thread scoped hook without turn id is rejected as an agent action`() = runTest {
        val agent = FakeAgent().apply { script = Script.UNSAFE_THREAD_HOOK }

        val failure = runCatching {
            session(agent, MemoryLinks())
                .stream(context("conversation-1", "safe chat"))
                .events
                .collect()
        }.exceptionOrNull() as ChatBackendException

        assertEquals(ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION, failure.code)
        assertEquals(1, agent.interruptCount)
    }

    private fun session(agent: FakeAgent, links: MemoryLinks) = CodexAgentChatSession(
        descriptor = CodexAgentChatSession.descriptor("gpt-5.6", listOf("gpt-5.6")),
        agent = agent,
        links = links,
    )

    private fun context(conversationId: String, currentPrompt: String) = ChatBackendRequestContext(
        conversationId = conversationId,
        actionId = "action-1",
        requestJson = JSONObject()
            .put("model", "gpt-5.6")
            .put("stream", true)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "old prompt"))
                    .put(JSONObject().put("role", "assistant").put("content", "old answer"))
                    .put(JSONObject().put("role", "user").put("content", currentPrompt)),
            )
            .toString(),
    )

    private enum class Script {
        COMPLETE,
        FAILED_TURN,
        UNSAFE_COMMAND,
        CLIENT_FAILURE,
        MISSING_TURN_ID,
        MODEL_REROUTED,
        CONTEXT_COMPACTION,
        UNSAFE_THREAD_HOOK,
        WAIT,
    }

    private class FakeAgent : CodexAgentApi {
        private val mutableNotifications = MutableSharedFlow<CodexRpcNotification>(
            extraBufferCapacity = 32,
        )
        override val notifications: SharedFlow<CodexRpcNotification> = mutableNotifications
        var script = Script.WAIT
        var failResume = false
        var startTurnFailure: CodexAppServerErrorCode? = null
        var resumed = false
        val resumedThreadIds = mutableListOf<String>()
        var startThreadCount = 0
        var interruptCount = 0
        val prompts = mutableListOf<String>()

        override suspend fun startThread(model: String): String {
            startThreadCount += 1
            return "thread-$startThreadCount"
        }

        override suspend fun resumeThread(threadId: String, model: String): String {
            resumed = true
            resumedThreadIds += threadId
            if (failResume) error("raw resume failure")
            return threadId
        }

        override suspend fun startTurn(
            threadId: String,
            model: String,
            prompt: String,
            actionId: String,
        ): String {
            startTurnFailure?.let { throw CodexAppServerException(it) }
            prompts += prompt
            val turnId = "turn-${prompts.size}"
            when (script) {
                Script.COMPLETE -> {
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "thread/status/changed",
                            JSONObject()
                                .put("threadId", threadId)
                                .put("status", JSONObject().put("type", "active")),
                        ),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "mcpServer/startupStatus/updated",
                            JSONObject()
                                .put("threadId", threadId)
                                .put("name", "disabled")
                                .put("status", "ready"),
                        ),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "warning",
                            JSONObject()
                                .put("threadId", threadId)
                                .put("message", "redacted by adapter"),
                        ),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "thread/goal/cleared",
                            JSONObject().put("threadId", threadId),
                        ),
                    )
                    emit("turn/started", threadId, turnId)
                    emitItem("item/started", threadId, turnId, "agentMessage")
                    emit(
                        "item/agentMessage/delta",
                        threadId,
                        turnId,
                        JSONObject().put("delta", "hello"),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "thread/name/updated",
                            JSONObject().put("threadId", threadId).put("name", "safe"),
                        ),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "turn/completed",
                            JSONObject()
                                .put("threadId", threadId)
                                .put(
                                    "turn",
                                    JSONObject().put("id", turnId).put("status", "completed"),
                                ),
                        ),
                    )
                }
                Script.UNSAFE_COMMAND ->
                    emitItem("item/started", threadId, turnId, "commandExecution")
                Script.FAILED_TURN ->
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "turn/completed",
                            JSONObject()
                                .put("threadId", threadId)
                                .put(
                                    "turn",
                                    JSONObject().put("id", turnId).put("status", "failed"),
                                ),
                        ),
                    )
                Script.CLIENT_FAILURE -> {
                    emit(
                        "item/agentMessage/delta",
                        threadId,
                        turnId,
                        JSONObject().put("delta", "partial"),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            method = "alpine/clientFailure",
                            params = JSONObject(),
                            clientFailureCode = CodexAppServerErrorCode.PROCESS_EXITED,
                        ),
                    )
                }
                Script.MISSING_TURN_ID ->
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            method = "item/agentMessage/delta",
                            params = JSONObject()
                                .put("threadId", threadId)
                                .put("delta", "must-not-emit"),
                        ),
                    )
                Script.MODEL_REROUTED ->
                    emit(
                        "model/rerouted",
                        threadId,
                        turnId,
                        JSONObject()
                            .put("fromModel", model)
                            .put("toModel", "different-model")
                            .put("reason", "highRiskCyberActivity"),
                    )
                Script.CONTEXT_COMPACTION -> {
                    emitItem("item/started", threadId, turnId, "contextCompaction")
                    emitItem("item/completed", threadId, turnId, "contextCompaction")
                    emit(
                        "item/agentMessage/delta",
                        threadId,
                        turnId,
                        JSONObject().put("delta", "hello"),
                    )
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "turn/completed",
                            JSONObject()
                                .put("threadId", threadId)
                                .put(
                                    "turn",
                                    JSONObject().put("id", turnId).put("status", "completed"),
                                ),
                        ),
                    )
                }
                Script.UNSAFE_THREAD_HOOK ->
                    mutableNotifications.emit(
                        CodexRpcNotification(
                            "hook/started",
                            JSONObject().put("threadId", threadId),
                        ),
                    )
                Script.WAIT -> Unit
            }
            return turnId
        }

        override suspend fun interruptTurn(threadId: String, turnId: String) {
            interruptCount += 1
        }

        private suspend fun emit(
            method: String,
            threadId: String,
            turnId: String,
            extra: JSONObject = JSONObject(),
        ) {
            extra.put("threadId", threadId).put("turnId", turnId)
            mutableNotifications.emit(CodexRpcNotification(method, extra))
        }

        private suspend fun emitItem(
            method: String,
            threadId: String,
            turnId: String,
            type: String,
        ) = emit(
            method,
            threadId,
            turnId,
            JSONObject().put("item", JSONObject().put("type", type).put("id", "item-1")),
        )
    }

    private class MemoryLinks : CodexThreadLinkStore {
        private val values = mutableMapOf<String, String>()
        override fun get(conversationId: String): String? = values[conversationId]
        override fun put(conversationId: String, threadId: String) {
            values[conversationId] = threadId
        }
        override fun remove(conversationId: String) {
            values.remove(conversationId)
        }
    }
}
