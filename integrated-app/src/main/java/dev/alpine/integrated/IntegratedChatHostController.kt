package dev.alpine.integrated

import android.content.Context
import dev.alpine.chat.backend.codex.AndroidCodexThreadLinkStore
import dev.alpine.chat.backend.codex.CodexAgentChatSession
import dev.alpine.chat.feature.backend.ChatBackendConnection
import dev.alpine.chat.feature.backend.ChatBackendConnectionState
import dev.alpine.chat.feature.backend.RoutedChatBackendSession
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.provider.android.DirectChatHostController
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.routing.ChatFallbackApprovalRequest
import dev.alpine.chat.routing.ChatFallbackAuthorizer
import dev.alpine.codex.appserver.CodexAppServerClient
import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import dev.alpine.codex.appserver.CodexAppServerRuntime
import dev.alpine.codex.appserver.CodexAuthController
import dev.alpine.codex.appserver.CodexAuthState
import dev.alpine.runtime.bridge.AlpineLlmBridgeHealth
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PendingFastChatFallback(
    val reason: ChatBackendFailureCode,
    val fromModel: String,
    val toModel: String,
) {
    val modelWillChange: Boolean
        get() = fromModel != toModel
}

/** Activity-level chat dispatcher. It never stores or exposes OAuth credential material. */
class IntegratedChatHostController(
    context: Context,
    private val viewModel: ChatViewModel,
    private val alpineHost: IntegratedAlpineLlmHost,
    private val codexRuntime: CodexAppServerRuntime? = null,
) : AutoCloseable {
    private data class PendingApproval(
        val prompt: PendingFastChatFallback,
        val answer: CompletableDeferred<Boolean>,
    )

    private val appContext = context.applicationContext
    private val direct = DirectChatHostController(appContext, viewModel)
    private val codexLinks = AndroidCodexThreadLinkStore(appContext)
    private val codexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codexOperationLock = Mutex()
    private val codexModelLock = Mutex()
    private val codexRestartPending = AtomicBoolean(false)
    private var directConnections: List<ChatBackendConnection> = emptyList()
    private var codexClient: CodexAppServerClient? = null
    private var codexAuthController: CodexAuthController? = null
    private var codexAuthJob: Job? = null
    private var codexSession: CodexAgentChatSession? = null
    private var codexModelIds: List<String> = emptyList()
    private val mutableCodexAuth = MutableStateFlow<CodexAuthState>(CodexAuthState.SignedOut)
    val codexAuth: StateFlow<CodexAuthState> = mutableCodexAuth.asStateFlow()
    val isCodexEnabled: Boolean get() = codexRuntime != null
    private val approvalLock = Any()
    private var pendingApproval: PendingApproval? = null
    private val mutableFallback = MutableStateFlow<PendingFastChatFallback?>(null)
    val pendingFallback: StateFlow<PendingFastChatFallback?> = mutableFallback.asStateFlow()

    fun refreshConnections() {
        directConnections = direct.snapshotConnections()
        publishConnections()
        if (codexRuntime != null) {
            codexScope.launch { refreshCodex() }
        }
    }

    fun selectModel(profileId: String, model: String) {
        if (profileId == CodexAgentChatSession.PROFILE_ID) {
            val client = codexClient ?: return
            if (model !in codexModelIds) return
            val descriptor = CodexAgentChatSession.descriptor(model, codexModelIds)
            codexSession = CodexAgentChatSession(descriptor, client, codexLinks)
            viewModel.selectModel(profileId, model)
            publishConnections()
            return
        }
        directConnections = direct.selectModelAndSnapshot(profileId, model)
        publishConnections()
    }

    fun selectExecutionMode(mode: ChatExecutionMode) {
        viewModel.selectExecutionMode(mode)
        publishConnections()
    }

    fun send(text: String) {
        val state = viewModel.state.value
        if (state.executionMode == ChatExecutionMode.FAST_CHAT) {
            if (state.selectedProfileId == CodexAgentChatSession.PROFILE_ID) {
                codexSession?.let { viewModel.send(text, it) }
                return
            }
            direct.send(text)
            return
        }
        val providerSession = selectedSession() ?: return
        val routed = RoutedChatBackendSession(
            descriptor = providerSession.descriptor,
            requestedMode = ChatExecutionMode.ALPINE_WORKSPACE,
            routerProvider = { alpineHost.routerFor(providerSession) },
            fallbackAuthorizer = ChatFallbackAuthorizer(::requestFallbackApproval),
        )
        viewModel.send(text, routed)
    }

    fun startAlpine(): CompletionStage<AlpineLlmBridgeHealth> =
        selectedSession()?.let(alpineHost::start) ?: unavailableProvider()

    fun restartAlpine(): CompletionStage<AlpineLlmBridgeHealth> =
        selectedSession()?.let(alpineHost::restart) ?: unavailableProvider()

    fun startCodexBrowserLogin(onAuthorizationReady: (URI) -> Boolean) {
        runCodexOperation { auth ->
            val login = auth.startBrowserLogin()
            val opened = withContext(Dispatchers.Main.immediate) {
                onAuthorizationReady(login.authorizationUri)
            }
            if (!opened) auth.failPendingLogin(CodexAppServerErrorCode.BROWSER_UNAVAILABLE)
        }
    }

    fun startCodexDeviceCodeLogin(onAuthorizationReady: (URI) -> Boolean) {
        runCodexOperation { auth ->
            val login = auth.startDeviceCodeLogin()
            val opened = withContext(Dispatchers.Main.immediate) {
                onAuthorizationReady(login.verificationUri)
            }
            if (!opened) auth.failPendingLogin(CodexAppServerErrorCode.BROWSER_UNAVAILABLE)
        }
    }

    fun openCodexAuthorization(uri: URI, opener: (URI) -> Boolean) {
        runCodexOperation { auth ->
            val opened = withContext(Dispatchers.Main.immediate) { opener(uri) }
            if (!opened) auth.failPendingLogin(CodexAppServerErrorCode.BROWSER_UNAVAILABLE)
        }
    }

    fun cancelCodexLogin() = runCodexOperation(CodexAuthController::cancelLogin)

    fun logoutCodex() {
        // Logout must not race an active/background Codex turn. Waiting for ChatViewModel's
        // targeted cancellation lets CodexAgentChatSession send its exactly-once interrupt first
        // without stopping unrelated direct or Alpine generations.
        viewModel.stopStreamingByProfile(CodexAgentChatSession.PROFILE_ID) {
            runCodexOperation(CodexAuthController::logout)
        }
    }

    fun retryCodexConnection() {
        if (codexRuntime != null) codexScope.launch { refreshCodex() }
    }

    /**
     * Performs a credential-preserving, controlled App Server restart. Active Codex turns finish
     * their cancellation cleanup before the process is replaced; direct Provider and Alpine
     * generations are not touched.
     */
    fun restartCodexConnection() {
        val runtime = codexRuntime ?: return
        if (!codexRestartPending.compareAndSet(false, true)) return
        viewModel.stopStreamingByProfile(CodexAgentChatSession.PROFILE_ID) {
            codexScope.launch {
                try {
                    codexOperationLock.withLock {
                        try {
                            codexAuthJob?.cancel()
                            codexAuthJob = null
                            codexClient = null
                            codexAuthController = null
                            mutableCodexAuth.value = CodexAuthState.Checking
                            clearCodexSession()
                            runtime.restart()
                            val connection = runtime.connectWithAuth()
                            val auth = bindCodexClient(connection.client, connection.auth)
                            auth.refresh()
                        } catch (failure: Throwable) {
                            mutableCodexAuth.value = CodexAuthState.Failed(
                                failure.safeCodexCode(),
                            )
                            clearCodexSession()
                        }
                    }
                } finally {
                    codexRestartPending.set(false)
                }
            }
        }
    }

    fun resolveFallback(approved: Boolean) {
        val pending = synchronized(approvalLock) {
            pendingApproval.also { pendingApproval = null }
        } ?: return
        mutableFallback.value = null
        pending.answer.complete(approved)
    }

    private fun selectedSession(): ChatCompletionSession? {
        val state = viewModel.state.value
        val profileId = state.selectedProfileId ?: return null
        val model = state.selectedModel ?: return null
        return direct.session(profileId, model)
    }

    private fun publishConnections() {
        val codex = codexSession?.takeIf {
            mutableCodexAuth.value == CodexAuthState.SignedIn &&
                viewModel.state.value.executionMode == ChatExecutionMode.FAST_CHAT
        }?.let { session ->
            ChatBackendConnection(
                descriptor = session.descriptor,
                state = ChatBackendConnectionState.AVAILABLE,
                session = session,
            )
        }
        viewModel.updateConnections(directConnections + listOfNotNull(codex))
    }

    private suspend fun refreshCodex() = codexOperationLock.withLock {
        val runtime = codexRuntime ?: return@withLock
        try {
            val connection = runtime.connectWithAuth()
            val client = connection.client
            val auth = bindCodexClient(client, connection.auth)
            auth.refresh()
        } catch (failure: Throwable) {
            mutableCodexAuth.value = CodexAuthState.Failed(failure.safeCodexCode())
            clearCodexSession()
        }
    }

    private suspend fun bindCodexClient(
        client: CodexAppServerClient,
        auth: CodexAuthController,
    ): CodexAuthController {
        if (codexClient === client && codexAuthController === auth) return auth
        codexAuthJob?.cancel()
        codexClient = client
        codexAuthController = auth
        codexAuthJob = codexScope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.state.collect { state ->
                mutableCodexAuth.value = state
                if (state == CodexAuthState.SignedIn) {
                    refreshCodexModels(client)
                } else {
                    clearCodexSession()
                }
            }
        }
        return auth
    }

    private suspend fun refreshCodexModels(client: CodexAppServerClient) = codexModelLock.withLock {
        try {
            val models = client.models()
            if (models.isEmpty()) {
                throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            }
            val ids = models.map { it.id }
            val current = withContext(Dispatchers.Main.immediate) {
                viewModel.state.value.selectedModel
                    ?.takeIf { viewModel.state.value.selectedProfileId == CodexAgentChatSession.PROFILE_ID }
            }
            val selected = current?.takeIf { it in ids }
                ?: models.firstOrNull { it.isDefault }?.id
                ?: ids.first()
            withContext(Dispatchers.Main.immediate) {
                codexModelIds = ids
                val descriptor = CodexAgentChatSession.descriptor(selected, ids)
                codexSession = CodexAgentChatSession(descriptor, client, codexLinks)
                publishConnections()
            }
        } catch (failure: Throwable) {
            mutableCodexAuth.value = CodexAuthState.Failed(failure.safeCodexCode())
            clearCodexSession()
        }
    }

    private suspend fun clearCodexSession() {
        withContext(Dispatchers.Main.immediate) {
            codexSession = null
            codexModelIds = emptyList()
            publishConnections()
        }
    }

    private fun runCodexOperation(action: suspend (CodexAuthController) -> Unit) {
        if (codexRuntime == null) return
        codexScope.launch {
            codexOperationLock.withLock {
                try {
                    val connection = codexRuntime.connectWithAuth()
                    action(bindCodexClient(connection.client, connection.auth))
                } catch (failure: Throwable) {
                    mutableCodexAuth.value = CodexAuthState.Failed(failure.safeCodexCode())
                    clearCodexSession()
                }
            }
        }
    }

    private fun Throwable.safeCodexCode(): CodexAppServerErrorCode {
        if (this is CancellationException) throw this
        return (this as? CodexAppServerException)?.code ?: CodexAppServerErrorCode.UNKNOWN
    }

    private suspend fun requestFallbackApproval(
        request: ChatFallbackApprovalRequest,
    ): Boolean {
        val approval = PendingApproval(
            prompt = PendingFastChatFallback(
                reason = request.reason,
                fromModel = request.fromModel,
                toModel = request.toModel,
            ),
            answer = CompletableDeferred(),
        )
        synchronized(approvalLock) {
            pendingApproval?.answer?.complete(false)
            pendingApproval = approval
        }
        mutableFallback.value = approval.prompt
        return try {
            approval.answer.await()
        } finally {
            val clear = synchronized(approvalLock) {
                if (pendingApproval === approval) {
                    pendingApproval = null
                    true
                } else {
                    false
                }
            }
            if (clear) mutableFallback.value = null
        }
    }

    private fun unavailableProvider(): CompletionStage<AlpineLlmBridgeHealth> =
        CompletableFuture<AlpineLlmBridgeHealth>().also {
            it.completeExceptionally(IllegalStateException("authenticated Provider is required"))
        }

    override fun close() {
        resolveFallback(false)
        codexAuthJob?.cancel()
        codexScope.cancel()
        direct.close()
    }
}
