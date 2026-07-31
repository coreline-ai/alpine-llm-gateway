package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.databinding.ActivityProviderProfilesBinding
import dev.alpine.llm.demo.databinding.DialogProviderTypeBinding
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ConnectedProviderRegistry
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.llm.ProviderSessionFactory
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.ui.ProviderProfileAdapter
import kotlinx.coroutines.launch

class ProviderProfilesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProviderProfilesBinding
    private lateinit var store: ProviderProfileStore
    private lateinit var registry: ConnectedProviderRegistry
    private lateinit var adapter: ProviderProfileAdapter
    private var activeAuthorization: ChatCompletionSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProviderProfileStore(this)
        registry = ConnectedProviderRegistry { profile ->
            ProviderSessionFactory.create(this, profile)
        }
        adapter = ProviderProfileAdapter(
            onEdit = ::editProfile,
            onConnectionAction = ::changeConnection,
            onDelete = ::confirmDelete,
        )
        binding.profilesList.layoutManager = LinearLayoutManager(this)
        binding.profilesList.adapter = adapter
        binding.backButton.setOnClickListener { finish() }
        binding.addProviderButton.setOnClickListener { chooseProviderType() }
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
    }

    private fun refreshProfiles() {
        val connections = registry.snapshot(store.load())
        adapter.submitList(connections)
        binding.emptyProfilesText.visibility =
            if (connections.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun chooseProviderType() {
        val chooser = DialogProviderTypeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_llm_title)
            .setView(chooser.root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        fun select(type: ProviderType) {
            dialog.dismiss()
            startActivity(
                Intent(this, ProviderEditActivity::class.java)
                    .putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, type.wireName),
            )
        }
        chooser.anthropicCard.setOnClickListener { select(ProviderType.ANTHROPIC) }
        chooser.geminiCard.setOnClickListener { select(ProviderType.GEMINI) }
        chooser.openAiCard.setOnClickListener { select(ProviderType.OPENAI_COMPATIBLE) }
        dialog.show()
    }

    private fun editProfile(connection: ProviderConnection) {
        startActivity(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(
                    ProviderEditActivity.EXTRA_PROFILE_ID,
                    connection.profile.id,
                ),
        )
    }

    private fun changeConnection(connection: ProviderConnection) {
        if (connection.state == ProviderConnectionState.AUTHENTICATED) {
            connection.session.logout()
            refreshProfiles()
            return
        }
        if (activeAuthorization != null) return

        activeAuthorization = connection.session
        binding.addProviderButton.isEnabled = false
        Toast.makeText(this, R.string.connecting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                connection.session.authorize(this@ProviderProfilesActivity)
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connected,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connection_failed_generic,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                activeAuthorization = null
                binding.addProviderButton.isEnabled = true
                refreshProfiles()
            }
        }
    }

    private fun confirmDelete(connection: ProviderConnection) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_provider_title)
            .setMessage(R.string.delete_provider_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                connection.session.logout()
                store.delete(connection.profile.id)
                Toast.makeText(this, R.string.provider_deleted, Toast.LENGTH_SHORT).show()
                refreshProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        activeAuthorization?.cancelAuthorization()
        super.onDestroy()
    }
}
