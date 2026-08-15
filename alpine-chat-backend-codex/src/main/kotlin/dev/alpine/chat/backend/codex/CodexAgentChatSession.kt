package dev.alpine.chat.backend.codex

import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import dev.alpine.chat.feature.backend.ChatBackendRequestContext
import dev.alpine.chat.feature.backend.ChatBackendRequestPolicy
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.backend.ContextualChatBackendSession
import dev.alpine.codex.appserver.CodexAgentApi
import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import dev.alpine.codex.appserver.protocol.CodexRpcNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class CodexAgentChatSession(
    override val descriptor: ChatBackendDescriptor,
    private val agent: CodexAgentApi,
    private val links: CodexThreadLinkStore,
) : ContextualChatBackendSession, ChatBackendRequestPolicy {
    override val allowsAutomaticCorrection: Boolean = false
    private val attachLock = Mutex()

    init {
        require(descriptor.profileId == PROFILE_ID) { "Codex Agent profile ID is fixed" }
    }

    override suspend fun stream(request: ChatBackendRequestContext): ChatBackendStreamResult {
        val prompt = currentUserPrompt(request.requestJson)
        return ChatBackendStreamResult(
            events = channelFlow {
                val inbox = Channel<CodexRpcNotification>(NOTIFICATION_BUFFER)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.notifications.collect(inbox::send)
                }
                var threadId: String? = null
                var turnId: String? = null
                var terminal = false
                val interrupted = AtomicBoolean(false)
                try {
                    threadId = attachThread(request.conversationId)
                    turnId = try {
                        agent.startTurn(
                            threadId = threadId,
                            model = descriptor.model,
                            prompt = prompt,
                            actionId = request.actionId,
                        )
                    } catch (failure: Throwable) {
                        throw failure.toBackendFailure()
                    }
                    withTimeout(TURN_TIMEOUT_MS) {
                        while (!terminal) {
                            val notification = inbox.receive()
                            notification.clientFailureCode?.let { code ->
                                throw CodexAppServerException(code).toBackendFailure()
                            }
                            if (notification.method in SAFE_THREAD_METADATA) {
                                if (notification.belongsToThread(threadId)) continue
                            }
                            if (notification.method in UNSAFE_THREAD_EVENTS) {
                                if (notification.belongsToThread(threadId)) {
                                    throw ChatBackendException(
                                        ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION,
                                    )
                                }
                                continue
                            }
                            if (!notification.belongsTo(threadId, turnId)) continue
                            when (notification.method) {
                                AGENT_MESSAGE_DELTA -> {
                                    val delta = notification.params.requiredString("delta")
                                    if (delta.toByteArray(Charsets.UTF_8).size > MAX_DELTA_BYTES) {
                                        throw ChatBackendException(
                                            ChatBackendFailureCode.RESPONSE_TOO_LARGE,
                                        )
                                    }
                                    if (delta.isNotEmpty()) send(ChatBackendDelta(delta))
                                }
                                TURN_COMPLETED -> {
                                    val status = notification.params.requiredObject("turn")
                                        .requiredString("status")
                                    terminal = true
                                    when (status) {
                                        "completed" -> Unit
                                        "interrupted" -> throw CancellationException(
                                            "Codex turn interrupted",
                                        )
                                        else -> throw ChatBackendException(
                                            ChatBackendFailureCode.PROVIDER_UNAVAILABLE,
                                        )
                                    }
                                }
                                ITEM_STARTED,
                                ITEM_COMPLETED,
                                -> ensureSafeItem(notification.params)
                                TURN_STARTED,
                                TURN_PLAN_UPDATED,
                                TURN_MODERATION_METADATA,
                                SAFE_REASONING_DELTA,
                                SAFE_REASONING_SUMMARY_DELTA,
                                SAFE_REASONING_PART,
                                SAFE_PLAN_DELTA,
                                THREAD_TOKEN_USAGE_UPDATED,
                                THREAD_COMPACTED,
                                MODEL_VERIFICATION,
                                MODEL_SAFETY_BUFFERING_UPDATED,
                                -> Unit
                                TURN_ERROR -> throw ChatBackendException(
                                    ChatBackendFailureCode.PROVIDER_UNAVAILABLE,
                                )
                                MODEL_REROUTED -> throw ChatBackendException(
                                    ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION,
                                )
                                else -> throw ChatBackendException(
                                    ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION,
                                )
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    throw ChatBackendException(ChatBackendFailureCode.TIMEOUT)
                } finally {
                    collector.cancel()
                    inbox.close()
                    val activeThread = threadId
                    val activeTurn = turnId
                    if (!terminal && activeThread != null && activeTurn != null &&
                        interrupted.compareAndSet(false, true)
                    ) {
                        withContext(NonCancellable) {
                            runCatching { agent.interruptTurn(activeThread, activeTurn) }
                        }
                    }
                }
            },
        )
    }

    private suspend fun attachThread(conversationId: String): String = attachLock.withLock {
        val existing = links.get(conversationId)
        if (existing != null) {
            return@withLock try {
                agent.resumeThread(existing, descriptor.model)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                throw ChatBackendException(ChatBackendFailureCode.THREAD_REATTACH_REQUIRED)
            }
        }
        val created = try {
            agent.startThread(descriptor.model)
        } catch (failure: Throwable) {
            throw failure.toBackendFailure()
        }
        try {
            links.put(conversationId, created)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw ChatBackendException(ChatBackendFailureCode.UNKNOWN)
        }
        created
    }

    private fun currentUserPrompt(requestJson: String): String {
        val request = try {
            JSONObject(requestJson)
        } catch (_: Exception) {
            throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        }
        if (request.requiredString("model") != descriptor.model ||
            (request.opt("stream") as? Boolean) != true
        ) {
            throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        }
        val messages = request.opt("messages") as? org.json.JSONArray
            ?: throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        val last = messages.optJSONObject(messages.length() - 1)
            ?: throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        val prompt = last.requiredString("content")
        if (last.requiredString("role") != "user" || prompt.isBlank() ||
            prompt.toByteArray(Charsets.UTF_8).size > MAX_PROMPT_BYTES ||
            prompt.any { it == '\u0000' || (it.isISOControl() && it !in "\n\r\t") }
        ) {
            throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        }
        return prompt
    }

    private fun ensureSafeItem(params: JSONObject) {
        val type = params.requiredObject("item").requiredString("type")
        if (type !in SAFE_ITEM_TYPES) {
            throw ChatBackendException(ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION)
        }
    }

    private fun CodexRpcNotification.belongsTo(threadId: String, turnId: String): Boolean {
        if (!belongsToThread(threadId)) return false
        val rawTurnId = params.opt("turnId")
        val eventTurnId = when {
            rawTurnId is String && rawTurnId.isNotBlank() -> rawTurnId
            rawTurnId != null && rawTurnId != JSONObject.NULL ->
                throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
            else -> params.optJSONObject("turn")?.requiredString("id")?.takeIf(String::isNotBlank)
        }
            ?: throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        return eventTurnId == turnId
    }

    private fun CodexRpcNotification.belongsToThread(threadId: String): Boolean {
        val rawThreadId = params.opt("threadId") ?: return false
        if (rawThreadId == JSONObject.NULL) return false
        if (rawThreadId !is String) {
            throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        }
        return rawThreadId == threadId
    }

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String)
            ?: throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)

    private fun JSONObject.requiredObject(name: String): JSONObject =
        (opt(name) as? JSONObject)
            ?: throw ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)

    private fun Throwable.toBackendFailure(): ChatBackendException {
        if (this is CancellationException) throw this
        if (this is ChatBackendException) return this
        val code = (this as? CodexAppServerException)?.code
        return ChatBackendException(
            when (code) {
                CodexAppServerErrorCode.AUTHENTICATION_REQUIRED,
                CodexAppServerErrorCode.LOGIN_FAILED,
                -> ChatBackendFailureCode.REAUTHENTICATION_REQUIRED
                CodexAppServerErrorCode.REQUEST_TIMEOUT -> ChatBackendFailureCode.TIMEOUT
                CodexAppServerErrorCode.SERVER_OVERLOADED -> ChatBackendFailureCode.RATE_LIMITED
                CodexAppServerErrorCode.RESPONSE_TOO_LARGE ->
                    ChatBackendFailureCode.RESPONSE_TOO_LARGE
                CodexAppServerErrorCode.PROCESS_EXITED,
                CodexAppServerErrorCode.PROCESS_START_FAILED,
                CodexAppServerErrorCode.TRUST_STORE_UNAVAILABLE,
                CodexAppServerErrorCode.NETWORK_BRIDGE_FAILED,
                CodexAppServerErrorCode.ARTIFACT_UNAVAILABLE,
                CodexAppServerErrorCode.ARTIFACT_INVALID,
                CodexAppServerErrorCode.BROWSER_UNAVAILABLE,
                -> ChatBackendFailureCode.PROVIDER_UNAVAILABLE
                CodexAppServerErrorCode.UNSUPPORTED_AGENT_ACTION,
                CodexAppServerErrorCode.UNSUPPORTED_SERVER_REQUEST,
                -> ChatBackendFailureCode.UNSUPPORTED_AGENT_ACTION
                CodexAppServerErrorCode.THREAD_REATTACH_REQUIRED ->
                    ChatBackendFailureCode.THREAD_REATTACH_REQUIRED
                else -> ChatBackendFailureCode.INVALID_RESPONSE
            },
        )
    }

    companion object {
        const val PROFILE_ID = "codex-agent-chatgpt"
        const val LABEL = "Codex Agent (ChatGPT 로그인)"

        fun descriptor(model: String, models: List<String>): ChatBackendDescriptor =
            ChatBackendDescriptor(
                profileId = PROFILE_ID,
                label = LABEL,
                model = model,
                modelOptions = models,
            )

        private const val NOTIFICATION_BUFFER = 256
        private const val MAX_PROMPT_BYTES = 64 * 1024
        private const val MAX_DELTA_BYTES = 256 * 1024
        private const val TURN_TIMEOUT_MS = 120_000L
        private const val AGENT_MESSAGE_DELTA = "item/agentMessage/delta"
        private const val ITEM_STARTED = "item/started"
        private const val ITEM_COMPLETED = "item/completed"
        private const val TURN_STARTED = "turn/started"
        private const val TURN_COMPLETED = "turn/completed"
        private const val TURN_PLAN_UPDATED = "turn/plan/updated"
        private const val TURN_MODERATION_METADATA = "turn/moderationMetadata"
        private const val SAFE_REASONING_DELTA = "item/reasoning/textDelta"
        private const val SAFE_REASONING_SUMMARY_DELTA = "item/reasoning/summaryTextDelta"
        private const val SAFE_REASONING_PART = "item/reasoning/summaryPartAdded"
        private const val SAFE_PLAN_DELTA = "item/plan/delta"
        private const val THREAD_TOKEN_USAGE_UPDATED = "thread/tokenUsage/updated"
        private const val THREAD_COMPACTED = "thread/compacted"
        private const val MODEL_VERIFICATION = "model/verification"
        private const val MODEL_SAFETY_BUFFERING_UPDATED = "model/safetyBuffering/updated"
        private const val TURN_ERROR = "error"
        private const val MODEL_REROUTED = "model/rerouted"
        private val SAFE_THREAD_METADATA = setOf(
            "thread/status/changed",
            "thread/name/updated",
            // Startup status is thread-scoped but intentionally has no turnId. It never becomes
            // chat output or authorizes an MCP/tool action; actual tool items still fail closed.
            "mcpServer/startupStatus/updated",
            // Generic App Server warning text is metadata, not assistant output. It is ignored
            // rather than leaking raw server text; guardian/action warnings remain fail-closed.
            "warning",
            // Goal state is App Server thread metadata. This backend never calls goal RPCs and
            // never exposes the metadata as assistant output or an executable action.
            "thread/goal/updated",
            "thread/goal/cleared",
        )
        private val UNSAFE_THREAD_EVENTS = setOf(
            "thread/archived",
            "thread/deleted",
            "thread/unarchived",
            "thread/closed",
            "thread/environment/connected",
            "thread/environment/disconnected",
            "thread/settings/updated",
            "hook/started",
            "hook/completed",
            "serverRequest/resolved",
            "guardianWarning",
            "thread/realtime/started",
            "thread/realtime/itemAdded",
            "thread/realtime/transcript/delta",
            "thread/realtime/transcript/done",
            "thread/realtime/outputAudio/delta",
            "thread/realtime/sdp",
            "thread/realtime/error",
            "thread/realtime/closed",
        )
        private val SAFE_ITEM_TYPES = setOf(
            "userMessage",
            "agentMessage",
            "reasoning",
            "plan",
            "contextCompaction",
        )
    }
}
