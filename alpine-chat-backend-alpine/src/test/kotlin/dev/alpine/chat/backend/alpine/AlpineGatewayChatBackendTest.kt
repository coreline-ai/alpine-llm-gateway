package dev.alpine.chat.backend.alpine

import dev.alpine.chat.routing.ChatBackendFailure
import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatFailureStage
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.ChatStreamEvent
import dev.alpine.chat.routing.ChatStreamEventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AlpineGatewayChatBackendTest {
    @Test
    fun `adapter preserves pre-dispatch runtime failure`() = runTest {
        val backend = AlpineGatewayChatBackend(
            id = "alpine",
            prepareAction = {
                ChatBackendPreparation.Unavailable(
                    ChatBackendFailure(
                        ChatBackendFailureCode.RUNTIME_NOT_INSTALLED,
                        ChatFailureStage.PREPARATION,
                        retryable = false,
                    ),
                )
            },
            streamAction = { _, _ -> error("must not dispatch") },
        )

        val preparation = backend.prepare(request()) as ChatBackendPreparation.Unavailable

        assertEquals(ChatBackendFailureCode.RUNTIME_NOT_INSTALLED, preparation.failure.code)
        assertEquals(ChatFailureStage.PREPARATION, preparation.failure.stage)
    }

    @Test
    fun `adapter uses shared stream contract without changing model`() = runTest {
        val events = mutableListOf<ChatStreamEvent>()
        val backend = AlpineGatewayChatBackend(
            id = "alpine",
            prepareAction = { ChatBackendPreparation.Ready },
            streamAction = { _, emitter ->
                emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = "gateway"))
                ChatBackendResult.Completed
            },
        )

        assertEquals(ChatBackendPreparation.Ready, backend.prepare(request()))
        assertEquals(ChatBackendResult.Completed, backend.stream(request(), ChatStreamEmitter(events::add)))
        assertEquals("gateway", events.single().text)
    }

    @Test
    fun `model mismatch fails before lifecycle start`() = runTest {
        var prepared = false
        val backend = AlpineGatewayChatBackend(
            id = "alpine",
            prepareAction = { prepared = true; ChatBackendPreparation.Ready },
            streamAction = { _, _ -> ChatBackendResult.Completed },
        )

        val result = backend.prepare(request().copy(model = "other"))

        assertEquals(ChatBackendFailureCode.INVALID_RESPONSE, (result as ChatBackendPreparation.Unavailable).failure.code)
        assertEquals(false, prepared)
    }

    private fun request() = ChatBackendRequest(
        requestId = "request-alpine-1234567890",
        conversationId = "conversation-1",
        model = "model",
        requestJson = """{"model":"model","messages":[]}""",
    )
}
