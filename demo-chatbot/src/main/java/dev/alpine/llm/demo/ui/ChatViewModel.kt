package dev.alpine.llm.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ChatRequestBuilder
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.model.ChatMessage
import dev.alpine.llm.demo.model.ChatMessageState
import dev.alpine.llm.demo.model.ChatRole
import dev.alpine.llm.demo.ui.state.ChatFailure
import dev.alpine.llm.demo.ui.state.ChatFailureMapper
import dev.alpine.llm.demo.ui.state.ChatRecoveryAction
import dev.alpine.llm.demo.ui.state.ChatRetryTarget
import dev.alpine.llm.demo.ui.state.SafeProviderStatus
import dev.alpine.llm.demo.ui.state.SafeProviderStatusException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ConnectedProviderOption(
    val profileId: String,
    val label: String,
    val model: String,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val providers: List<ConnectedProviderOption> = emptyList(),
    val selectedProfileId: String? = null,
    val isStreaming: Boolean = false,
    val statusMessage: String? = null,
    val failure: ChatFailure? = null,
    val retryTarget: ChatRetryTarget? = null,
)

class ChatViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var streamJob: Job? = null
    private var failedAssistantId: String? = null
    private var failedSession: ChatCompletionSession? = null

    fun updateConnections(connections: List<ProviderConnection>) {
        val providers = connections
            .filter { it.state == ProviderConnectionState.AUTHENTICATED }
            .map {
                ConnectedProviderOption(
                    profileId = it.profile.id,
                    label = it.profile.label,
                    model = it.profile.model,
                )
            }
        mutableState.update { current ->
            val selected = current.selectedProfileId
                ?.takeIf { id -> providers.any { it.profileId == id } }
                ?: providers.firstOrNull()?.profileId
            current.copy(
                providers = providers,
                selectedProfileId = selected,
                statusMessage = if (providers.isEmpty()) {
                    "Connect an LLM to start chatting."
                } else {
                    current.statusMessage
                },
            )
        }
    }

    fun selectProvider(profileId: String) {
        mutableState.update { current ->
            if (current.isStreaming || current.providers.none { it.profileId == profileId }) {
                current
            } else {
                current.copy(selectedProfileId = profileId, statusMessage = null)
            }
        }
    }

    fun clearConversation() {
        if (mutableState.value.isStreaming) return
        failedAssistantId = null
        failedSession = null
        mutableState.update {
            it.copy(
                messages = emptyList(),
                statusMessage = null,
                failure = null,
                retryTarget = null,
            )
        }
    }

    fun send(text: String, session: ChatCompletionSession) {
        val prompt = text.trim()
        val current = mutableState.value
        if (
            prompt.isEmpty() ||
            current.isStreaming ||
            current.selectedProfileId != session.profile.id
        ) {
            return
        }

        val userMessage = ChatMessage(role = ChatRole.USER, text = prompt)
        val requestMessages = current.messages + userMessage
        startStreaming(requestMessages, session)
    }

    /** Replays a failed request without duplicating its original user message. */
    fun retry(session: ChatCompletionSession) {
        val current = mutableState.value
        val target = current.retryTarget ?: return
        val failedId = failedAssistantId ?: return
        if (
            current.isStreaming ||
            current.selectedProfileId != target.profileId ||
            failedSession !== session ||
            session.profile.id != target.profileId ||
            session.profile.model != target.model
        ) {
            return
        }

        val remainingMessages = current.messages.filterNot { it.id == failedId }
        // The stored retry target must still refer to the user message held in the conversation.
        if (remainingMessages.none { it.role == ChatRole.USER && it.text == target.userText }) return
        failedAssistantId = null
        failedSession = null
        startStreaming(remainingMessages, session)
    }

    fun dismissFailure() {
        failedAssistantId = null
        failedSession = null
        mutableState.update { it.copy(failure = null, retryTarget = null) }
    }

    private fun startStreaming(
        requestMessages: List<ChatMessage>,
        session: ChatCompletionSession,
    ) {
        failedAssistantId = null
        failedSession = null
        val assistantMessage = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "",
            state = ChatMessageState.STREAMING,
            providerProfileId = session.profile.id,
            providerLabel = session.profile.label,
            model = session.profile.model,
        )
        mutableState.update {
            it.copy(
                messages = requestMessages + assistantMessage,
                isStreaming = true,
                statusMessage = "Streaming from ${session.profile.label} · ${session.profile.model}",
                failure = null,
                retryTarget = null,
            )
        }

        streamJob = viewModelScope.launch {
            try {
                val result = session.stream(
                    ChatRequestBuilder.build(session.profile.model, requestMessages),
                )
                if (result.statusCode !in 200..299) {
                    throw SafeProviderStatusException(SafeProviderStatus(result.statusCode))
                }
                result.events.collect { event ->
                    val delta = JSONObject(event.dataJson).optString("text")
                    if (delta.isNotEmpty()) {
                        updateAssistant(assistantMessage.id) { message ->
                            message.copy(text = message.text + delta)
                        }
                    }
                }
                updateAssistant(assistantMessage.id) { message ->
                    message.copy(state = ChatMessageState.COMPLETE)
                }
                mutableState.update { it.copy(statusMessage = null, failure = null, retryTarget = null) }
            } catch (timeout: TimeoutCancellationException) {
                recordFailure(assistantMessage.id, requestMessages, session, timeout)
            } catch (cancelled: CancellationException) {
                updateAssistant(assistantMessage.id) { message ->
                    message.copy(state = ChatMessageState.CANCELLED)
                }
                mutableState.update {
                    it.copy(statusMessage = "Stopped", failure = null, retryTarget = null)
                }
            } catch (error: Exception) {
                recordFailure(assistantMessage.id, requestMessages, session, error)
            } finally {
                mutableState.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
    }

    private fun recordFailure(
        assistantMessageId: String,
        requestMessages: List<ChatMessage>,
        session: ChatCompletionSession,
        error: Throwable,
    ) {
        val failure = ChatFailureMapper.map(error)
        val retryTarget = if (failure.recoveryAction == ChatRecoveryAction.RETRY) {
            ChatRetryTarget(
                userText = requestMessages.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty(),
                profileId = session.profile.id,
                model = session.profile.model,
            )
        } else {
            null
        }
        updateAssistant(assistantMessageId) { message ->
            message.copy(state = ChatMessageState.FAILED)
        }
        failedAssistantId = assistantMessageId
        failedSession = session
        mutableState.update {
            it.copy(
                statusMessage = "Request failed.",
                failure = failure,
                retryTarget = retryTarget,
            )
        }
    }

    private fun updateAssistant(
        id: String,
        transform: (ChatMessage) -> ChatMessage,
    ) {
        mutableState.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == id) transform(message) else message
                },
            )
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
