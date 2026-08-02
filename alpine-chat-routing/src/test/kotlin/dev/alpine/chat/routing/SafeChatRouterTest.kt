package dev.alpine.chat.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeChatRouterTest {
    @Test
    fun `runtime preparation failure falls back only after explicit approval`() = runTest {
        val direct = FakeBackend(ChatBackendKind.ANDROID_DIRECT)
        val alpine = FakeBackend(
            ChatBackendKind.ALPINE_GATEWAY,
            preparation = unavailable(ChatBackendFailureCode.RUNTIME_NOT_INSTALLED),
        )
        val events = mutableListOf<ChatStreamEvent>()
        val audits = mutableListOf<ChatRoutingAuditEvent>()
        var approvals = 0
        val router = SafeChatRouter(direct, alpine, auditSink = ChatRoutingAuditSink(audits::add))

        val result = router.route(
            routingRequest = routing(
                mode = ChatExecutionMode.ALPINE_WORKSPACE,
                primary = request("approved", "alpine-model"),
                fallback = request("approved", "direct-model"),
            ),
            authorizer = ChatFallbackAuthorizer {
                approvals += 1
                assertTrue(it.modelWillChange)
                assertEquals(ChatBackendFailureCode.RUNTIME_NOT_INSTALLED, it.reason)
                true
            },
            emitter = ChatStreamEmitter(events::add),
        )

        assertEquals(ChatRoutingOutcome.COMPLETED, result.outcome)
        assertEquals(ChatExecutionMode.FAST_CHAT, result.effectiveMode)
        assertEquals("android-direct", result.backendId)
        assertEquals("direct-model", result.model)
        assertTrue(result.fallbackUsed)
        assertEquals(1, approvals)
        assertEquals(0, alpine.streamCalls)
        assertEquals(1, direct.streamCalls)
        assertEquals(
            listOf(ChatStreamEventType.ROUTE_SELECTED, ChatStreamEventType.FALLBACK_ACTIVATED),
            events.take(2).map { it.type },
        )
        assertTrue(audits.any { it.type == ChatRoutingAuditType.FALLBACK_APPROVED })
        assertTrue(audits.any { it.backendId == "android-direct" && it.model == "direct-model" })
    }

    @Test
    fun `first delta failure never invokes fallback backend`() = runTest {
        var approvals = 0
        val direct = FakeBackend(ChatBackendKind.ANDROID_DIRECT)
        val alpine = FakeBackend(ChatBackendKind.ALPINE_GATEWAY) { emitter ->
            emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = "partial"))
            ChatBackendResult.Failed(
                ChatBackendFailure(
                    ChatBackendFailureCode.PROVIDER_UNAVAILABLE,
                    ChatFailureStage.STREAMING,
                    retryable = true,
                ),
            )
        }
        val result = SafeChatRouter(direct, alpine).route(
            routing(ChatExecutionMode.ALPINE_WORKSPACE, request("partial"), request("partial")),
            authorizer = ChatFallbackAuthorizer { approvals += 1; true },
        )

        assertEquals(ChatRoutingOutcome.FAILED, result.outcome)
        assertTrue(result.firstDeltaEmitted)
        assertFalse(result.fallbackUsed)
        assertEquals(0, approvals)
        assertEquals(0, direct.streamCalls)
        assertEquals(1, alpine.streamCalls)
    }

    @Test
    fun `provider dispatch failure before delta is not replayed to fallback`() = runTest {
        var approvals = 0
        val direct = FakeBackend(ChatBackendKind.ANDROID_DIRECT)
        val alpine = FakeBackend(ChatBackendKind.ALPINE_GATEWAY) {
            ChatBackendResult.Failed(
                ChatBackendFailure(
                    ChatBackendFailureCode.RATE_LIMITED,
                    ChatFailureStage.DISPATCH,
                    retryable = true,
                ),
            )
        }

        val result = SafeChatRouter(direct, alpine).route(
            routing(ChatExecutionMode.ALPINE_WORKSPACE, request("rate"), request("rate")),
            authorizer = ChatFallbackAuthorizer { approvals += 1; true },
        )

        assertEquals(ChatRoutingOutcome.FAILED, result.outcome)
        assertEquals(0, approvals)
        assertEquals(0, direct.streamCalls)
        assertTrue(result.failure?.providerMayHaveAcceptedRequest == true)
    }

    @Test
    fun `same request cannot run concurrently or replay after completion`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val direct = FakeBackend(ChatBackendKind.ANDROID_DIRECT) { emitter ->
            entered.complete(Unit)
            release.await()
            emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = "once"))
            ChatBackendResult.Completed
        }
        val router = SafeChatRouter(direct, FakeBackend(ChatBackendKind.ALPINE_GATEWAY))
        val route = routing(ChatExecutionMode.FAST_CHAT, request("same"))

        val first = async { router.route(route) }
        entered.await()
        val simultaneous = router.route(route)
        release.complete(Unit)
        val completed = first.await()
        val replay = router.route(route)

        assertEquals(ChatRoutingOutcome.COMPLETED, completed.outcome)
        assertEquals(ChatRoutingOutcome.DUPLICATE_REJECTED, simultaneous.outcome)
        assertEquals(ChatRoutingOutcome.DUPLICATE_REJECTED, replay.outcome)
        assertEquals(1, direct.streamCalls)
    }

    @Test
    fun `declined fallback emits no provider stream and new request remains retryable`() = runTest {
        val direct = FakeBackend(ChatBackendKind.ANDROID_DIRECT)
        val alpine = FakeBackend(
            ChatBackendKind.ALPINE_GATEWAY,
            preparation = unavailable(ChatBackendFailureCode.RUNTIME_REPAIR_REQUIRED),
        )
        val events = mutableListOf<ChatStreamEvent>()
        val router = SafeChatRouter(direct, alpine)
        val declined = router.route(
            routing(ChatExecutionMode.ALPINE_WORKSPACE, request("declined"), request("declined")),
            authorizer = ChatFallbackAuthorizer.DENY,
            emitter = ChatStreamEmitter(events::add),
        )
        assertEquals(0, direct.streamCalls)
        val retry = router.route(
            routing(ChatExecutionMode.FAST_CHAT, request("retry-new")),
        )

        assertEquals(ChatRoutingOutcome.FALLBACK_DECLINED, declined.outcome)
        assertEquals(1, direct.streamCalls)
        assertTrue(events.none { it.type in setOf(ChatStreamEventType.STARTED, ChatStreamEventType.DELTA) })
        assertEquals(ChatRoutingOutcome.COMPLETED, retry.outcome)
    }

    @Test
    fun `execution mode store isolates conversation and workspace scopes`() = runTest {
        val store = InMemoryChatExecutionModeStore()
        val conversation = ChatExecutionScope(ChatExecutionScopeKind.CONVERSATION, "conversation-1")
        val workspace = ChatExecutionScope(ChatExecutionScopeKind.WORKSPACE, "workspace-1")

        store.save(conversation, ChatExecutionMode.FAST_CHAT)
        store.save(workspace, ChatExecutionMode.ALPINE_WORKSPACE)

        assertEquals(ChatExecutionMode.FAST_CHAT, store.load(conversation))
        assertEquals(ChatExecutionMode.ALPINE_WORKSPACE, store.load(workspace))
        store.delete(conversation)
        assertEquals(null, store.load(conversation))
    }

    private class FakeBackend(
        override val kind: ChatBackendKind,
        private val preparation: ChatBackendPreparation = ChatBackendPreparation.Ready,
        private val streamBlock: suspend (ChatStreamEmitter) -> ChatBackendResult = { emitter ->
            emitter.emit(ChatStreamEvent(ChatStreamEventType.STARTED))
            emitter.emit(ChatStreamEvent(ChatStreamEventType.DELTA, text = "ok"))
            emitter.emit(ChatStreamEvent(ChatStreamEventType.COMPLETED))
            ChatBackendResult.Completed
        },
    ) : ChatBackend {
        override val id = when (kind) {
            ChatBackendKind.ANDROID_DIRECT -> "android-direct"
            ChatBackendKind.ALPINE_GATEWAY -> "alpine-gateway"
        }
        override val capabilities = ChatBackendCapabilities()
        var prepareCalls = 0
        var streamCalls = 0

        override suspend fun prepare(request: ChatBackendRequest): ChatBackendPreparation {
            prepareCalls += 1
            return preparation
        }

        override suspend fun stream(
            request: ChatBackendRequest,
            emitter: ChatStreamEmitter,
        ): ChatBackendResult {
            streamCalls += 1
            return streamBlock(emitter)
        }
    }

    private fun routing(
        mode: ChatExecutionMode,
        primary: ChatBackendRequest,
        fallback: ChatBackendRequest? = null,
    ) = ChatRoutingRequest(mode, primary, fallback)

    private fun request(id: String, model: String = "model") = ChatBackendRequest(
        requestId = "request-$id-1234567890",
        conversationId = "conversation-1",
        model = model,
        requestJson = """{"model":"$model","messages":[]}""",
    )

    private fun unavailable(code: ChatBackendFailureCode) = ChatBackendPreparation.Unavailable(
        ChatBackendFailure(code, ChatFailureStage.PREPARATION, retryable = true),
    )
}
