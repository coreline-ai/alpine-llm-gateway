package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.databinding.ActivityMainBinding
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ConnectedProviderRegistry
import dev.alpine.llm.demo.llm.ProviderSessionFactory
import dev.alpine.llm.demo.ui.ChatMessageAdapter
import dev.alpine.llm.demo.ui.ChatUiState
import dev.alpine.llm.demo.ui.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var store: ProviderProfileStore
    private lateinit var registry: ConnectedProviderRegistry
    private val messageAdapter = ChatMessageAdapter()
    private var sessions: Map<String, ChatCompletionSession> = emptyMap()
    private var renderingSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        store = ProviderProfileStore(this)
        registry = ConnectedProviderRegistry { profile ->
            ProviderSessionFactory.create(this, profile)
        }

        binding.messagesList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messagesList.adapter = messageAdapter
        binding.manageProvidersButton.setOnClickListener {
            startActivity(Intent(this, ProviderProfilesActivity::class.java))
        }
        binding.newChatButton.setOnClickListener { viewModel.clearConversation() }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.stopButton.setOnClickListener { viewModel.stopStreaming() }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
        binding.messageInput.addTextChangedListener {
            renderActionAvailability(viewModel.state.value)
        }
        binding.providerSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (!renderingSpinner) {
                        viewModel.state.value.providers.getOrNull(position)?.let {
                            viewModel.selectProvider(it.profileId)
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val connections = registry.snapshot(store.load())
        sessions = connections.associate { it.profile.id to it.session }
        viewModel.updateConnections(connections)
    }

    private fun sendMessage() {
        val state = viewModel.state.value
        val session = state.selectedProfileId?.let(sessions::get) ?: return
        val text = binding.messageInput.text.toString()
        if (text.isBlank() || state.isStreaming) return
        viewModel.send(text, session)
        binding.messageInput.text?.clear()
    }

    private fun render(state: ChatUiState) {
        val spinnerLabels = if (state.providers.isEmpty()) {
            listOf(getString(R.string.no_connected_provider))
        } else {
            state.providers.map { "${it.label} · ${it.model}" }
        }
        renderingSpinner = true
        binding.providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            spinnerLabels,
        )
        val selectedIndex = state.providers
            .indexOfFirst { it.profileId == state.selectedProfileId }
            .coerceAtLeast(0)
        binding.providerSpinner.setSelection(selectedIndex, false)
        renderingSpinner = false

        messageAdapter.submitList(state.messages) {
            if (state.messages.isNotEmpty()) {
                binding.messagesList.scrollToPosition(state.messages.lastIndex)
            }
        }
        binding.emptyMessagesText.visibility =
            if (state.messages.isEmpty()) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (state.isStreaming) View.VISIBLE else View.GONE
        binding.sendButton.visibility = if (state.isStreaming) View.GONE else View.VISIBLE
        binding.providerSpinner.isEnabled =
            state.providers.isNotEmpty() && !state.isStreaming
        binding.newChatButton.isEnabled = !state.isStreaming

        val selected = state.providers.firstOrNull {
            it.profileId == state.selectedProfileId
        }
        binding.statusText.text = state.statusMessage ?: if (selected == null) {
            getString(R.string.no_connected_provider)
        } else {
            getString(R.string.ready, selected.label, selected.model)
        }
        renderActionAvailability(state)
    }

    private fun renderActionAvailability(state: ChatUiState) {
        binding.sendButton.isEnabled =
            !state.isStreaming &&
                state.selectedProfileId != null &&
                binding.messageInput.text?.isNotBlank() == true
    }

    override fun onDestroy() {
        sessions.values.forEach(ChatCompletionSession::cancelAuthorization)
        super.onDestroy()
    }
}
