package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val actions = state.actionAvailability()
    val presentation = state.runtimeState.toPresentation()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = presentation.label },
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
                        else -> onHealth
                    },
                    enabled = actions.install || actions.start || actions.stop || actions.health,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            actions.install -> "설치"
                            actions.start -> "시작"
                            actions.stop -> "종료"
                            else -> "상태 확인"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onHealth,
                    enabled = actions.health,
                    modifier = Modifier.weight(1f),
                ) { Text("상태 검사") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRepair,
                    enabled = actions.repair,
                    modifier = Modifier.weight(1f),
                ) { Text("복구") }
                OutlinedButton(
                    onClick = onReset,
                    enabled = actions.reset,
                    modifier = Modifier.weight(1f),
                ) { Text("초기화") }
            }
        }
    }
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
