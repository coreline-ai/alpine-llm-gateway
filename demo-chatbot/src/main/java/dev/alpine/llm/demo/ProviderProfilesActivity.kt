package dev.alpine.llm.demo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ConnectedProviderRegistry
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.ui.screens.provider.ProviderProfilesScreen
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme
import dev.alpine.llm.OAuthException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

/** Compose host for adding, editing and authorizing LLM connection profiles. */
class ProviderProfilesActivity : ComponentActivity() {
    private lateinit var store: ProviderProfileStore
    private lateinit var registry: ConnectedProviderRegistry
    private var connections by mutableStateOf<List<ProviderConnection>>(emptyList())
    private var activeAuthorization: ChatCompletionSession? = null
    private var authorizingProfileId by mutableStateOf<String?>(null)
    private var deleteCandidate by mutableStateOf<ProviderConnection?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProviderProfileStore(this)
        registry = ConnectedProviderRegistry { profile ->
            DemoDependencies.createSession(this, profile)
        }
        setContent {
            AlpineChatTheme {
                ProviderProfilesScreen(
                    connections = connections,
                    authorizingProfileId = authorizingProfileId,
                    deleteCandidate = deleteCandidate,
                    onBack = ::finish,
                    onAddProvider = ::openAddProfile,
                    onEdit = ::openEditProfile,
                    onConnectionAction = ::changeConnection,
                    onDelete = { deleteCandidate = it },
                    onConfirmDelete = ::deleteProfile,
                    onDismissDelete = { deleteCandidate = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
    }

    private fun refreshProfiles() {
        connections = registry.snapshot(store.load())
    }

    private fun openAddProfile(type: ProviderType) {
        startActivity(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, type.wireName),
        )
    }

    private fun openEditProfile(connection: ProviderConnection) {
        startActivity(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_PROFILE_ID, connection.profile.id),
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
        authorizingProfileId = connection.profile.id
        lifecycleScope.launch {
            try {
                // OAuth needs the visible Activity to launch the browser and receive its result.
                connection.session.authorize(this@ProviderProfilesActivity)
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connected,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Never expose Provider bodies, endpoints, or OAuth details in UI/logcat.
                if (error is OAuthException) {
                    Log.w(
                        LOG_TAG,
                        "OAuth authorization failed: kind=${error.kind}, " +
                            "message=${error.message}, " +
                            "cause=${error.cause?.javaClass?.simpleName ?: "none"}",
                    )
                } else {
                    Log.w(LOG_TAG, "OAuth authorization failed: ${error.javaClass.simpleName}")
                }
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connection_failed_generic,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                activeAuthorization = null
                authorizingProfileId = null
                refreshProfiles()
            }
        }
    }

    private fun deleteProfile(connection: ProviderConnection) {
        deleteCandidate = null
        connection.session.logout()
        store.delete(connection.profile.id)
        refreshProfiles()
    }

    override fun onDestroy() {
        activeAuthorization?.cancelAuthorization()
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "AlpineOAuth"
    }
}
