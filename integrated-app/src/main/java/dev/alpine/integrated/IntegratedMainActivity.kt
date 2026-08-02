package dev.alpine.integrated

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not authorize a hidden daemon. Android still
        // exposes the user-started FGS in Task Manager, and the SDK keeps its stop contract.
        app.runtimeController.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                IntegratedApp(app, app.runtimeController, ::startRuntimeWithNotificationConsent)
            }
        }
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
    app: IntegratedApplication,
    controller: RuntimeHostController,
    onStartRuntime: () -> Unit,
) {
    var mode by remember { mutableStateOf(app.savedMode()) }
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
                    mode = selected
                    app.saveMode(selected)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (mode) {
                ChatExecutionMode.FAST_CHAT -> FastChatShell(Modifier.padding(16.dp))
                ChatExecutionMode.ALPINE_WORKSPACE -> AlpineWorkspace(
                    state = runtimeState,
                    controller = controller,
                    onStartRuntime = onStartRuntime,
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
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = mode == ChatExecutionMode.ALPINE_WORKSPACE,
            onClick = { onModeChanged(ChatExecutionMode.ALPINE_WORKSPACE) },
            label = { Text("Alpine 작업") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FastChatShell(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("빠른 채팅 모드", style = MaterialTheme.typography.headlineSmall)
            Text("Android에서 Provider에 직접 연결하는 경로입니다. Alpine 설치 없이 가장 빠르게 답변을 받을 수 있습니다.")
            Text(
                "Phase 5의 안전 라우터와 Android 직접 Provider backend가 이 앱에 포함되어 있습니다. " +
                    "계정별 OAuth 화면은 기존 데모와 분리된 상태로 유지하며, Provider 요청 후에는 자동 fallback하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { /* Account-specific chat host is injected by the product app. */ }, enabled = false) {
                Text("Provider 로그인 연결 후 사용")
            }
        }
    }
}

@Composable
private fun AlpineWorkspace(
    state: RuntimeHostState,
    controller: RuntimeHostController,
    onStartRuntime: () -> Unit,
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
    )
}

private val PACKAGE_ALLOWLIST = setOf("git", "python3", "py3-pip", "curl", "openssh-client")
