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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme
import dev.alpine.chat.feature.ui.theme.AlpineTheme
import dev.alpine.chat.provider.android.activity.ProviderProfilesActivity
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.runtime.api.RuntimePackageAllowlistPolicy
import dev.alpine.runtime.api.RuntimePackageApproval
import dev.alpine.runtime.api.RuntimePackageInstallRequest
import dev.alpine.runtime.api.RuntimeTerminalSignal
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.bridge.LlmBridgeErrorCode
import dev.alpine.runtime.bridge.LlmBridgeLifecycleState
import dev.alpine.runtime.host.RuntimeHostController
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.ui.compose.RuntimeWorkspaceScreen
import java.util.concurrent.CompletableFuture

class IntegratedMainActivity : ComponentActivity() {
    private val app by lazy { application as IntegratedApplication }
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var chatHost: IntegratedChatHostController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingNotificationAction: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not authorize a hidden daemon. Android still
        // exposes the user-started FGS in Task Manager, and the SDK keeps its stop contract.
        pendingNotificationAction?.also { pendingNotificationAction = null }?.invoke()
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
            AlpineChatTheme(darkTheme = false, dynamicColor = false) {
                IntegratedApp(
                    controller = app.runtimeController,
                    alpineHost = app.alpineLlmHost,
                    chatViewModel = chatViewModel,
                    chatHost = chatHost,
                    onManageProviders = ::openProviderProfiles,
                    onStartAlpine = { startAlpineWithNotificationConsent() },
                    onRestartAlpine = { startAlpineWithNotificationConsent(restart = true) },
                    onRecoveryAction = ::handleRecoveryAction,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegratedApp(
    controller: RuntimeHostController,
    alpineHost: IntegratedAlpineLlmHost,
    chatViewModel: ChatViewModel,
    chatHost: IntegratedChatHostController,
    onManageProviders: () -> Unit,
    onStartAlpine: () -> Unit,
    onRestartAlpine: () -> Unit,
    onRecoveryAction: (ChatRecoveryAction) -> Unit,
) {
    val chatState = chatViewModel.state.collectAsStateWithLifecycle().value
    val mode = chatState.executionMode
    var runtimeState by remember { mutableStateOf(controller.currentState()) }
    var bridgeState by remember { mutableStateOf(alpineHost.currentState()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(controller, alpineHost) {
        val runtimeSubscription = controller.addStateListener { next ->
            mainHandler.post { runtimeState = next }
        }
        val bridgeSubscription = alpineHost.addStateListener { next ->
            mainHandler.post { bridgeState = next }
        }
        onDispose {
            runtimeSubscription.close()
            bridgeSubscription.close()
        }
    }
    val pendingFallback = chatHost.pendingFallback.collectAsStateWithLifecycle().value

    Scaffold(
        topBar = {
            AlpineBrandHeader()
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
                    bridgeState = bridgeState,
                    controller = controller,
                    chatState = chatState,
                    chatViewModel = chatViewModel,
                    chatHost = chatHost,
                    onManageProviders = onManageProviders,
                    onStartAlpine = onStartAlpine,
                    onRestartAlpine = onRestartAlpine,
                    onRecoveryAction = onRecoveryAction,
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
}

@Composable
private fun AlpineBrandHeader() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inversePrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = ">_",
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ALPINE AI WORKSPACE",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "LOCAL LINUX · EXTERNAL LLM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = "READY",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: ChatExecutionMode,
    onModeChanged: (ChatExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentOption(
            selected = mode == ChatExecutionMode.FAST_CHAT,
            onClick = { onModeChanged(ChatExecutionMode.FAST_CHAT) },
            label = "빠른 채팅",
            modifier = Modifier
                .weight(1f)
                .testTag("mode_fast_chat"),
        )
        SegmentOption(
            selected = mode == ChatExecutionMode.ALPINE_WORKSPACE,
            onClick = { onModeChanged(ChatExecutionMode.ALPINE_WORKSPACE) },
            label = "Alpine 작업",
            modifier = Modifier
                .weight(1f)
                .testTag("mode_alpine_workspace"),
        )
    }
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlpineWorkspace(
    runtimeState: RuntimeHostState,
    bridgeState: AlpineWorkspaceLlmState,
    controller: RuntimeHostController,
    chatState: dev.alpine.chat.feature.ui.ChatUiState,
    chatViewModel: ChatViewModel,
    chatHost: IntegratedChatHostController,
    onManageProviders: () -> Unit,
    onStartAlpine: () -> Unit,
    onRestartAlpine: () -> Unit,
    onRecoveryAction: (ChatRecoveryAction) -> Unit,
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
                SegmentOption(
                    selected = selectedPane == AlpineWorkspacePane.CHAT,
                    onClick = { selectedPane = AlpineWorkspacePane.CHAT },
                    label = "Gateway 채팅",
                    modifier = Modifier.weight(1f).testTag("alpine_chat_pane"),
                )
                SegmentOption(
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
                onSendTerminal = { controller.sendTerminalInput(it) },
                onInterruptTerminal = { controller.signalTerminal(RuntimeTerminalSignal.INTERRUPT) },
                onCloseTerminal = { controller.closeTerminal() },
                onInstallPackages = { packages ->
                    controller.installPackages(
                        request = RuntimePackageInstallRequest(packages),
                        policy = RuntimePackageAllowlistPolicy(PACKAGE_ALLOWLIST),
                        // RuntimePackagePanel already showed the explicit host confirmation dialog.
                        approval = RuntimePackageApproval { CompletableFuture.completedFuture(true) },
                    )
                },
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

private val PACKAGE_ALLOWLIST = setOf("git", "python3", "py3-pip", "curl", "openssh-client")
