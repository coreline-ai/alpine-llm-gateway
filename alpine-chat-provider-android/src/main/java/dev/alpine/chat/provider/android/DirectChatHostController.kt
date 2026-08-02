package dev.alpine.chat.provider.android

import android.content.Context
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ConnectedProviderRegistry

/**
 * Reusable Android direct-Provider host boundary shared by sample and product apps.
 *
 * It owns only profile/session assembly. Conversation state and rendering stay in the common
 * Chat Feature, while OAuth token material stays in the Android Provider implementation.
 */
class DirectChatHostController(
    context: Context,
    private val viewModel: ChatViewModel,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = ProviderProfileStore(appContext)
    private val registry = ConnectedProviderRegistry { profile ->
        ProviderDependencies.createSession(appContext, profile)
    }
    private var sessions: Map<String, ChatCompletionSession> = emptyMap()
    private val modelSessions = mutableMapOf<String, ChatCompletionSession>()

    fun refreshConnections() {
        val connections = registry.snapshot(store.load())
        sessions = connections.associate { it.profile.id to it.session }
        viewModel.updateConnections(connections.map { it.asChatBackendConnection() })
    }

    fun selectModel(profileId: String, model: String) {
        val option = viewModel.state.value.providers.firstOrNull { it.profileId == profileId }
            ?: return
        if (model !in option.modelOptions || option.model == model) return

        val profile = store.find(profileId) ?: return
        viewModel.selectModel(profileId, model)
        store.upsert(profile.copy(model = model))
        refreshConnections()
    }

    fun send(text: String) {
        val state = viewModel.state.value
        val profileId = state.selectedProfileId ?: return
        val model = state.selectedModel ?: return
        val session = sessionFor(profileId, model) ?: return
        viewModel.send(text, session)
    }

    private fun sessionFor(profileId: String, model: String): ChatCompletionSession? {
        sessions[profileId]?.takeIf { it.profile.model == model }?.let { return it }
        val profile = store.find(profileId)?.copy(model = model) ?: return null
        val key = "$profileId\u0000$model"
        return modelSessions.getOrPut(key) {
            ProviderDependencies.createSession(appContext, profile)
        }
    }

    override fun close() {
        (sessions.values + modelSessions.values)
            .distinct()
            .forEach(ChatCompletionSession::cancelAuthorization)
        sessions = emptyMap()
        modelSessions.clear()
    }
}
