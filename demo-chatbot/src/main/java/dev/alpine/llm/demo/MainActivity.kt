package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
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
                    onNewChat = viewModel::clearConversation,
                    onManageProviders = ::openProviderProfiles,
                    onSend = { text -> sendMessage(text) },
                    onStop = viewModel::stopStreaming,
                    failure = state.failure,
                    onDismissFailure = viewModel::dismissFailure,
                    onRetry = {
                        state.retryTarget?.profileId
                            ?.let(sessions::get)
                            ?.let(viewModel::retry)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val connections = registry.snapshot(store.load())
        sessions = connections.associate { it.profile.id to it.session }
        viewModel.updateConnections(connections)
    }

    private fun sendMessage(text: String) {
        val session = viewModel.state.value.selectedProfileId?.let(sessions::get) ?: return
        viewModel.send(text, session)
    }

    private fun openProviderProfiles() {
        startActivity(Intent(this, ProviderProfilesActivity::class.java))
    }

    override fun onDestroy() {
        sessions.values.forEach(ChatCompletionSession::cancelAuthorization)
        super.onDestroy()
    }
}
