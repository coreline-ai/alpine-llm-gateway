package dev.alpine.integrated

import android.content.Context
import dev.alpine.chat.feature.backend.RoutedChatBackendSession
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.provider.android.DirectChatHostController
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.routing.ChatFallbackApprovalRequest
import dev.alpine.chat.routing.ChatFallbackAuthorizer
import dev.alpine.runtime.bridge.AlpineLlmBridgeHealth
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingFastChatFallback(
    val reason: ChatBackendFailureCode,
    val fromModel: String,
    val toModel: String,
) {
    val modelWillChange: Boolean
        get() = fromModel != toModel
}

/** Activity-level chat dispatcher. It never stores or exposes OAuth credential material. */
class IntegratedChatHostController(
    context: Context,
    private val viewModel: ChatViewModel,
    private val alpineHost: IntegratedAlpineLlmHost,
) : AutoCloseable {
    private data class PendingApproval(
        val prompt: PendingFastChatFallback,
        val answer: CompletableDeferred<Boolean>,
    )

    private val direct = DirectChatHostController(context, viewModel)
    private val approvalLock = Any()
    private var pendingApproval: PendingApproval? = null
    private val mutableFallback = MutableStateFlow<PendingFastChatFallback?>(null)
    val pendingFallback: StateFlow<PendingFastChatFallback?> = mutableFallback.asStateFlow()

    fun refreshConnections() = direct.refreshConnections()

    fun selectModel(profileId: String, model: String) = direct.selectModel(profileId, model)

    fun send(text: String) {
        val state = viewModel.state.value
        if (state.executionMode == ChatExecutionMode.FAST_CHAT) {
            direct.send(text)
            return
        }
        val providerSession = selectedSession() ?: return
        val routed = RoutedChatBackendSession(
            descriptor = providerSession.descriptor,
            requestedMode = ChatExecutionMode.ALPINE_WORKSPACE,
            routerProvider = { alpineHost.routerFor(providerSession) },
            fallbackAuthorizer = ChatFallbackAuthorizer(::requestFallbackApproval),
        )
        viewModel.send(text, routed)
    }

    fun startAlpine(): CompletionStage<AlpineLlmBridgeHealth> =
        selectedSession()?.let(alpineHost::start) ?: unavailableProvider()

    fun restartAlpine(): CompletionStage<AlpineLlmBridgeHealth> =
        selectedSession()?.let(alpineHost::restart) ?: unavailableProvider()

    fun resolveFallback(approved: Boolean) {
        val pending = synchronized(approvalLock) {
            pendingApproval.also { pendingApproval = null }
        } ?: return
        mutableFallback.value = null
        pending.answer.complete(approved)
    }

    private fun selectedSession(): ChatCompletionSession? {
        val state = viewModel.state.value
        val profileId = state.selectedProfileId ?: return null
        val model = state.selectedModel ?: return null
        return direct.session(profileId, model)
    }

    private suspend fun requestFallbackApproval(
        request: ChatFallbackApprovalRequest,
    ): Boolean {
        val approval = PendingApproval(
            prompt = PendingFastChatFallback(
                reason = request.reason,
                fromModel = request.fromModel,
                toModel = request.toModel,
            ),
            answer = CompletableDeferred(),
        )
        synchronized(approvalLock) {
            pendingApproval?.answer?.complete(false)
            pendingApproval = approval
        }
        mutableFallback.value = approval.prompt
        return try {
            approval.answer.await()
        } finally {
            val clear = synchronized(approvalLock) {
                if (pendingApproval === approval) {
                    pendingApproval = null
                    true
                } else {
                    false
                }
            }
            if (clear) mutableFallback.value = null
        }
    }

    private fun unavailableProvider(): CompletionStage<AlpineLlmBridgeHealth> =
        CompletableFuture<AlpineLlmBridgeHealth>().also {
            it.completeExceptionally(IllegalStateException("authenticated Provider is required"))
        }

    override fun close() {
        resolveFallback(false)
        direct.close()
    }
}
