package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.RuntimePackageInstallOutcome
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState

data class RuntimePackageInput(
    val packages: List<String>,
    val valid: Boolean,
)

fun parseRuntimePackageInput(value: String, allowlist: Set<String>): RuntimePackageInput {
    val packages = value.split(',', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val namePattern = Regex("[a-z0-9][a-z0-9+_.-]{0,127}")
    return RuntimePackageInput(
        packages = packages,
        valid = packages.isNotEmpty() &&
            packages.size <= 32 &&
            packages.all(namePattern::matches) &&
            packages.all(allowlist::contains),
    )
}

@Composable
fun RuntimePackagePanel(
    state: RuntimeHostState,
    allowlistedPackages: Set<String>,
    onApprovedInstall: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    val parsed = parseRuntimePackageInput(input, allowlistedPackages)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.5.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Alpine 패키지 설치")
            Text("허용된 패키지: ${allowlistedPackages.sorted().joinToString()}")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("패키지 이름") },
                supportingText = {
                    if (input.isNotBlank() && !parsed.valid) {
                        Text("허용 목록에 있는 정확한 패키지 이름만 입력해 주세요.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.sessionActive,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { pending = parsed.packages },
                    enabled = parsed.valid &&
                        state.sessionActive &&
                        state.operation == RuntimeHostOperation.IDLE,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("설치 요청") }
            }
            state.packageOutcome?.let { outcome ->
                Text(
                    when (outcome) {
                        RuntimePackageInstallOutcome.INSTALLED -> "패키지 설치 완료"
                        RuntimePackageInstallOutcome.POLICY_DENIED -> "정책에서 허용되지 않은 패키지입니다."
                        RuntimePackageInstallOutcome.APPROVAL_DECLINED -> "패키지 설치가 취소되었습니다."
                    },
                )
            }
        }
    }

    pending?.let { packages ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("패키지를 설치할까요?") },
            text = { Text("Alpine 환경에 ${packages.joinToString()} 패키지를 설치합니다. 네트워크와 저장 공간을 사용합니다.") },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    onApprovedInstall(packages)
                }) { Text("승인하고 설치") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null }) { Text("취소") }
            },
        )
    }
}
