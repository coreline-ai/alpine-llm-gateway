package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeState

data class RuntimeStatePresentation(
    val label: String,
    val actionable: Boolean,
)

fun RuntimeState.toPresentation(): RuntimeStatePresentation = when (lifecycle) {
    RuntimeLifecycleState.NOT_INSTALLED -> RuntimeStatePresentation("설치 필요", true)
    RuntimeLifecycleState.INSTALLING -> RuntimeStatePresentation(
        progressPercent?.let { "설치 중 $it%" } ?: "설치 중",
        false,
    )
    RuntimeLifecycleState.READY -> RuntimeStatePresentation("실행 준비", true)
    RuntimeLifecycleState.STARTING -> RuntimeStatePresentation("시작 중", false)
    RuntimeLifecycleState.RUNNING -> RuntimeStatePresentation("실행 중", true)
    RuntimeLifecycleState.STOPPING -> RuntimeStatePresentation("종료 중", false)
    RuntimeLifecycleState.REPAIR_REQUIRED -> RuntimeStatePresentation("복구 필요", true)
    RuntimeLifecycleState.FAILED -> RuntimeStatePresentation("확인 필요", true)
}

/** Optional Compose status surface. Hosts may ignore this module and render RuntimeState directly. */
@Composable
fun RuntimeStateBadge(
    state: RuntimeState,
    modifier: Modifier = Modifier,
) {
    val presentation = state.toPresentation()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = presentation.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
