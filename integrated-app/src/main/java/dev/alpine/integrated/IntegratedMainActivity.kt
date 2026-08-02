package dev.alpine.integrated

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alpine.chat.feature.data.AssistantDefaultsStore
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.ui.ChatViewModel
import dev.alpine.chat.feature.ui.screens.chat.AlpineChatScreen
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme
import dev.alpine.chat.provider.android.DirectChatHostController
import dev.alpine.chat.provider.android.activity.ProviderProfilesActivity
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.runtime.api.RuntimePackageAllowlistPolicy
import dev.alpine.runtime.api.RuntimePackageApproval
import dev.alpine.runtime.api.RuntimePackageInstallRequest
import dev.alpine.runtime.api.RuntimeTerminalSignal
import dev.alpine.runtime.host.RuntimeHostController
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.ui.compose.RuntimeWorkspaceScreen
import java.util.concurrent.CompletableFuture

class IntegratedMainActivity : ComponentActivity() {
    private val app by lazy { application as IntegratedApplication }
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var directChat: DirectChatHostController
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not authorize a hidden daemon. Android still
        // exposes the user-started FGS in Task Manager, and the SDK keeps its stop contract.
        app.runtimeController.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantDefaults = AssistantDefaultsStore(this)
        chatViewModel = ViewModelProvider(
            this,
            ChatViewModel.Factory(
                repository = ConversationRepository(ConversationStore(this)),
                initialAssistantSelection = assistantDefaults.load(),
                persistAssistantDefaults = assistantDefaults::save,
            ),
        )[ChatViewModel::class.java]
        directChat = DirectChatHostController(this, chatViewModel)
        setContent {
            AlpineChatTheme {
                IntegratedApp(
                    controller = app.runtimeController,
                    chatViewModel = chatViewModel,
                    directChat = directChat,
                    onManageProviders = ::openProviderProfiles,
                    onStartRuntime = ::startRuntimeWithNotificationConsent,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        directChat.refreshConnections()
    }

    override fun onStop() {
        chatViewModel.flushPersistence()
        super.onStop()
    }

    override fun onDestroy() {
        directChat.close()
        super.onDestroy()
    }

    private fun openProviderProfiles() {
        startActivity(Intent(this, ProviderProfilesActivity::class.java))
    }

    private fun startRuntimeWithNotificationConsent() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            app.runtimeController.start()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegratedApp(
    controller: RuntimeHostController,
    chatViewModel: ChatViewModel,
    directChat: DirectChatHostController,
    onManageProviders: () -> Unit,
    onStartRuntime: () -> Unit,
) {
    val chatState = chatViewModel.state.collectAsStateWithLifecycle().value
    val mode = chatState.executionMode
    var runtimeState by remember { mutableStateOf(controller.currentState()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(controller) {
        val subscription = controller.addStateListener { next ->
            mainHandler.post { runtimeState = next }
        }
        onDispose { subscription.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Alpine AI Workspace") })
        },
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
                    onSelectModel = directChat::selectModel,
                    onSelectAssistantMode = chatViewModel::selectAssistantMode,
                    onResetAssistantMode = chatViewModel::resetAssistantMode,
                    onNewChat = chatViewModel::newConversation,
                    onSelectConversation = chatViewModel::selectConversation,
                    onRenameConversation = chatViewModel::renameConversation,
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onManageProviders = onManageProviders,
                    onDraftChange = chatViewModel::updateDraft,
                    onSend = directChat::send,
                    onStop = chatViewModel::stopStreaming,
                    failure = chatState.failure,
                    onDismissFailure = chatViewModel::dismissFailure,
                    onRetry = chatViewModel::retry,
                    modifier = Modifier.weight(1f),
                )
                ChatExecutionMode.ALPINE_WORKSPACE -> AlpineWorkspace(
                    state = runtimeState,
                    controller = controller,
                    onStartRuntime = onStartRuntime,
                    modifier = Modifier.weight(1f),
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
        FilterChip(
            selected = mode == ChatExecutionMode.FAST_CHAT,
            onClick = { onModeChanged(ChatExecutionMode.FAST_CHAT) },
            label = { Text("빠른 채팅") },
            modifier = Modifier
                .weight(1f)
                .testTag("mode_fast_chat"),
        )
        FilterChip(
            selected = mode == ChatExecutionMode.ALPINE_WORKSPACE,
            onClick = { onModeChanged(ChatExecutionMode.ALPINE_WORKSPACE) },
            label = { Text("Alpine 작업") },
            modifier = Modifier
                .weight(1f)
                .testTag("mode_alpine_workspace"),
        )
    }
}

@Composable
private fun AlpineWorkspace(
    state: RuntimeHostState,
    controller: RuntimeHostController,
    onStartRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeWorkspaceScreen(
        state = state,
        allowlistedPackages = PACKAGE_ALLOWLIST,
        onInstall = { controller.install() },
        onStart = onStartRuntime,
        onStop = { controller.stop() },
        onHealth = { controller.refreshHealth() },
        onRepair = { controller.repair() },
        onReset = { controller.reset() },
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
        modifier = modifier,
    )
}

private val PACKAGE_ALLOWLIST = setOf("git", "python3", "py3-pip", "curl", "openssh-client")
