package dev.alpine.chat.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.alpine.chat.feature.backend.ChatBackendConnection
import dev.alpine.chat.feature.backend.ChatBackendConnectionState
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.feature.assistant.AssistantCatalog
import dev.alpine.chat.feature.assistant.AssistantPromptComposer
import dev.alpine.chat.feature.assistant.ResponseConstraintDetector
import dev.alpine.chat.feature.assistant.ResponseConstraintViolation
import dev.alpine.chat.feature.assistant.ResponseConstraints
import dev.alpine.chat.feature.assistant.ResponseFreshnessGuard
import dev.alpine.chat.feature.assistant.ResponseFreshnessGuardDetector
import dev.alpine.chat.feature.assistant.ResponseFreshnessViolation
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationSnapshot
import dev.alpine.chat.feature.llm.ChatRequestBuilder
import dev.alpine.chat.feature.model.AssistantSelection
import dev.alpine.chat.feature.model.ChatConversation
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatMessageState
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.model.ConversationGenerationState
import dev.alpine.chat.feature.model.ConversationSummary
import dev.alpine.chat.feature.model.ConversationText
import dev.alpine.chat.feature.ui.state.ChatFailure
import dev.alpine.chat.feature.ui.state.ChatFailureMapper
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.chat.feature.ui.state.ChatRetryTarget
import dev.alpine.chat.feature.ui.state.SafeProviderStatus
import dev.alpine.chat.feature.ui.state.SafeProviderStatusException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class ConnectedProviderOption(
    val profileId: String,
    val label: String,
    val model: String,
    val modelOptions: List<String>,
)

data class ChatUiState(
    val activeConversationId: String,
    val conversationTitle: String,
    val conversations: List<ConversationSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val providers: List<ConnectedProviderOption> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedModel: String? = null,
    val executionMode: ChatExecutionMode = ChatExecutionMode.FAST_CHAT,
    val selectedSkillId: String = AssistantSelection.DEFAULT_SKILL_ID,
    val selectedPersonaId: String = AssistantSelection.DEFAULT_PERSONA_ID,
    val defaultSkillId: String = AssistantSelection.DEFAULT_SKILL_ID,
    val defaultPersonaId: String = AssistantSelection.DEFAULT_PERSONA_ID,
    val isStreaming: Boolean = false,
    val activeGenerationCount: Int = 0,
    val isLoadingConversations: Boolean = false,
    val statusMessage: String? = null,
    val storageWarning: String? = null,
    val failure: ChatFailure? = null,
    val retryTarget: ChatRetryTarget? = null,
)

class ChatViewModel(
    private val repository: ConversationRepository = ConversationRepository(),
    initialAssistantSelection: AssistantSelection = AssistantSelection.DEFAULT,
    private val persistAssistantDefaults: (AssistantSelection) -> Unit = {},
) : ViewModel() {
    private data class FailureContext(
        val assistantMessageId: String,
        val failure: ChatFailure,
        val retryTarget: ChatRetryTarget?,
        val session: ChatBackendSession,
        val assistantSelection: AssistantSelection,
        val systemInstruction: String,
        val responseConstraints: ResponseConstraints,
        val responseFreshnessGuard: ResponseFreshnessGuard,
    )

    private data class RuntimeState(
        val statusMessage: String? = null,
        val failureContext: FailureContext? = null,
    )

    private var defaultAssistantSelection = AssistantCatalog.resolve(initialAssistantSelection)
    private var snapshot: ConversationSnapshot = repository.initialSnapshot(
        assistantSelection = defaultAssistantSelection,
    )
    private var providerOptions: List<ConnectedProviderOption> = emptyList()
    private val activeJobs = linkedMapOf<String, Job>()
    private val streamGenerations = mutableMapOf<String, Long>()
    private val runtimeStates = mutableMapOf<String, RuntimeState>()
    private var delayedPersistenceJob: Job? = null
    private var persistenceRevision = 0L
    private var storageWarning: String? = null
    private var isLoading = repository.isPersistent

    private val mutableState = MutableStateFlow(projectState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    init {
        if (repository.isPersistent) {
            viewModelScope.launch {
                runCatching {
                    repository.load(fallbackAssistantSelection = defaultAssistantSelection)
                }
                    .onSuccess { loaded ->
                        snapshot = applyProviderFallbacks(loaded)
                        storageWarning = if (loaded.recoveredFileCount > 0) {
                            "Some saved conversations could not be restored."
                        } else {
                            null
                        }
                        isLoading = false
                        publish()
                        persistImmediately()
                    }
                    .onFailure {
                        isLoading = false
                        storageWarning = "Saved conversations could not be opened. A new chat is available."
                        publish()
                        persistImmediately()
                    }
            }
        }
    }

    fun updateConnections(connections: List<ChatBackendConnection>) {
        providerOptions = connections
            .filter { it.state == ChatBackendConnectionState.AVAILABLE }
            .map {
                ConnectedProviderOption(
                    profileId = it.descriptor.profileId,
                    label = it.descriptor.label,
                    model = it.descriptor.model,
                    modelOptions = it.descriptor.modelOptions,
                )
            }
        val before = snapshot
        snapshot = applyProviderFallbacks(snapshot)
        if (snapshot != before) schedulePersistence()
        publish()
    }

    fun selectProvider(profileId: String) {
        if (isLoading) return
        val option = providerOptions.firstOrNull { it.profileId == profileId } ?: return
        updateActiveConversation { current ->
            val model = current.selectedModel
                ?.takeIf { current.selectedProfileId == profileId && it in option.modelOptions }
                ?: option.model
            current.copy(
                selectedProfileId = profileId,
                selectedModel = model,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        clearFailure(snapshot.activeConversationId)
        schedulePersistence()
        publish()
    }

    fun selectModel(profileId: String, model: String) {
        if (isLoading) return
        val option = providerOptions.firstOrNull { it.profileId == profileId } ?: return
        val active = snapshot.activeConversation
        if (active.selectedProfileId != profileId || model !in option.modelOptions) return
        updateActiveConversation {
            it.copy(selectedModel = model, updatedAtMs = System.currentTimeMillis())
        }
        clearFailure(active.id)
        schedulePersistence()
        publish()
    }

    /** Persists the active conversation's mode without interrupting an in-flight generation. */
    fun selectExecutionMode(mode: ChatExecutionMode) {
        if (isLoading) return
        val active = snapshot.activeConversation
        if (active.executionMode == mode) return
        updateActiveConversation {
            it.copy(executionMode = mode, updatedAtMs = System.currentTimeMillis())
        }
        clearFailure(active.id)
        persistImmediately()
        publish()
    }

    fun selectAssistantMode(
        skillId: String,
        personaId: String,
        saveAsDefault: Boolean = false,
    ) {
        if (isLoading) return
        val selection = AssistantCatalog.resolve(skillId, personaId)
        val active = snapshot.activeConversation
        if (
            active.selectedSkillId != selection.skillId ||
            active.selectedPersonaId != selection.personaId
        ) {
            updateActiveConversation {
                it.copy(
                    selectedSkillId = selection.skillId,
                    selectedPersonaId = selection.personaId,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
            persistImmediately()
        }
        if (saveAsDefault) updateAssistantDefaults(selection)
        publish()
    }

    fun saveCurrentAssistantModeAsDefault() {
        if (isLoading) return
        updateAssistantDefaults(
            AssistantCatalog.resolve(snapshot.activeConversation.assistantSelection),
        )
        publish()
    }

    fun resetAssistantMode(saveAsDefault: Boolean = false) {
        selectAssistantMode(
            skillId = AssistantSelection.DEFAULT_SKILL_ID,
            personaId = AssistantSelection.DEFAULT_PERSONA_ID,
            saveAsDefault = saveAsDefault,
        )
    }

    fun updateDraft(value: String) {
        if (isLoading) return
        val bounded = if (value.length <= MAX_DRAFT_CHARS) value else value.take(MAX_DRAFT_CHARS)
        if (snapshot.activeConversation.draft == bounded) return
        updateActiveConversation {
            it.copy(draft = bounded, updatedAtMs = System.currentTimeMillis())
        }
        schedulePersistence(DRAFT_PERSIST_DEBOUNCE_MS)
        publish()
    }

    fun newConversation() {
        if (isLoading) return
        val active = snapshot.activeConversation
        snapshot = repository.create(
            snapshot = snapshot,
            selectedProfileId = active.selectedProfileId
                ?: providerOptions.firstOrNull()?.profileId,
            selectedModel = active.selectedModel
                ?: providerOptions.firstOrNull()?.model,
            executionMode = active.executionMode,
            assistantSelection = defaultAssistantSelection,
        )
        clearFailure(snapshot.activeConversationId)
        persistImmediately()
        publish()
    }

    /** Backward-compatible name retained for existing callers. */
    fun clearConversation() = newConversation()

    fun selectConversation(id: String) {
        if (isLoading || snapshot.activeConversationId == id) return
        snapshot = repository.select(snapshot, id)
        snapshot = applyProviderFallbacks(snapshot)
        persistImmediately()
        publish()
    }

    fun renameConversation(id: String, title: String) {
        if (isLoading || title.isBlank()) return
        snapshot = repository.rename(snapshot, id, title)
        persistImmediately()
        publish()
    }

    fun deleteConversation(id: String) {
        if (isLoading) return
        streamGenerations[id] = (streamGenerations[id] ?: 0L) + 1L
        activeJobs.remove(id)?.cancel()
        runtimeStates.remove(id)
        val previousActive = snapshot.activeConversation
        snapshot = repository.delete(snapshot, id)
        if (snapshot.conversations.size == 1 && snapshot.activeConversation.isCompletelyEmpty()) {
            val fallback = providerOptions.firstOrNull()
            val selectedProfile = previousActive.selectedProfileId ?: fallback?.profileId
            val selectedModel = previousActive.selectedModel ?: fallback?.model
            snapshot = repository.update(
                snapshot,
                snapshot.activeConversation.copy(
                    selectedProfileId = selectedProfile,
                    selectedModel = selectedModel,
                    executionMode = previousActive.executionMode,
                    selectedSkillId = defaultAssistantSelection.skillId,
                    selectedPersonaId = defaultAssistantSelection.personaId,
                ),
            )
        }
        snapshot = applyProviderFallbacks(snapshot)
        persistImmediately()
        publish()
    }

    fun send(text: String, session: ChatBackendSession) {
        val prompt = text.trim()
        val conversation = snapshot.activeConversation
        val selectedModel = conversation.selectedModel
        if (
            isLoading ||
            prompt.isEmpty() ||
            activeJobs.containsKey(conversation.id) ||
            conversation.selectedProfileId != session.descriptor.profileId ||
            selectedModel != session.descriptor.model
        ) {
            return
        }
        if (activeJobs.size >= MAX_CONCURRENT_GENERATIONS) {
            runtimeStates[conversation.id] = RuntimeState(
                statusMessage = "Two conversations are already generating.",
            )
            publish()
            return
        }

        val userMessage = ChatMessage(role = ChatRole.USER, text = prompt)
        val requestMessages = conversation.messages + userMessage
        val assistantSelection = AssistantCatalog.resolve(conversation.assistantSelection)
        val systemInstruction = AssistantPromptComposer.compose(assistantSelection)
        val responseConstraints = ResponseConstraintDetector.detect(prompt)
        val responseFreshnessGuard = ResponseFreshnessGuardDetector.detect(prompt)
        val title = if (conversation.messages.none { it.role == ChatRole.USER }) {
            ConversationText.automaticTitle(prompt)
        } else {
            conversation.title
        }
        val prepared = conversation.copy(
            title = title,
            messages = requestMessages,
            draft = "",
            selectedProfileId = session.descriptor.profileId,
            selectedModel = session.descriptor.model,
            updatedAtMs = System.currentTimeMillis(),
        )
        replaceConversation(prepared)
        startStreaming(
            conversationId = prepared.id,
            requestMessages = requestMessages,
            session = session,
            assistantSelection = assistantSelection,
            systemInstruction = systemInstruction,
            responseConstraints = responseConstraints,
            responseFreshnessGuard = responseFreshnessGuard,
        )
    }

    /** Replays the active conversation's failed request without duplicating its user message. */
    fun retry(session: ChatBackendSession? = null) {
        val conversation = snapshot.activeConversation
        val context = runtimeStates[conversation.id]?.failureContext ?: return
        val target = context.retryTarget ?: return
        val retrySession = session ?: context.session
        if (
            activeJobs.containsKey(conversation.id) ||
            conversation.selectedProfileId != target.profileId ||
            conversation.selectedModel != target.model ||
            retrySession.descriptor.profileId != target.profileId ||
            retrySession.descriptor.model != target.model ||
            (session != null && session !== context.session)
        ) {
            return
        }
        if (activeJobs.size >= MAX_CONCURRENT_GENERATIONS) return
        val remaining = conversation.messages.filterNot { it.id == context.assistantMessageId }
        if (remaining.none { it.role == ChatRole.USER && it.text == target.userText }) return
        replaceConversation(
            conversation.copy(
                messages = remaining,
                generationState = ConversationGenerationState.IDLE,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        runtimeStates.remove(conversation.id)
        startStreaming(
            conversationId = conversation.id,
            requestMessages = remaining,
            session = retrySession,
            assistantSelection = context.assistantSelection,
            systemInstruction = context.systemInstruction,
            responseConstraints = context.responseConstraints,
            responseFreshnessGuard = context.responseFreshnessGuard,
        )
    }

    fun dismissFailure() {
        runtimeStates.remove(snapshot.activeConversationId)
        publish()
    }

    fun stopStreaming() {
        activeJobs[snapshot.activeConversationId]?.cancel()
    }

    fun flushPersistence() {
        if (!isLoading) persistImmediately()
    }

    private fun startStreaming(
        conversationId: String,
        requestMessages: List<ChatMessage>,
        session: ChatBackendSession,
        assistantSelection: AssistantSelection,
        systemInstruction: String,
        responseConstraints: ResponseConstraints,
        responseFreshnessGuard: ResponseFreshnessGuard,
    ) {
        val current = repository.get(snapshot, conversationId) ?: return
        val generation = (streamGenerations[conversationId] ?: 0L) + 1L
        streamGenerations[conversationId] = generation
        val assistant = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "",
            state = ChatMessageState.STREAMING,
            providerProfileId = session.descriptor.profileId,
            providerLabel = session.descriptor.label,
            model = session.descriptor.model,
            assistantSkillId = assistantSelection.skillId,
            assistantPersonaId = assistantSelection.personaId,
        )
        replaceConversation(
            current.copy(
                messages = requestMessages + assistant,
                generationState = ConversationGenerationState.STREAMING,
                hasUnreadCompletion = false,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        runtimeStates[conversationId] = RuntimeState(
            statusMessage = "Streaming from ${session.descriptor.label} · ${session.descriptor.model}",
        )
        schedulePersistence(STREAM_PERSIST_DEBOUNCE_MS)

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var correctionAttempt = 0
            var fallbackText: String? = null
            var previousViolations = emptyList<ResponseConstraintViolation>()
            var previousFreshnessViolation: ResponseFreshnessViolation? = null
            try {
                while (isCurrentGeneration(conversationId, generation)) {
                    val responseInstruction = responseFreshnessGuard.augmentSystemInstruction(
                        base = responseConstraints.augmentSystemInstruction(
                            base = systemInstruction,
                            previousViolations = previousViolations,
                        ),
                        previousViolation = previousFreshnessViolation,
                    )
                    val result = session.stream(
                        ChatRequestBuilder.build(
                            model = session.descriptor.model,
                            messages = requestMessages,
                            systemInstruction = responseInstruction,
                        ),
                    )
                    if (!isCurrentGeneration(conversationId, generation)) return@launch
                    if (result.statusCode !in 200..299) {
                        throw SafeProviderStatusException(SafeProviderStatus(result.statusCode))
                    }
                    result.events.collect { event ->
                        if (!isCurrentGeneration(conversationId, generation)) return@collect
                        val delta = event.text
                        if (delta.isNotEmpty()) {
                            appendAssistantDelta(conversationId, assistant.id, delta)
                        }
                    }
                    if (!isCurrentGeneration(conversationId, generation)) return@launch
                    val answer = assistantText(conversationId, assistant.id)
                    val violations = responseConstraints.validate(answer)
                    val freshnessViolation = responseFreshnessGuard.validate(answer)
                    val hasViolation = violations.isNotEmpty() || freshnessViolation != null
                    if (!hasViolation || correctionAttempt >= MAX_RESPONSE_CORRECTIONS) {
                        finishAssistant(
                            conversationId = conversationId,
                            assistantMessageId = assistant.id,
                            messageState = ChatMessageState.COMPLETE,
                            generationState = ConversationGenerationState.COMPLETE,
                        )
                        runtimeStates[conversationId] = when {
                            violations.isNotEmpty() && freshnessViolation != null -> RuntimeState(
                                statusMessage =
                                    "Response may not match the requested limits or verification boundary.",
                            )
                            violations.isNotEmpty() -> RuntimeState(
                                statusMessage = "Response format may not match the requested limits.",
                            )
                            freshnessViolation != null -> RuntimeState(
                                statusMessage =
                                    "Response may contain an unsupported external verification claim.",
                            )
                            previousFreshnessViolation != null && previousViolations.isNotEmpty() ->
                                RuntimeState(
                                    statusMessage =
                                        "Response corrected for format and verification safety.",
                                )
                            previousFreshnessViolation != null -> RuntimeState(
                                statusMessage =
                                    "Response corrected to remove an unsupported verification claim.",
                            )
                            correctionAttempt > 0 -> RuntimeState(
                                statusMessage = "Response corrected to match the requested format.",
                            )
                            else -> RuntimeState()
                        }
                        if (runtimeStates[conversationId] == RuntimeState()) {
                            runtimeStates.remove(conversationId)
                        }
                        persistImmediately()
                        break
                    }
                    fallbackText = answer
                    previousViolations = violations
                    previousFreshnessViolation = freshnessViolation
                    correctionAttempt += 1
                    replaceAssistantText(conversationId, assistant.id, "")
                    runtimeStates[conversationId] = RuntimeState(
                        statusMessage = if (freshnessViolation != null) {
                            "Correcting response verification safety…"
                        } else {
                            "Correcting response format…"
                        },
                    )
                    schedulePersistence(STREAM_PERSIST_DEBOUNCE_MS)
                    publish()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (isCurrentGeneration(conversationId, generation)) {
                    if (correctionAttempt > 0 && fallbackText != null) {
                        completeWithCorrectionFallback(
                            conversationId,
                            assistant.id,
                            fallbackText,
                            freshnessCorrection = previousFreshnessViolation != null,
                        )
                    } else {
                        recordFailure(
                            conversationId,
                            assistant.id,
                            requestMessages,
                            session,
                            assistantSelection,
                            systemInstruction,
                            responseConstraints,
                            responseFreshnessGuard,
                            timeout,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (isCurrentGeneration(conversationId, generation)) {
                    if (assistantText(conversationId, assistant.id).isBlank() && fallbackText != null) {
                        replaceAssistantText(conversationId, assistant.id, fallbackText)
                    }
                    finishAssistant(
                        conversationId = conversationId,
                        assistantMessageId = assistant.id,
                        messageState = ChatMessageState.CANCELLED,
                        generationState = ConversationGenerationState.CANCELLED,
                    )
                    runtimeStates[conversationId] = RuntimeState(statusMessage = "Stopped")
                    persistImmediately()
                }
            } catch (error: Exception) {
                if (isCurrentGeneration(conversationId, generation)) {
                    if (correctionAttempt > 0 && fallbackText != null) {
                        completeWithCorrectionFallback(
                            conversationId,
                            assistant.id,
                            fallbackText,
                            freshnessCorrection = previousFreshnessViolation != null,
                        )
                    } else {
                        recordFailure(
                            conversationId,
                            assistant.id,
                            requestMessages,
                            session,
                            assistantSelection,
                            systemInstruction,
                            responseConstraints,
                            responseFreshnessGuard,
                            error,
                        )
                    }
                }
            } finally {
                if (isCurrentGeneration(conversationId, generation)) {
                    activeJobs.remove(conversationId)
                    publish()
                }
            }
        }
        activeJobs[conversationId] = job
        publish()
        job.start()
    }

    private fun appendAssistantDelta(conversationId: String, assistantId: String, delta: String) {
        val conversation = repository.get(snapshot, conversationId) ?: return
        replaceConversation(
            conversation.copy(
                messages = conversation.messages.map { message ->
                    if (message.id == assistantId) message.copy(text = message.text + delta) else message
                },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        schedulePersistence(STREAM_PERSIST_DEBOUNCE_MS)
        publish()
    }

    private fun assistantText(conversationId: String, assistantId: String): String =
        repository.get(snapshot, conversationId)
            ?.messages
            ?.firstOrNull { it.id == assistantId }
            ?.text
            .orEmpty()

    private fun replaceAssistantText(conversationId: String, assistantId: String, text: String) {
        val conversation = repository.get(snapshot, conversationId) ?: return
        replaceConversation(
            conversation.copy(
                messages = conversation.messages.map { message ->
                    if (message.id == assistantId) {
                        message.copy(text = text, state = ChatMessageState.STREAMING)
                    } else {
                        message
                    }
                },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun completeWithCorrectionFallback(
        conversationId: String,
        assistantMessageId: String,
        fallbackText: String,
        freshnessCorrection: Boolean,
    ) {
        replaceAssistantText(conversationId, assistantMessageId, fallbackText)
        finishAssistant(
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            messageState = ChatMessageState.COMPLETE,
            generationState = ConversationGenerationState.COMPLETE,
        )
        runtimeStates[conversationId] = RuntimeState(
            statusMessage = if (freshnessCorrection) {
                "Response safety could not be corrected; the original response was kept."
            } else {
                "Response format could not be corrected; the original response was kept."
            },
        )
        persistImmediately()
    }

    private fun finishAssistant(
        conversationId: String,
        assistantMessageId: String,
        messageState: ChatMessageState,
        generationState: ConversationGenerationState,
    ) {
        val conversation = repository.get(snapshot, conversationId) ?: return
        replaceConversation(
            conversation.copy(
                messages = conversation.messages.map { message ->
                    if (message.id == assistantMessageId) message.copy(state = messageState) else message
                },
                generationState = generationState,
                hasUnreadCompletion = snapshot.activeConversationId != conversationId,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        publish()
    }

    private fun recordFailure(
        conversationId: String,
        assistantMessageId: String,
        requestMessages: List<ChatMessage>,
        session: ChatBackendSession,
        assistantSelection: AssistantSelection,
        systemInstruction: String,
        responseConstraints: ResponseConstraints,
        responseFreshnessGuard: ResponseFreshnessGuard,
        error: Throwable,
    ) {
        val failure = ChatFailureMapper.map(error)
        val retryTarget = if (failure.recoveryAction == ChatRecoveryAction.RETRY) {
            ChatRetryTarget(
                userText = requestMessages.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty(),
                profileId = session.descriptor.profileId,
                model = session.descriptor.model,
                assistantSkillId = assistantSelection.skillId,
                assistantPersonaId = assistantSelection.personaId,
            )
        } else {
            null
        }
        finishAssistant(
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            messageState = ChatMessageState.FAILED,
            generationState = ConversationGenerationState.FAILED,
        )
        runtimeStates[conversationId] = RuntimeState(
            statusMessage = "Request failed.",
            failureContext = FailureContext(
                assistantMessageId = assistantMessageId,
                failure = failure,
                retryTarget = retryTarget,
                session = session,
                assistantSelection = assistantSelection,
                systemInstruction = systemInstruction,
                responseConstraints = responseConstraints,
                responseFreshnessGuard = responseFreshnessGuard,
            ),
        )
        persistImmediately()
        publish()
    }

    private fun clearFailure(conversationId: String) {
        runtimeStates.remove(conversationId)
    }

    private fun updateAssistantDefaults(selection: AssistantSelection) {
        val resolved = AssistantCatalog.resolve(selection)
        runCatching { persistAssistantDefaults(resolved) }
            .onSuccess { defaultAssistantSelection = resolved }
            .onFailure { storageWarning = "Assistant defaults could not be saved." }
    }

    private fun applyProviderFallbacks(source: ConversationSnapshot): ConversationSnapshot {
        if (providerOptions.isEmpty()) return source
        var updated = source
        source.conversations.forEach { conversation ->
            val option = providerOptions.firstOrNull { it.profileId == conversation.selectedProfileId }
                ?: providerOptions.first()
            val model = conversation.selectedModel
                ?.takeIf { conversation.selectedProfileId == option.profileId && it in option.modelOptions }
                ?: option.model
            if (
                conversation.selectedProfileId != option.profileId ||
                conversation.selectedModel != model
            ) {
                updated = repository.update(
                    updated,
                    conversation.copy(
                        selectedProfileId = option.profileId,
                        selectedModel = model,
                    ),
                )
                runtimeStates.remove(conversation.id)
            }
        }
        return updated
    }

    private fun updateActiveConversation(transform: (ChatConversation) -> ChatConversation) {
        replaceConversation(transform(snapshot.activeConversation))
    }

    private fun replaceConversation(conversation: ChatConversation) {
        snapshot = repository.update(snapshot, conversation)
    }

    private fun isCurrentGeneration(conversationId: String, generation: Long): Boolean =
        streamGenerations[conversationId] == generation &&
            repository.get(snapshot, conversationId) != null

    private fun schedulePersistence(delayMs: Long = DEFAULT_PERSIST_DEBOUNCE_MS) {
        if (!repository.isPersistent || isLoading) return
        val revision = ++persistenceRevision
        repository.requestPersistence(revision)
        val captured = snapshot
        delayedPersistenceJob?.cancel()
        delayedPersistenceJob = viewModelScope.launch {
            delay(delayMs)
            persist(captured, revision)
        }
    }

    private fun persistImmediately() {
        if (!repository.isPersistent || isLoading) return
        delayedPersistenceJob?.cancel()
        delayedPersistenceJob = null
        val revision = ++persistenceRevision
        repository.requestPersistence(revision)
        val captured = snapshot
        viewModelScope.launch { persist(captured, revision) }
    }

    private suspend fun persist(captured: ConversationSnapshot, revision: Long) {
        runCatching { repository.persist(captured, revision) }
            .onFailure {
                storageWarning = "Conversation changes could not be saved."
                publish()
            }
    }

    private fun publish() {
        mutableState.value = projectState()
    }

    private fun projectState(): ChatUiState {
        val active = snapshot.activeConversation
        val selectedOption = providerOptions.firstOrNull { it.profileId == active.selectedProfileId }
        val selectedModel = active.selectedModel
            ?.takeIf { model -> selectedOption?.modelOptions?.contains(model) == true }
            ?: selectedOption?.model
        val projectedProviders = providerOptions.map { option ->
            if (option.profileId == selectedOption?.profileId && selectedModel != null) {
                option.copy(model = selectedModel)
            } else {
                option
            }
        }
        val runtime = runtimeStates[active.id]
        return ChatUiState(
            activeConversationId = active.id,
            conversationTitle = active.title,
            conversations = snapshot.summaries,
            messages = active.messages,
            draft = active.draft,
            providers = projectedProviders,
            selectedProfileId = selectedOption?.profileId,
            selectedModel = selectedModel,
            executionMode = active.executionMode,
            selectedSkillId = active.selectedSkillId,
            selectedPersonaId = active.selectedPersonaId,
            defaultSkillId = defaultAssistantSelection.skillId,
            defaultPersonaId = defaultAssistantSelection.personaId,
            isStreaming = activeJobs.containsKey(active.id),
            activeGenerationCount = activeJobs.size,
            isLoadingConversations = isLoading,
            statusMessage = when {
                isLoading -> "Restoring conversations…"
                providerOptions.isEmpty() -> "Connect an LLM to start chatting."
                else -> runtime?.statusMessage
            },
            storageWarning = storageWarning,
            failure = runtime?.failureContext?.failure,
            retryTarget = runtime?.failureContext?.retryTarget,
        )
    }

    override fun onCleared() {
        delayedPersistenceJob?.cancel()
        activeJobs.values.toList().forEach(Job::cancel)
        activeJobs.clear()
        super.onCleared()
    }

    class Factory(
        private val repository: ConversationRepository,
        private val initialAssistantSelection: AssistantSelection = AssistantSelection.DEFAULT,
        private val persistAssistantDefaults: (AssistantSelection) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(
                repository = repository,
                initialAssistantSelection = initialAssistantSelection,
                persistAssistantDefaults = persistAssistantDefaults,
            ) as T
        }
    }

    companion object {
        const val MAX_CONCURRENT_GENERATIONS = 2
        private const val MAX_RESPONSE_CORRECTIONS = 1
        private const val MAX_DRAFT_CHARS = 128 * 1024
        private const val DEFAULT_PERSIST_DEBOUNCE_MS = 350L
        private const val STREAM_PERSIST_DEBOUNCE_MS = 500L
        private const val DRAFT_PERSIST_DEBOUNCE_MS = 600L
    }
}
