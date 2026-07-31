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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
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
)

class ChatViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var streamJob: Job? = null

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
        mutableState.update { it.copy(messages = emptyList(), statusMessage = null) }
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
        val assistantMessage = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "",
            state = ChatMessageState.STREAMING,
            providerProfileId = session.profile.id,
            providerLabel = session.profile.label,
            model = session.profile.model,
        )
        val requestMessages = current.messages + userMessage
        mutableState.update {
            it.copy(
                messages = requestMessages + assistantMessage,
                isStreaming = true,
                statusMessage = "Streaming from ${session.profile.label} · ${session.profile.model}",
            )
        }

        streamJob = viewModelScope.launch {
            try {
                val result = session.stream(
                    ChatRequestBuilder.build(session.profile.model, requestMessages),
                )
                check(result.statusCode in 200..299) { "Provider request failed" }
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
                mutableState.update { it.copy(statusMessage = null) }
            } catch (cancelled: CancellationException) {
                updateAssistant(assistantMessage.id) { message ->
                    message.copy(state = ChatMessageState.CANCELLED)
                }
                mutableState.update { it.copy(statusMessage = "Stopped") }
            } catch (_: Exception) {
                updateAssistant(assistantMessage.id) { message ->
                    message.copy(
                        text = message.text.ifBlank {
                            "The LLM request failed. Check the connection and profile settings."
                        },
                        state = ChatMessageState.FAILED,
                    )
                }
                mutableState.update {
                    it.copy(
                        messages = if (
                            it.messages.lastOrNull()?.id == assistantMessage.id
                        ) {
                            it.messages
                        } else {
                            it.messages + ChatMessage(
                                role = ChatRole.ERROR,
                                text = "The LLM request failed.",
                                state = ChatMessageState.FAILED,
                            )
                        },
                        statusMessage =
                            "Request failed. Check the connection and profile settings.",
                    )
                }
            } finally {
                mutableState.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
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
