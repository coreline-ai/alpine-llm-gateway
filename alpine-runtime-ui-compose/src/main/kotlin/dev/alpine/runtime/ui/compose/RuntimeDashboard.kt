package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState

data class RuntimeActionAvailability(
    val install: Boolean,
    val start: Boolean,
    val stop: Boolean,
    val health: Boolean,
    val repair: Boolean,
    val reset: Boolean,
)

fun RuntimeHostState.actionAvailability(): RuntimeActionAvailability {
    val idle = operation == RuntimeHostOperation.IDLE
    return RuntimeActionAvailability(
        install = idle && runtimeState.lifecycle == RuntimeLifecycleState.NOT_INSTALLED,
        start = idle && runtimeState.lifecycle == RuntimeLifecycleState.READY,
        stop = idle && runtimeState.lifecycle == RuntimeLifecycleState.RUNNING,
        health = idle && runtimeState.lifecycle in setOf(
            RuntimeLifecycleState.READY,
            RuntimeLifecycleState.RUNNING,
            RuntimeLifecycleState.REPAIR_REQUIRED,
        ),
        repair = idle && runtimeState.lifecycle in setOf(
            RuntimeLifecycleState.REPAIR_REQUIRED,
            RuntimeLifecycleState.FAILED,
        ),
        reset = idle && runtimeState.lifecycle !in setOf(
            RuntimeLifecycleState.INSTALLING,
            RuntimeLifecycleState.STARTING,
            RuntimeLifecycleState.STOPPING,
        ),
    )
}

@Composable
fun RuntimeDashboard(
    state: RuntimeHostState,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHealth: () -> Unit,
    onRepair: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }
    val actions = state.actionAvailability()
    val presentation = state.runtimeState.toPresentation()
    val primaryEnabled = actions.install || actions.start || actions.stop || actions.repair || actions.health
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = presentation.label },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Alpine Runtime", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.runtimeState.activeVersion ?: "런타임 버전 확인 전",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RuntimeStateBadge(state.runtimeState)
            }
            RuntimeStatusRail(
                label = presentation.label,
                message = state.runtimeState.lifecycle.statusGuidance(),
                error = state.lastErrorCode != null,
            )
            state.runtimeState.progressPercent?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.health?.let { health ->
                Text(
                    if (health.healthy) "상태 검사: 정상" else "상태 검사: 복구 필요",
                    color = if (health.healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            state.lastErrorCode?.let { error ->
                Text(
                    text = error.userMessage(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = when {
                        actions.install -> onInstall
                        actions.start -> onStart
                        actions.stop -> onStop
                        actions.repair -> onRepair
                        else -> onHealth
                    },
                    enabled = primaryEnabled,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            actions.install -> "설치"
                            actions.start -> "시작"
                            actions.stop -> "종료"
                            actions.repair -> "복구"
                            else -> "상태 확인"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onHealth,
                    enabled = actions.health,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (actions.health) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("상태 검사") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showResetConfirmation = true },
                    enabled = actions.reset,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (actions.reset) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Runtime 초기화") }
            }
        }
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Runtime을 초기화할까요?") },
            text = { Text("설치된 Alpine 실행 환경을 제거합니다. Workspace 보존 정책은 현재 Runtime 설정을 따릅니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmation = false
                        onReset()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("초기화") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirmation = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun RuntimeStatusRail(
    label: String,
    message: String,
    error: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun RuntimeLifecycleState.statusGuidance(): String = when (this) {
    RuntimeLifecycleState.NOT_INSTALLED -> "설치한 뒤 Alpine 작업과 터미널을 사용할 수 있습니다."
    RuntimeLifecycleState.INSTALLING -> "설치가 끝날 때까지 앱을 종료하지 마세요."
    RuntimeLifecycleState.READY -> "Runtime을 시작하면 터미널과 Gateway를 사용할 수 있습니다."
    RuntimeLifecycleState.STARTING -> "Alpine session과 Host Bridge를 준비하고 있습니다."
    RuntimeLifecycleState.RUNNING -> "터미널과 허용된 Linux 도구를 사용할 수 있습니다."
    RuntimeLifecycleState.STOPPING -> "실행 중인 session을 안전하게 정리하고 있습니다."
    RuntimeLifecycleState.REPAIR_REQUIRED -> "복구를 실행한 뒤 상태를 다시 확인하세요."
    RuntimeLifecycleState.FAILED -> "오류 원인을 정리한 뒤 복구를 실행하세요."
}

internal fun RuntimeErrorCode.userMessage(): String = when (this) {
    RuntimeErrorCode.UNSUPPORTED_ABI -> "이 기기에서는 지원되지 않는 실행 환경입니다."
    RuntimeErrorCode.ARTIFACT_NOT_FOUND -> "설치 파일을 찾을 수 없습니다."
    RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED -> "설치 파일 검증에 실패했습니다."
    RuntimeErrorCode.STORAGE_UNAVAILABLE -> "저장 공간을 확인해 주세요."
    RuntimeErrorCode.INSTALL_CANCELLED -> "설치가 취소되었습니다."
    RuntimeErrorCode.PROCESS_START_FAILED -> "Alpine을 시작하지 못했습니다."
    RuntimeErrorCode.PROCESS_EXITED -> "실행 세션이 종료되었습니다."
    RuntimeErrorCode.COMMAND_FAILED -> "명령 실행에 실패했습니다."
    RuntimeErrorCode.TERMINAL_UNAVAILABLE -> "터미널을 열 수 없습니다."
    RuntimeErrorCode.TERMINAL_RESIZE_UNSUPPORTED -> "이 실행 환경은 터미널 시작 크기만 지원합니다."
    RuntimeErrorCode.HEALTH_CHECK_FAILED -> "상태 검사에 실패했습니다."
    RuntimeErrorCode.BRIDGE_UNAVAILABLE -> "LLM 연결을 사용할 수 없습니다."
    RuntimeErrorCode.INVALID_REQUEST -> "요청 내용을 확인해 주세요."
    RuntimeErrorCode.INTERNAL_ERROR -> "내부 오류가 발생했습니다. 다시 시도해 주세요."
}
