package dev.alpine.chat.backend.direct

import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatFailureStage
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.ChatStreamEvent
import dev.alpine.chat.routing.ChatStreamEventType
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.ProviderStreamException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDirectChatBackendTest {
    @Test
    fun `normalized Provider stream maps to common events`() = runTest {
        val backend = AndroidDirectChatBackend("direct") {
            HostLlmStreamResult(
                events = flowOf(
                    HostLlmStreamEvent.delta(text = "hello"),
                    HostLlmStreamEvent.delta(text = " world", finishReason = "stop"),
                ),
            )
        }
        val events = mutableListOf<ChatStreamEvent>()

        val result = backend.stream(request(), ChatStreamEmitter(events::add))

        assertEquals(ChatBackendResult.Completed, result)
        assertEquals(
            listOf(
                ChatStreamEventType.STARTED,
                ChatStreamEventType.DELTA,
                ChatStreamEventType.DELTA,
                ChatStreamEventType.COMPLETED,
            ),
            events.map { it.type },
        )
        assertEquals("hello world", events.filter { it.type == ChatStreamEventType.DELTA }.joinToString("") { it.text })
    }

    @Test
    fun `429 is closed dispatch failure and never retried by adapter`() = runTest {
        var calls = 0
        val backend = AndroidDirectChatBackend("direct") {
            calls += 1
            HostLlmStreamResult(statusCode = 429, errorBodyJson = "secret raw body")
        }

        val result = backend.stream(request(), ChatStreamEmitter { }) as ChatBackendResult.Failed

        assertEquals(1, calls)
        assertEquals(ChatBackendFailureCode.RATE_LIMITED, result.failure.code)
        assertEquals(ChatFailureStage.DISPATCH, result.failure.stage)
        assertTrue(result.failure.providerMayHaveAcceptedRequest)
    }

    @Test
    fun `failure after delta is marked streaming and cannot be fallback safe`() = runTest {
        val backend = AndroidDirectChatBackend("direct") {
            HostLlmStreamResult(
                events = flow {
                    emit(HostLlmStreamEvent.delta(text = "partial"))
                    throw ProviderStreamException("raw provider detail")
                },
            )
        }

        val result = backend.stream(request(), ChatStreamEmitter { }) as ChatBackendResult.Failed

        assertEquals(ChatBackendFailureCode.INVALID_RESPONSE, result.failure.code)
        assertEquals(ChatFailureStage.STREAMING, result.failure.stage)
    }

    @Test
    fun `request model mismatch fails before Provider preparation`() = runTest {
        val backend = AndroidDirectChatBackend("direct") { HostLlmStreamResult() }
        val invalid = request().copy(model = "other")

        val preparation = backend.prepare(invalid) as ChatBackendPreparation.Unavailable

        assertEquals(ChatBackendFailureCode.INVALID_RESPONSE, preparation.failure.code)
        assertEquals(ChatFailureStage.PREPARATION, preparation.failure.stage)
    }

    @Test
    fun `unknown normalized event is a closed invalid response failure`() = runTest {
        val backend = AndroidDirectChatBackend("direct") {
            HostLlmStreamResult(events = flowOf(HostLlmStreamEvent("""{"type":"unknown"}""")))
        }

        val result = backend.stream(request(), ChatStreamEmitter { }) as ChatBackendResult.Failed

        assertEquals(ChatBackendFailureCode.INVALID_RESPONSE, result.failure.code)
        assertEquals(ChatFailureStage.DISPATCH, result.failure.stage)
    }

    private fun request() = ChatBackendRequest(
        requestId = "request-direct-1234567890",
        conversationId = "conversation-1",
        model = "model",
        requestJson = """{"model":"model","messages":[]}""",
    )
}
