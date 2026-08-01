package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alpine.llm.demo.data.ConversationRepository
import dev.alpine.llm.demo.data.ConversationStore
import dev.alpine.llm.demo.data.AssistantDefaultsStore
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ConnectedProviderRegistry
import dev.alpine.llm.demo.ui.ChatViewModel
import dev.alpine.llm.demo.ui.screens.chat.AlpineChatScreen
import dev.alpine.llm.demo.ui.theme.AlpineChatTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel
    private lateinit var store: ProviderProfileStore
    private lateinit var registry: ConnectedProviderRegistry
    private var sessions: Map<String, ChatCompletionSession> = emptyMap()
    private val modelSessions = mutableMapOf<String, ChatCompletionSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantDefaultsStore = AssistantDefaultsStore(this)
        viewModel = ViewModelProvider(
            this,
            ChatViewModel.Factory(
                repository = ConversationRepository(ConversationStore(this)),
                initialAssistantSelection = assistantDefaultsStore.load(),
                persistAssistantDefaults = assistantDefaultsStore::save,
            ),
        )[ChatViewModel::class.java]
        store = ProviderProfileStore(this)
        registry = ConnectedProviderRegistry { profile ->
            DemoDependencies.createSession(this, profile)
        }

        setContent {
            AlpineChatTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value
                AlpineChatScreen(
                    state = state,
                    onSelectProvider = viewModel::selectProvider,
                    onSelectModel = ::selectModel,
                    onSelectAssistantMode = viewModel::selectAssistantMode,
                    onResetAssistantMode = viewModel::resetAssistantMode,
                    onNewChat = viewModel::newConversation,
                    onSelectConversation = viewModel::selectConversation,
                    onRenameConversation = viewModel::renameConversation,
                    onDeleteConversation = viewModel::deleteConversation,
                    onManageProviders = ::openProviderProfiles,
                    onDraftChange = viewModel::updateDraft,
                    onSend = { text -> sendMessage(text) },
                    onStop = viewModel::stopStreaming,
                    failure = state.failure,
                    onDismissFailure = viewModel::dismissFailure,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshConnections()
    }

    private fun refreshConnections() {
        val connections = registry.snapshot(store.load())
        sessions = connections.associate { it.profile.id to it.session }
        viewModel.updateConnections(connections)
    }

    private fun selectModel(profileId: String, model: String) {
        val state = viewModel.state.value
        val option = state.providers.firstOrNull { it.profileId == profileId } ?: return
        if (model !in option.modelOptions || option.model == model) return

        val profile = store.find(profileId) ?: return
        viewModel.selectModel(profileId, model)
        store.upsert(profile.copy(model = model))
        refreshConnections()
    }

    private fun sendMessage(text: String) {
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
            DemoDependencies.createSession(this, profile)
        }
    }

    private fun openProviderProfiles() {
        startActivity(Intent(this, ProviderProfilesActivity::class.java))
    }

    override fun onStop() {
        viewModel.flushPersistence()
        super.onStop()
    }

    override fun onDestroy() {
        (sessions.values + modelSessions.values)
            .distinct()
            .forEach(ChatCompletionSession::cancelAuthorization)
        super.onDestroy()
    }
}
