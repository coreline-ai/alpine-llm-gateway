package dev.alpine.chat.provider.android.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.alpine.chat.provider.android.ProviderDependencies
import dev.alpine.chat.provider.android.R
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.session.ConnectedProviderRegistry
import dev.alpine.chat.provider.android.session.ProviderConnection
import dev.alpine.chat.provider.android.session.ProviderConnectionIssue
import dev.alpine.chat.provider.android.session.ProviderConnectionState
import dev.alpine.chat.provider.android.session.ProviderAuthorizationRecoveryStore
import dev.alpine.chat.provider.android.session.ProviderPostSaveLoginPolicy
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.ui.ProviderProfilesScreen
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import dev.alpine.llm.OAuthTokenStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Compose host for adding, editing and authorizing LLM connection profiles. */
class ProviderProfilesActivity : ComponentActivity() {
    private lateinit var store: ProviderProfileStore
    private lateinit var registry: ConnectedProviderRegistry
    private lateinit var authorizationRecovery: ProviderAuthorizationRecoveryStore
    private lateinit var oauthTokenStore: OAuthTokenStore
    private var connections by mutableStateOf<List<ProviderConnection>>(emptyList())
    private var activeAuthorization: ChatCompletionSession? = null
    private var activeAuthorizationAttempt: ProviderAuthorizationRecoveryStore.Attempt? = null
    private var authorizationJob: Job? = null
    private var authorizingProfileId by mutableStateOf<String?>(null)
    private var connectionIssues by mutableStateOf<Map<String, ProviderConnectionIssue>>(emptyMap())
    private var deleteCandidate by mutableStateOf<ProviderConnection?>(null)
    private val editProfile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val profileId = result.data
            ?.getStringExtra(ProviderEditActivity.EXTRA_RESULT_PROFILE_ID)
            ?: return@registerForActivityResult
        val requestLogin = result.data?.getBooleanExtra(
            ProviderEditActivity.EXTRA_RESULT_REQUEST_LOGIN,
            false,
        ) == true
        refreshProfiles()
        if (!requestLogin) return@registerForActivityResult
        ProviderPostSaveLoginPolicy.select(connections, profileId, requestLogin)
            ?.let(::changeConnection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        store = ProviderProfileStore(this)
        authorizationRecovery = ProviderAuthorizationRecoveryStore(this)
        oauthTokenStore = OAuthTokenStore(this)
        registry = ConnectedProviderRegistry { profile ->
            ProviderDependencies.createSession(this, profile)
        }
        refreshProfiles()
        recoverInterruptedAuthorizations()
        setContent {
            AlpineProductTheme {
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
                    connectionIssues = connectionIssues,
                    onCancelAuthorization = ::cancelAuthorization,
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
        val knownProfileIds = connections.mapTo(mutableSetOf()) { it.profile.id }
        connectionIssues = connectionIssues.filterKeys(knownProfileIds::contains)
    }

    private fun openAddProfile(type: ProviderType) {
        editProfile.launch(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, type.wireName),
        )
    }

    private fun openEditProfile(connection: ProviderConnection) {
        editProfile.launch(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_PROFILE_ID, connection.profile.id),
        )
    }

    private fun changeConnection(connection: ProviderConnection) {
        if (connection.state == ProviderConnectionState.AUTHENTICATED) {
            connection.session.logout()
            authorizationRecovery.clearProfile(connection.profile.id)
            connectionIssues = connectionIssues - connection.profile.id
            refreshProfiles()
            return
        }
        if (activeAuthorization != null) return

        connectionIssues = connectionIssues - connection.profile.id
        val attempt = runCatching {
            authorizationRecovery.begin(connection.profile.id)
        }.getOrElse {
            connectionIssues = connectionIssues + (
                connection.profile.id to ProviderConnectionIssue.from(
                    OAuthException(
                        "failed to persist authorization lifecycle marker",
                        OAuthFailureKind.STORAGE_FAILURE,
                    ),
                )
            )
            Toast.makeText(
                this,
                R.string.connection_failed_generic,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        activeAuthorization = connection.session
        activeAuthorizationAttempt = attempt
        authorizingProfileId = connection.profile.id
        val session = connection.session
        authorizationJob = lifecycleScope.launch {
            try {
                // OAuth needs the visible Activity to launch the browser and receive its result.
                session.authorize(this@ProviderProfilesActivity)
                authorizationRecovery.clearIfCurrent(
                    connection.profile.id,
                    attempt.attemptId,
                )
                connectionIssues = connectionIssues - connection.profile.id
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connected,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancelled: CancellationException) {
                authorizationRecovery.markInterrupted(
                    connection.profile.id,
                    attempt.attemptId,
                )
                throw cancelled
            } catch (error: Exception) {
                authorizationRecovery.clearIfCurrent(
                    connection.profile.id,
                    attempt.attemptId,
                )
                // Never expose Provider bodies, endpoints, or OAuth details in UI/logcat.
                if (error is OAuthException) {
                    Log.w(
                        LOG_TAG,
                        "OAuth authorization failed: kind=${error.kind}",
                    )
                } else {
                    Log.w(LOG_TAG, "OAuth authorization failed: ${error.javaClass.simpleName}")
                }
                connectionIssues = connectionIssues + (
                    connection.profile.id to ProviderConnectionIssue.from(error)
                )
                Toast.makeText(
                    this@ProviderProfilesActivity,
                    R.string.connection_failed_generic,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (activeAuthorization === session) {
                    activeAuthorization = null
                    activeAuthorizationAttempt = null
                    authorizationJob = null
                    authorizingProfileId = null
                    refreshProfiles()
                }
            }
        }
    }

    private fun cancelAuthorization() {
        val session = activeAuthorization ?: return
        activeAuthorizationAttempt?.let { attempt ->
            authorizationRecovery.clearIfCurrent(attempt.profileId, attempt.attemptId)
        }
        session.cancelAuthorization()
        authorizationJob?.cancel()
        activeAuthorization = null
        activeAuthorizationAttempt = null
        authorizationJob = null
        authorizingProfileId = null
        refreshProfiles()
        Toast.makeText(this, R.string.connection_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun deleteProfile(connection: ProviderConnection) {
        deleteCandidate = null
        connection.session.logout()
        authorizationRecovery.clearProfile(connection.profile.id)
        store.delete(connection.profile.id)
        connectionIssues = connectionIssues - connection.profile.id
        refreshProfiles()
    }

    override fun onDestroy() {
        activeAuthorizationAttempt?.let { attempt ->
            authorizationRecovery.markInterrupted(attempt.profileId, attempt.attemptId)
        }
        authorizationJob?.cancel()
        activeAuthorization?.cancelAuthorization()
        super.onDestroy()
    }

    private fun recoverInterruptedAuthorizations() {
        val knownProfileIds = connections.mapTo(mutableSetOf()) { it.profile.id }
        runCatching { authorizationRecovery.prune(knownProfileIds) }
            .onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "Authorization recovery prune failed: ${error.javaClass.simpleName}",
                )
            }
        val recoveredIssues = buildMap {
            connections.forEach { connection ->
                val profileId = connection.profile.id
                val hasEncryptedTransaction = oauthTokenStore.hasTransaction(profileId)
                if (connection.state == ProviderConnectionState.AUTHENTICATED) {
                    authorizationRecovery.clearProfile(profileId)
                    if (hasEncryptedTransaction) oauthTokenStore.clearTransaction(profileId)
                    return@forEach
                }
                val recovery = runCatching {
                    authorizationRecovery.recover(profileId)
                        ?: if (hasEncryptedTransaction) {
                            authorizationRecovery.recordOrphaned(profileId)
                        } else {
                            null
                        }
                }.getOrElse { error ->
                    Log.w(
                        LOG_TAG,
                        "Authorization recovery failed: ${error.javaClass.simpleName}",
                    )
                    if (hasEncryptedTransaction) oauthTokenStore.clearTransaction(profileId)
                    put(
                        profileId,
                        ProviderConnectionIssue.from(
                            OAuthException(
                                "failed to restore authorization lifecycle marker",
                                OAuthFailureKind.STORAGE_FAILURE,
                            ),
                        ),
                    )
                    return@forEach
                }
                if (recovery != null) {
                    // Never resume an Authorization Code + PKCE transaction across process death.
                    oauthTokenStore.clearTransaction(profileId)
                    put(
                        profileId,
                        when (recovery) {
                            ProviderAuthorizationRecoveryStore.Recovery.INTERRUPTED ->
                                ProviderConnectionIssue.authorizationInterrupted()
                            ProviderAuthorizationRecoveryStore.Recovery.EXPIRED ->
                                ProviderConnectionIssue.authorizationExpired()
                        },
                    )
                }
            }
        }
        connectionIssues = connectionIssues + recoveredIssues
    }

    private companion object {
        const val LOG_TAG = "AlpineOAuth"
    }
}
