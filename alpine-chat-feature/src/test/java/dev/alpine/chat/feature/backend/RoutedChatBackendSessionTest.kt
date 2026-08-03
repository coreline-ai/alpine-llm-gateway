package dev.alpine.chat.feature.backend

import dev.alpine.chat.routing.ChatBackend
import dev.alpine.chat.routing.ChatBackendCapabilities
import dev.alpine.chat.routing.ChatBackendFailure
import dev.alpine.chat.routing.ChatBackendFailureCode as RoutingFailureCode
import dev.alpine.chat.routing.ChatBackendIdempotency
import dev.alpine.chat.routing.ChatBackendKind
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.routing.ChatFailureStage
import dev.alpine.chat.routing.ChatFallbackAuthorizer
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.ChatStreamEvent
import dev.alpine.chat.routing.ChatStreamEventType
import dev.alpine.chat.routing.SafeChatRouter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RoutedChatBackendSessionTest {
    @Test
    fun `request json and deltas cross the shared router unchanged`() = runTest {
        var receivedJson: String? = null
        val direct = backend(ChatBackendKind.ANDROID_DIRECT)
        val alpine = backend(ChatBackendKind.ALPINE_GATEWAY) { request, emitter ->
            receivedJson = request.requestJson
            emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = "hello"))
            emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = " alpine"))
            ChatBackendResult.Completed
        }
        val session = session(direct, alpine)
        val requestJson = """{"model":"model","messages":[{"role":"user","content":"keep-me"}],"system":"keep-system","stream":true}"""

        val deltas = session.stream(requestJson).events.toList()

        assertEquals(requestJson, receivedJson)
        assertEquals("hello alpine", deltas.joinToString("") { it.text })
    }

    @Test
    fun `declined pre-dispatch fallback never calls direct backend`() = runTest {
        var directCalls = 0
        val direct = backend(ChatBackendKind.ANDROID_DIRECT) { _, _ ->
            directCalls += 1
            ChatBackendResult.Completed
        }
        val alpine = unavailableBackend(RoutingFailureCode.RUNTIME_NOT_INSTALLED)
        val session = session(direct, alpine, ChatFallbackAuthorizer { false })

        val error = collectFailure(session)

        assertEquals(ChatBackendFailureCode.FALLBACK_DECLINED, error.code)
        assertEquals(0, directCalls)
    }

    @Test
    fun `failure after alpine dispatch is not replayed through direct backend`() = runTest {
        var directCalls = 0
        val direct = backend(ChatBackendKind.ANDROID_DIRECT) { _, _ ->
            directCalls += 1
            ChatBackendResult.Completed
        }
        val alpine = backend(ChatBackendKind.ALPINE_GATEWAY) { _, _ ->
            ChatBackendResult.Failed(
                ChatBackendFailure(
                    RoutingFailureCode.PROVIDER_UNAVAILABLE,
                    ChatFailureStage.DISPATCH,
                    retryable = true,
                ),
            )
        }
        val session = session(direct, alpine, ChatFallbackAuthorizer { true })

        val error = collectFailure(session)

        assertEquals(ChatBackendFailureCode.PROVIDER_UNAVAILABLE, error.code)
        assertEquals(0, directCalls)
    }

    private suspend fun collectFailure(session: RoutedChatBackendSession): ChatBackendException =
        try {
            session.stream(requestJson()).events.toList()
            fail("stream must fail")
            error("unreachable")
        } catch (error: ChatBackendException) {
            error
        }

    private fun session(
        direct: ChatBackend,
        alpine: ChatBackend,
        authorizer: ChatFallbackAuthorizer = ChatFallbackAuthorizer.DENY,
    ) = RoutedChatBackendSession(
        descriptor = ChatBackendDescriptor("profile", "Provider", "model"),
        requestedMode = ChatExecutionMode.ALPINE_WORKSPACE,
        routerProvider = { SafeChatRouter(direct, alpine) },
        fallbackAuthorizer = authorizer,
    )

    private fun unavailableBackend(code: RoutingFailureCode): ChatBackend = object : ChatBackend {
        override val id = "alpine"
        override val kind = ChatBackendKind.ALPINE_GATEWAY
        override val capabilities = ChatBackendCapabilities(ChatBackendIdempotency.NONE)
        override suspend fun prepare(request: ChatBackendRequest) =
            ChatBackendPreparation.Unavailable(
                ChatBackendFailure(code, ChatFailureStage.PREPARATION, retryable = true),
            )
        override suspend fun stream(request: ChatBackendRequest, emitter: ChatStreamEmitter) =
            error("must not dispatch")
    }

    private fun backend(
        kind: ChatBackendKind,
        stream: suspend (ChatBackendRequest, ChatStreamEmitter) -> ChatBackendResult =
            { _, _ -> ChatBackendResult.Completed },
    ): ChatBackend = object : ChatBackend {
        override val id = if (kind == ChatBackendKind.ANDROID_DIRECT) "direct" else "alpine"
        override val kind = kind
        override val capabilities = ChatBackendCapabilities(ChatBackendIdempotency.NONE)
        override suspend fun prepare(request: ChatBackendRequest) = ChatBackendPreparation.Ready
        override suspend fun stream(request: ChatBackendRequest, emitter: ChatStreamEmitter) =
            stream(request, emitter)
    }

    private fun requestJson() = """{"model":"model","messages":[],"stream":true}"""
}
