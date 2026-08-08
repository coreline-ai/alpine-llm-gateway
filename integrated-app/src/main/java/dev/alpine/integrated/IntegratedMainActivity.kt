package dev.alpine.integrated

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.feature.ui.designsystem.AlpineProductHeader
import dev.alpine.chat.feature.ui.designsystem.AlpineSegmentOption
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme
import dev.alpine.chat.feature.ui.theme.AlpineTheme
import dev.alpine.chat.provider.android.activity.ProviderProfilesActivity
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.runtime.api.RuntimePackageAllowlistPolicy
import dev.alpine.runtime.api.RuntimePackageAction
import dev.alpine.runtime.api.RuntimePackageApproval
import dev.alpine.runtime.api.RuntimePackageCatalog
import dev.alpine.runtime.api.RuntimePackageInstallRequest
import dev.alpine.runtime.api.RuntimePackageMetadata
import dev.alpine.runtime.api.RuntimePackageMutationAllowlistPolicy
import dev.alpine.runtime.api.RuntimePackageMutationRequest
import dev.alpine.runtime.api.RuntimeTerminalSignal
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.bridge.LlmBridgeErrorCode
import dev.alpine.runtime.bridge.LlmBridgeLifecycleState
import dev.alpine.runtime.host.RuntimeHostController
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.ui.compose.RuntimeWorkspaceScreen
import dev.alpine.workspace.android.WorkspaceSafTransfer
import dev.alpine.workspace.android.WorkspaceShareFilePublisher
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceHostController
import dev.alpine.workspace.api.WorkspaceHostState
import dev.alpine.workspace.api.WorkspaceOperationException
import dev.alpine.workspace.api.WorkspacePath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class IntegratedMainActivity : ComponentActivity() {
    private val app by lazy { application as IntegratedApplication }
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var chatHost: IntegratedChatHostController
    private val modeGuideStore by lazy { IntegratedModeGuideStore(this) }
    private val workspaceSafTransfer by lazy { WorkspaceSafTransfer(contentResolver) }
    private val workspaceSharePublisher by lazy { WorkspaceShareFilePublisher(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingNotificationAction: (() -> Unit)? = null
    private var pendingWorkspaceImportDirectory: WorkspacePath? = null
    private var pendingWorkspaceExportPath: WorkspacePath? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not authorize a hidden daemon. Android still
        // exposes the user-started FGS in Task Manager, and the SDK keeps its stop contract.
        pendingNotificationAction?.also { pendingNotificationAction = null }?.invoke()
    }
    private val workspaceDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val directory = pendingWorkspaceImportDirectory ?: WorkspacePath.ROOT
        pendingWorkspaceImportDirectory = null
        if (uri == null) return@registerForActivityResult
        Thread {
            runCatching {
                workspaceSafTransfer.readImport(uri, app.workspaceStore.limits.maxWriteBytes)
            }.onSuccess { imported ->
                app.workspaceController.importBytes(directory, imported.name, imported.bytes)
            }.onFailure { error ->
                app.workspaceController.reportExternalFailure(workspaceErrorCode(error))
            }
        }.start()
    }
    private val workspaceDocumentCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val path = pendingWorkspaceExportPath
        pendingWorkspaceExportPath = null
        if (uri == null || path == null) return@registerForActivityResult
        app.workspaceController.readForExport(path).whenComplete { bytes, error ->
            if (error != null || bytes == null) {
                app.workspaceController.reportExternalFailure(workspaceErrorCode(error))
            } else {
                runCatching { workspaceSafTransfer.writeExport(bytes, uri) }
                    .onFailure { writeError ->
                        app.workspaceController.reportExternalFailure(workspaceErrorCode(writeError))
                    }
            }
        }
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
        val assistantDefaults = AssistantDefaultsStore(this)
        chatViewModel = ViewModelProvider(
            this,
            ChatViewModel.Factory(
                repository = ConversationRepository(ConversationStore(this)),
                initialAssistantSelection = assistantDefaults.load(),
                persistAssistantDefaults = assistantDefaults::save,
            ),
        )[ChatViewModel::class.java]
        chatHost = IntegratedChatHostController(this, chatViewModel, app.alpineLlmHost)
        setContent {
            AlpineProductTheme {
                IntegratedApp(
                    controller = app.runtimeController,
                    workspaceController = app.workspaceController,
                    alpineHost = app.alpineLlmHost,
                    chatViewModel = chatViewModel,
                    chatHost = chatHost,
                    onManageProviders = ::openProviderProfiles,
                    onStartAlpine = { startAlpineWithNotificationConsent() },
                    onRestartAlpine = { startAlpineWithNotificationConsent(restart = true) },
                    onRecoveryAction = ::handleRecoveryAction,
                    onWorkspaceImport = ::requestWorkspaceImport,
                    onWorkspaceExport = ::requestWorkspaceExport,
                    onWorkspaceShare = ::requestWorkspaceShare,
                    showModeGuideInitially = modeGuideStore.shouldShowGuide(),
                    onCompleteModeGuide = { selectedMode ->
                        modeGuideStore.markCompleted()
                        chatViewModel.selectExecutionMode(selectedMode)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        chatHost.refreshConnections()
    }

    override fun onStop() {
        chatViewModel.flushPersistence()
        super.onStop()
    }

    override fun onDestroy() {
        chatHost.close()
        super.onDestroy()
    }

    private fun openProviderProfiles() {
        startActivity(Intent(this, ProviderProfilesActivity::class.java))
    }

    private fun startAlpineWithNotificationConsent(
        restart: Boolean = false,
        retryAfterReady: Boolean = false,
    ) {
        val startAction: () -> Unit = {
            val stage = if (restart) chatHost.restartAlpine() else chatHost.startAlpine()
            stage.whenComplete { _, error ->
                if (error == null && retryAfterReady) {
                    mainHandler.post { chatViewModel.retry() }
                }
            }
            Unit
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = startAction
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAction()
        }
    }

    private fun handleRecoveryAction(action: ChatRecoveryAction) {
        when (action) {
            ChatRecoveryAction.RETRY -> chatViewModel.retry()
            ChatRecoveryAction.RECONNECT,
            ChatRecoveryAction.CHECK_SETTINGS,
            -> openProviderProfiles()
            ChatRecoveryAction.INSTALL_RUNTIME -> {
                app.runtimeController.install().whenComplete { _, error ->
                    if (error == null) {
                        mainHandler.post {
                            startAlpineWithNotificationConsent(retryAfterReady = true)
                        }
                    }
                }
            }
            ChatRecoveryAction.REPAIR_RUNTIME -> {
                app.alpineLlmHost.stop().whenComplete { _, _ ->
                    app.runtimeController.repair().whenComplete { _, error ->
                        if (error == null) {
                            mainHandler.post {
                                startAlpineWithNotificationConsent(retryAfterReady = true)
                            }
                        }
                    }
                }
            }
            ChatRecoveryAction.RESTART_RUNTIME ->
                startAlpineWithNotificationConsent(restart = true, retryAfterReady = true)
        }
    }

    private fun requestWorkspaceImport() {
        pendingWorkspaceImportDirectory = app.workspaceController.currentState().directory
        workspaceDocumentPicker.launch(arrayOf("text/plain", "text/*", "application/json", "application/octet-stream"))
    }

    private fun requestWorkspaceExport(path: WorkspacePath) {
        pendingWorkspaceExportPath = path
        workspaceDocumentCreator.launch(path.name.ifBlank { "workspace.txt" })
    }

    private fun requestWorkspaceShare(path: WorkspacePath) {
        app.workspaceController.readForExport(path).whenComplete { bytes, error ->
            if (error != null || bytes == null) {
                app.workspaceController.reportExternalFailure(workspaceErrorCode(error))
                return@whenComplete
            }
            runCatching {
                val target = workspaceSharePublisher.publish(
                    displayName = path.name.ifBlank { "workspace.txt" },
                    bytes = bytes,
                    maxBytes = app.workspaceStore.limits.maxReadBytes,
                )
                FileProvider.getUriForFile(this, "$packageName.workspace-share", target)
            }.onSuccess { uri ->
                mainHandler.post {
                    startActivity(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            }.onFailure { shareError ->
                app.workspaceController.reportExternalFailure(workspaceErrorCode(shareError))
            }
        }
    }

    private fun workspaceErrorCode(error: Throwable?): WorkspaceErrorCode {
        var current = error
        repeat(8) {
            when (current) {
                is WorkspaceOperationException -> return current.errorCode
                is CompletionException -> current = current.cause
                else -> current = current?.cause
            }
        }
        return WorkspaceErrorCode.IO_FAILED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegratedApp(
    controller: RuntimeHostController,
    workspaceController: WorkspaceHostController,
    alpineHost: IntegratedAlpineLlmHost,
    chatViewModel: ChatViewModel,
    chatHost: IntegratedChatHostController,
    onManageProviders: () -> Unit,
    onStartAlpine: () -> Unit,
    onRestartAlpine: () -> Unit,
    onRecoveryAction: (ChatRecoveryAction) -> Unit,
    onWorkspaceImport: () -> Unit,
    onWorkspaceExport: (WorkspacePath) -> Unit,
    onWorkspaceShare: (WorkspacePath) -> Unit,
    showModeGuideInitially: Boolean,
    onCompleteModeGuide: (ChatExecutionMode) -> Unit,
) {
    val chatState = chatViewModel.state.collectAsStateWithLifecycle().value
    val mode = chatState.executionMode
    var runtimeState by remember { mutableStateOf(controller.currentState()) }
    var workspaceState by remember { mutableStateOf(workspaceController.currentState()) }
    var bridgeState by remember { mutableStateOf(alpineHost.currentState()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(controller, alpineHost, workspaceController) {
        val runtimeSubscription = controller.addStateListener { next ->
            mainHandler.post { runtimeState = next }
        }
        val bridgeSubscription = alpineHost.addStateListener { next ->
            mainHandler.post { bridgeState = next }
        }
        val workspaceSubscription = workspaceController.addStateListener { next ->
            mainHandler.post { workspaceState = next }
        }
        onDispose {
            runtimeSubscription.close()
            bridgeSubscription.close()
            workspaceSubscription.close()
        }
    }
    val pendingFallback = chatHost.pendingFallback.collectAsStateWithLifecycle().value
    var showModeGuide by rememberSaveable { mutableStateOf(showModeGuideInitially) }

    Scaffold(
        topBar = {
            AlpineProductHeader(
                title = "ALPINE AI WORKSPACE",
                subtitle = "LOCAL LINUX · EXTERNAL LLM",
                statusLabel = "READY",
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            ModeSelector(
                mode = mode,
                onModeChanged = { selected ->
                    chatViewModel.selectExecutionMode(selected)
                },
                onOpenGuide = { showModeGuide = true },
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("mode_selector"),
            )
            when (mode) {
                ChatExecutionMode.FAST_CHAT -> AlpineChatScreen(
                    state = chatState,
                    onSelectProvider = chatViewModel::selectProvider,
                    onSelectModel = chatHost::selectModel,
                    onSelectAssistantMode = chatViewModel::selectAssistantMode,
                    onResetAssistantMode = chatViewModel::resetAssistantMode,
                    onNewChat = chatViewModel::newConversation,
                    onSelectConversation = chatViewModel::selectConversation,
                    onRenameConversation = chatViewModel::renameConversation,
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onStopConversation = chatViewModel::stopStreaming,
                    onManageProviders = onManageProviders,
                    onDraftChange = chatViewModel::updateDraft,
                    onSend = chatHost::send,
                    onStop = chatViewModel::stopStreaming,
                    failure = chatState.failure,
                    onDismissFailure = chatViewModel::dismissFailure,
                    onRetry = chatViewModel::retry,
                    modifier = Modifier.weight(1f),
                )
                ChatExecutionMode.ALPINE_WORKSPACE -> AlpineWorkspace(
                    runtimeState = runtimeState,
                    workspaceState = workspaceState,
                    bridgeState = bridgeState,
                    controller = controller,
                    workspaceController = workspaceController,
                    chatState = chatState,
                    chatViewModel = chatViewModel,
                    chatHost = chatHost,
                    onManageProviders = onManageProviders,
                    onStartAlpine = onStartAlpine,
                    onRestartAlpine = onRestartAlpine,
                    onRecoveryAction = onRecoveryAction,
                    onWorkspaceImport = onWorkspaceImport,
                    onWorkspaceExport = onWorkspaceExport,
                    onWorkspaceShare = onWorkspaceShare,
                    onStopAlpine = { alpineHost.stop() },
                    onHealthAlpine = { alpineHost.health() },
                    onRepairRuntime = {
                        alpineHost.stop().thenCompose { controller.repair() }
                    },
                    onResetRuntime = {
                        alpineHost.stop().thenCompose { controller.reset() }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    pendingFallback?.let { fallback ->
        FastChatFallbackDialog(
            fallback = fallback,
            onApprove = { chatHost.resolveFallback(true) },
            onDecline = { chatHost.resolveFallback(false) },
        )
    }
    if (showModeGuide) {
        IntegratedModeGuideSheet(
            onDismiss = { showModeGuide = false },
            onStartMode = { selectedMode ->
                onCompleteModeGuide(selectedMode)
                showModeGuide = false
            },
        )
    }
}

@Composable
private fun ModeSelector(
    mode: ChatExecutionMode,
    onModeChanged: (ChatExecutionMode) -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AlpineSegmentOption(
            selected = mode == ChatExecutionMode.FAST_CHAT,
            onClick = { onModeChanged(ChatExecutionMode.FAST_CHAT) },
            label = "빠른 채팅",
            modifier = Modifier
                .weight(1f)
                .testTag("mode_fast_chat"),
        )
        AlpineSegmentOption(
            selected = mode == ChatExecutionMode.ALPINE_WORKSPACE,
            onClick = { onModeChanged(ChatExecutionMode.ALPINE_WORKSPACE) },
            label = "Alpine 작업",
            modifier = Modifier
                .weight(1f)
                .testTag("mode_alpine_workspace"),
        )
        TextButton(
            modifier = Modifier
                .heightIn(min = 52.dp)
                .testTag("open_mode_guide")
                .semantics {
                    contentDescription = "빠른 채팅과 Alpine 작업 모드 안내"
                },
            onClick = onOpenGuide,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("안내")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlpineWorkspace(
    runtimeState: RuntimeHostState,
    workspaceState: WorkspaceHostState,
    bridgeState: AlpineWorkspaceLlmState,
    controller: RuntimeHostController,
    workspaceController: WorkspaceHostController,
    chatState: dev.alpine.chat.feature.ui.ChatUiState,
    chatViewModel: ChatViewModel,
    chatHost: IntegratedChatHostController,
    onManageProviders: () -> Unit,
    onStartAlpine: () -> Unit,
    onRestartAlpine: () -> Unit,
    onRecoveryAction: (ChatRecoveryAction) -> Unit,
    onWorkspaceImport: () -> Unit,
    onWorkspaceExport: (WorkspacePath) -> Unit,
    onWorkspaceShare: (WorkspacePath) -> Unit,
    onStopAlpine: () -> Unit,
    onHealthAlpine: () -> Unit,
    onRepairRuntime: () -> Unit,
    onResetRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPane by remember { mutableStateOf(AlpineWorkspacePane.CHAT) }
    val compactForChatIme =
        selectedPane == AlpineWorkspacePane.CHAT && WindowInsets.isImeVisible
    Column(modifier = modifier.fillMaxSize()) {
        if (!compactForChatIme) {
            AlpineGatewayStatusCard(
                runtimeState = runtimeState,
                bridgeState = bridgeState,
                hasProvider = chatState.selectedProfileId != null,
                onStart = onStartAlpine,
                onRestart = onRestartAlpine,
                onStop = onStopAlpine,
                onHealth = onHealthAlpine,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlpineSegmentOption(
                    selected = selectedPane == AlpineWorkspacePane.CHAT,
                    onClick = { selectedPane = AlpineWorkspacePane.CHAT },
                    label = "Gateway 채팅",
                    modifier = Modifier.weight(1f).testTag("alpine_chat_pane"),
                )
                AlpineSegmentOption(
                    selected = selectedPane == AlpineWorkspacePane.TOOLS,
                    onClick = { selectedPane = AlpineWorkspacePane.TOOLS },
                    label = "터미널·도구",
                    modifier = Modifier.weight(1f).testTag("alpine_tools_pane"),
                )
            }
        }
        when (selectedPane) {
            AlpineWorkspacePane.CHAT -> AlpineChatScreen(
                state = chatState,
                onSelectProvider = chatViewModel::selectProvider,
                onSelectModel = chatHost::selectModel,
                onSelectAssistantMode = chatViewModel::selectAssistantMode,
                onResetAssistantMode = chatViewModel::resetAssistantMode,
                onNewChat = chatViewModel::newConversation,
                onSelectConversation = chatViewModel::selectConversation,
                onRenameConversation = chatViewModel::renameConversation,
                onDeleteConversation = chatViewModel::deleteConversation,
                onStopConversation = chatViewModel::stopStreaming,
                onManageProviders = onManageProviders,
                onDraftChange = chatViewModel::updateDraft,
                onSend = chatHost::send,
                onStop = chatViewModel::stopStreaming,
                failure = chatState.failure,
                onDismissFailure = chatViewModel::dismissFailure,
                onRetry = chatViewModel::retry,
                onRecoveryAction = onRecoveryAction,
                modifier = Modifier.weight(1f),
            )
            AlpineWorkspacePane.TOOLS -> RuntimeWorkspaceScreen(
                state = runtimeState,
                allowlistedPackages = PACKAGE_ALLOWLIST,
                onInstall = { controller.install() },
                onStart = onStartAlpine,
                onStop = onStopAlpine,
                onHealth = {
                    controller.refreshHealth()
                    onHealthAlpine()
                },
                onRepair = {
                    onRepairRuntime()
                },
                onReset = {
                    onResetRuntime()
                },
                onOpenTerminal = { controller.openTerminal() },
                onOpenAdditionalTerminal = { controller.openAdditionalTerminal() },
                onSelectTerminal = { terminalId -> controller.selectTerminal(terminalId) },
                onRenameTerminal = { terminalId, title -> controller.renameTerminal(terminalId, title) },
                onSendTerminal = { controller.sendTerminalInput(it) },
                onSendTerminalRaw = { controller.sendTerminalInput(it, appendNewline = false) },
                onInterruptTerminal = { controller.signalTerminal(RuntimeTerminalSignal.INTERRUPT) },
                onCloseTerminal = { controller.closeTerminal() },
                onTerminateTerminal = { controller.signalTerminal(RuntimeTerminalSignal.TERMINATE) },
                onKillTerminal = { controller.signalTerminal(RuntimeTerminalSignal.KILL) },
                onInstallPackages = { packages ->
                    controller.installPackages(
                        request = RuntimePackageInstallRequest(packages),
                        policy = RuntimePackageAllowlistPolicy(PACKAGE_ALLOWLIST),
                        // RuntimePackagePanel already showed the explicit host confirmation dialog.
                        approval = RuntimePackageApproval { CompletableFuture.completedFuture(true) },
                    )
                },
                packageCatalog = PACKAGE_CATALOG,
                removablePackages = PACKAGE_REMOVABLE_ALLOWLIST,
                onMutatePackages = { action, packages ->
                    controller.mutatePackages(
                        request = RuntimePackageMutationRequest(action, packages),
                        policy = RuntimePackageMutationAllowlistPolicy(
                            allowedPackages = PACKAGE_ALLOWLIST,
                            removablePackages = PACKAGE_REMOVABLE_ALLOWLIST,
                        ),
                        // RuntimePackagePanel already displayed the action-specific confirmation.
                        approval = RuntimePackageApproval { CompletableFuture.completedFuture(true) },
                    )
                },
                onRunToolSmoke = controller::runToolSmoke,
                workspaceState = workspaceState,
                onWorkspaceRefresh = { workspaceController.refresh() },
                onWorkspaceNavigate = { path -> workspaceController.navigate(path) },
                onWorkspaceOpen = { path -> workspaceController.open(path) },
                onWorkspaceSave = { text -> workspaceController.saveSelected(text) },
                onWorkspaceCreateFile = { name -> workspaceController.createTextFile(name) },
                onWorkspaceCreateDirectory = { name -> workspaceController.createDirectory(name) },
                onWorkspaceRenameSelected = { name -> workspaceController.renameSelected(name) },
                onWorkspaceDeleteSelected = { workspaceController.deleteSelected() },
                onWorkspaceSearch = { query -> workspaceController.search(query) },
                onWorkspaceImport = onWorkspaceImport,
                onWorkspaceExport = onWorkspaceExport,
                onWorkspaceShare = onWorkspaceShare,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private enum class AlpineWorkspacePane { CHAT, TOOLS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlpineGatewayStatusCard(
    runtimeState: RuntimeHostState,
    bridgeState: AlpineWorkspaceLlmState,
    hasProvider: Boolean,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = bridgeState.operation == AlpineWorkspaceLlmOperation.IDLE
    val runtimeReady = runtimeState.runtimeState.lifecycle == RuntimeLifecycleState.READY
    val running = bridgeState.lifecycle == LlmBridgeLifecycleState.RUNNING
    val statusColors = AlpineTheme.statusColors
    val containerColor = when {
        bridgeState.healthy == true -> statusColors.connected
        !runtimeReady || bridgeState.errorCode != null -> statusColors.warning
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("alpine_gateway_status"),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Alpine LLM Gateway", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(bridgeState.profileLabel, bridgeState.model).joinToString(" · ")
                            .ifBlank { "연결할 Provider와 모델을 선택하세요." },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        bridgeState.lifecycle.userLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (runtimeState.runtimeState.lifecycle == RuntimeLifecycleState.NOT_INSTALLED) {
                Text("터미널·도구에서 Alpine Runtime을 먼저 설치하세요.")
            }
            if (bridgeState.operation != AlpineWorkspaceLlmOperation.IDLE) {
                Text(
                    bridgeState.operation.userLabel(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("alpine_gateway_operation"),
                )
                if (bridgeState.operation == AlpineWorkspaceLlmOperation.RECOVERING) {
                    Text(
                        "이전 대화 요청과 터미널 명령은 자동으로 다시 실행하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            bridgeState.errorCode?.let { code ->
                Text(
                    code.userMessage(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (bridgeState.healthy == true) {
                Text("Runtime · HostBridge · Gateway · protocol 정상", color = MaterialTheme.colorScheme.primary)
            } else if (bridgeState.checks.isNotEmpty()) {
                val failed = bridgeState.checks.filterValues { !it }.keys.joinToString()
                if (failed.isNotBlank()) Text("확인 필요: $failed", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val startEnabled = idle && runtimeReady && hasProvider && !running
                val restartEnabled = idle && hasProvider && bridgeState.lifecycle in setOf(
                    LlmBridgeLifecycleState.RUNNING,
                    LlmBridgeLifecycleState.FAILED,
                )
                val healthEnabled = idle && running
                val stopEnabled = idle && bridgeState.lifecycle != LlmBridgeLifecycleState.STOPPED
                val disabledContainer = MaterialTheme.colorScheme.surfaceContainerHigh
                val disabledContent = MaterialTheme.colorScheme.onSurfaceVariant
                val outlinedColors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContentColor = disabledContent,
                )
                Button(
                    onClick = onStart,
                    enabled = startEnabled,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = disabledContainer,
                        disabledContentColor = disabledContent,
                    ),
                    modifier = Modifier.testTag("alpine_gateway_start"),
                ) { Text("시작") }
                OutlinedButton(
                    onClick = onRestart,
                    enabled = restartEnabled,
                    colors = outlinedColors,
                    border = BorderStroke(
                        1.25.dp,
                        if (restartEnabled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.testTag("alpine_gateway_restart"),
                ) { Text("재시작") }
                OutlinedButton(
                    onClick = onHealth,
                    enabled = healthEnabled,
                    colors = outlinedColors,
                    border = BorderStroke(
                        1.25.dp,
                        if (healthEnabled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.testTag("alpine_gateway_health"),
                ) { Text("상태") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = stopEnabled,
                    colors = outlinedColors,
                    border = BorderStroke(
                        1.25.dp,
                        if (stopEnabled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.testTag("alpine_gateway_stop"),
                ) { Text("종료") }
            }
        }
    }
}

@Composable
private fun FastChatFallbackDialog(
    fallback: PendingFastChatFallback,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("이 요청만 빠른 채팅으로 보낼까요?") },
        text = {
            Text(
                buildString {
                    append("Alpine Gateway가 아직 준비되지 않았습니다. 사용자 승인 후에만 Android 직접 Provider 경로를 사용합니다. ")
                    append("전송이 시작된 뒤에는 다른 경로로 자동 재전송하지 않습니다.")
                    if (fallback.modelWillChange) {
                        append(" 모델이 ${fallback.fromModel}에서 ${fallback.toModel}(으)로 변경됩니다.")
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onApprove, modifier = Modifier.testTag("fallback_approve")) {
                Text("이 요청 허용")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.testTag("fallback_decline")) {
                Text("취소")
            }
        },
    )
}

private fun LlmBridgeLifecycleState.userLabel(): String = when (this) {
    LlmBridgeLifecycleState.STOPPED -> "중지됨"
    LlmBridgeLifecycleState.STARTING -> "시작 중"
    LlmBridgeLifecycleState.RUNNING -> "실행 중"
    LlmBridgeLifecycleState.STOPPING -> "종료 중"
    LlmBridgeLifecycleState.FAILED -> "복구 필요"
}

private fun AlpineWorkspaceLlmOperation.userLabel(): String = when (this) {
    AlpineWorkspaceLlmOperation.IDLE -> "대기"
    AlpineWorkspaceLlmOperation.STARTING -> "Gateway 시작 중"
    AlpineWorkspaceLlmOperation.CHECKING_HEALTH -> "Gateway 상태 확인 중"
    AlpineWorkspaceLlmOperation.RESTARTING -> "Gateway 재시작 중"
    AlpineWorkspaceLlmOperation.RECOVERING -> "Gateway 자동 복구 중"
    AlpineWorkspaceLlmOperation.STOPPING -> "Gateway 종료 중"
}

private fun LlmBridgeErrorCode.userMessage(): String = when (this) {
    LlmBridgeErrorCode.INVALID_STATE -> "Runtime 상태를 확인한 뒤 다시 시도하세요."
    LlmBridgeErrorCode.ARTIFACT_INVALID -> "Gateway 설치 파일 검증에 실패했습니다."
    LlmBridgeErrorCode.PROTOCOL_MISMATCH -> "Gateway protocol 버전이 호환되지 않습니다."
    LlmBridgeErrorCode.CAPABILITY_WRITE_FAILED -> "보안 capability 파일을 만들 수 없습니다."
    LlmBridgeErrorCode.PYTHON_UNAVAILABLE -> "Alpine에 Python 3.11 이상이 필요합니다."
    LlmBridgeErrorCode.GATEWAY_INSTALL_FAILED -> "Python Gateway 설치에 실패했습니다."
    LlmBridgeErrorCode.GATEWAY_START_FAILED -> "Python Gateway를 시작하지 못했습니다."
    LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED -> "Python Gateway 상태 검사가 실패했습니다."
    LlmBridgeErrorCode.BRIDGE_HEALTH_FAILED -> "Android HostBridge 상태 검사가 실패했습니다."
    LlmBridgeErrorCode.RUNTIME_FAILED -> "Alpine Runtime 실행 상태를 복구해야 합니다."
    LlmBridgeErrorCode.INTERNAL_ERROR -> "내부 연결 오류가 발생했습니다."
}

private val PACKAGE_ALLOWLIST = setOf(
    "git",
    "python3",
    "py3-pip",
    "curl",
    "openssh-client",
    "nodejs",
    "npm",
)

/** Gateway Python stays protected; these optional developer tools are the only removable set. */
private val PACKAGE_REMOVABLE_ALLOWLIST = setOf(
    "git",
    "py3-pip",
    "curl",
    "openssh-client",
    "nodejs",
    "npm",
)

/**
 * Informational snapshot for the bundled Alpine 3.21 arm64 Runtime only.
 *
 * This is not a live package solver and is intentionally not used to authorize or construct an
 * apk command.  The package panel explains that dependencies, repository changes, index/cache
 * downloads and filesystem overhead can make the actual transaction larger.
 */
private val PACKAGE_CATALOG = RuntimePackageCatalog(
    listOf(
        packageMetadata(
            packageName = "git",
            version = "2.47.3-r0",
            license = "GPL-2.0-only",
            downloadBytes = 3_414_900,
            installedBytes = 6_997_971,
            repository = "main",
        ),
        packageMetadata(
            packageName = "python3",
            version = "3.12.13-r0",
            license = "PSF-2.0",
            downloadBytes = 8_078_719,
            installedBytes = 26_338_596,
            repository = "main",
        ),
        packageMetadata(
            packageName = "py3-pip",
            version = "24.3.1-r0",
            license = "MIT",
            downloadBytes = 1_695_108,
            installedBytes = 5_656_209,
            repository = "community",
        ),
        packageMetadata(
            packageName = "curl",
            version = "8.14.1-r2",
            license = "curl",
            downloadBytes = 165_197,
            installedBytes = 274_878,
            repository = "main",
        ),
        packageMetadata(
            packageName = "openssh-client",
            resolvedPackageName = "openssh-client-default",
            version = "9.9_p2-r0",
            license = "SSH-OpenSSH",
            downloadBytes = 367_738,
            installedBytes = 854_664,
            repository = "main",
        ),
        packageMetadata(
            packageName = "nodejs",
            version = "22.23.2-r0",
            license = "MIT",
            downloadBytes = 17_436_759,
            installedBytes = 48_276_512,
            repository = "main",
        ),
        packageMetadata(
            packageName = "npm",
            version = "10.9.1-r0",
            license = "Artistic-2.0",
            downloadBytes = 2_184_455,
            installedBytes = 7_933_969,
            repository = "community",
        ),
    ),
)

private fun packageMetadata(
    packageName: String,
    version: String,
    license: String,
    downloadBytes: Long,
    installedBytes: Long,
    repository: String,
    resolvedPackageName: String = packageName,
): RuntimePackageMetadata = RuntimePackageMetadata(
    packageName = packageName,
    resolvedPackageName = resolvedPackageName,
    version = version,
    licenseExpression = license,
    downloadBytes = downloadBytes,
    installedBytes = installedBytes,
    repository = repository,
    architecture = "aarch64",
    snapshotId = "Alpine v3.21 aarch64 APKINDEX · 2026-08-08",
    sourceUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/$repository/aarch64/APKINDEX.tar.gz",
)
