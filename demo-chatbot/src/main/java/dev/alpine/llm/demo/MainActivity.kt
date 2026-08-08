package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alpine.chat.provider.android.DirectChatHostController
import dev.alpine.chat.provider.android.activity.ProviderProfilesActivity
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel
    private lateinit var directChat: DirectChatHostController

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
        directChat = DirectChatHostController(this, viewModel)

        setContent {
            AlpineChatTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value
                AlpineChatScreen(
                    state = state,
                    onSelectProvider = viewModel::selectProvider,
                    onSelectModel = directChat::selectModel,
                    onSelectAssistantMode = viewModel::selectAssistantMode,
                    onResetAssistantMode = viewModel::resetAssistantMode,
                    onNewChat = viewModel::newConversation,
                    onSelectConversation = viewModel::selectConversation,
                    onRenameConversation = viewModel::renameConversation,
                    onDeleteConversation = viewModel::deleteConversation,
                    onStopConversation = viewModel::stopStreaming,
                    onManageProviders = ::openProviderProfiles,
                    onDraftChange = viewModel::updateDraft,
                    onSend = directChat::send,
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
        directChat.refreshConnections()
    }

    private fun openProviderProfiles() {
        startActivity(Intent(this, ProviderProfilesActivity::class.java))
    }

    override fun onStop() {
        viewModel.flushPersistence()
        super.onStop()
    }

    override fun onDestroy() {
        directChat.close()
        super.onDestroy()
    }
}
