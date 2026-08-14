package dev.alpine.chat.provider.android

import android.content.Context
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ConnectedProviderRegistry
import dev.alpine.chat.provider.android.session.toChatBackendDescriptor

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
    private val modelSessions = mutableMapOf<ProviderSessionKey, ChatCompletionSession>()
    private val registry = ConnectedProviderRegistry { profile ->
        modelSessions.getOrPut(ProviderSessionKey.from(profile, profile.model)) {
            ProviderDependencies.createSession(appContext, profile)
        }
    }
    private var sessions: Map<String, ChatCompletionSession> = emptyMap()

    fun refreshConnections() {
        val profiles = store.load()
        val enabledSessionKeys = profiles.flatMap { profile ->
            profile.enabledModelIds().map { model -> ProviderSessionKey.from(profile, model) }
        }.toSet()
        evictStaleSessionEntries(
            cache = modelSessions,
            enabledKeys = enabledSessionKeys,
            onEvict = ChatCompletionSession::cancelAuthorization,
        )
        val connections = registry.snapshot(profiles)
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
        val session = session(profileId, model) ?: return
        viewModel.send(text, session)
    }

    /** Returns a model-specific authenticated host session without exposing OAuth credentials. */
    fun session(profileId: String, model: String): ChatCompletionSession? {
        sessions[profileId]?.takeIf { it.profile.model == model }?.let { return it }
        val storedProfile = store.find(profileId) ?: return null
        if (model !in storedProfile.toChatBackendDescriptor().modelOptions) return null
        val profile = storedProfile.copy(model = model)
        val key = ProviderSessionKey.from(profile, model)
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

/** Cache identity for every profile field captured by a Provider session, excluding catalog metadata. */
internal data class ProviderSessionKey(
    val profileId: String,
    val model: String,
    val type: ProviderType,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val inferenceEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    val callbackPort: Int,
    val googleProjectId: String?,
) {
    companion object {
        fun from(profile: ProviderProfile, model: String) = ProviderSessionKey(
            profileId = profile.id,
            model = model,
            type = profile.type,
            authorizationEndpoint = profile.authorizationEndpoint,
            tokenEndpoint = profile.tokenEndpoint,
            inferenceEndpoint = profile.inferenceEndpoint,
            clientId = profile.clientId,
            scopes = profile.scopes,
            callbackPort = profile.callbackPort,
            googleProjectId = profile.googleProjectId,
        )
    }
}

internal fun <K, T> evictStaleSessionEntries(
    cache: MutableMap<K, T>,
    enabledKeys: Set<K>,
    onEvict: (T) -> Unit,
) {
    (cache.keys - enabledKeys).forEach { key ->
        cache.remove(key)?.let(onEvict)
    }
}
