package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.DefaultRuntimeDeveloperToolProfiles
import dev.alpine.runtime.api.RuntimeDeveloperToolProfile
import dev.alpine.runtime.api.RuntimePackageInstallOutcome
import dev.alpine.runtime.api.RuntimePackageAction
import dev.alpine.runtime.api.RuntimePackageCatalog
import dev.alpine.runtime.api.RuntimePackageEstimate
import dev.alpine.runtime.api.RuntimePackageMutationOutcome
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.host.RuntimeToolSmokeOutcome
import java.util.Locale

data class RuntimePackageInput(
    val packages: List<String>,
    val valid: Boolean,
)

/** Compatibility alias; reusable profile/argv validation lives in :alpine-runtime-api. */
typealias RuntimeToolProfile = RuntimeDeveloperToolProfile

val DefaultRuntimeToolProfiles: List<RuntimeToolProfile>
    get() = DefaultRuntimeDeveloperToolProfiles

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimePackagePanel(
    state: RuntimeHostState,
    allowlistedPackages: Set<String>,
    onApprovedInstall: (List<String>) -> Unit,
    toolProfiles: List<RuntimeToolProfile> = DefaultRuntimeToolProfiles,
    packageCatalog: RuntimePackageCatalog = RuntimePackageCatalog(emptyList()),
    removablePackages: Set<String> = emptySet(),
    onApprovedMutation: (RuntimePackageAction, List<String>) -> Unit = { _, _ -> },
    onRunToolSmoke: (RuntimeToolProfile) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuntimePackageAction.INSTALL) }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    val parsed = parseRuntimePackageInput(input, allowlistedPackages)
    val packageEstimate = packageCatalog.estimate(parsed.packages)
    val removableSelection = parsed.packages.all(removablePackages::contains)
    val actionSelectionValid = parsed.valid &&
        (action != RuntimePackageAction.REMOVE || removableSelection)
    val matchingPackages = allowlistedPackages.sorted().filter { packageName ->
        search.trim().isBlank() || packageName.contains(search.trim(), ignoreCase = true)
    }

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
            Text("Alpine 패키지", style = MaterialTheme.typography.titleMedium)
            Text(
                "허용 목록에서만 선택합니다. 설치·업데이트는 network를 사용하며, 삭제는 개발 도구만 허용됩니다. 용량은 표시된 snapshot 기준입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimePackageAction.entries.forEach { candidate ->
                    FilterChip(
                        selected = action == candidate,
                        onClick = { action = candidate },
                        enabled = state.sessionActive && state.operation == RuntimeHostOperation.IDLE,
                        label = { Text(candidate.userLabel()) },
                    )
                }
            }
            Text(
                action.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val availableProfiles = toolProfiles.filter { profile ->
                profile.packages.all(allowlistedPackages::contains)
            }
            if (availableProfiles.isNotEmpty()) {
                Text("개발 도구 프로필", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableProfiles.forEach { profile ->
                        val selected = profile.packages.all(parsed.packages::contains)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = parsed.packages.toMutableList().apply {
                                    if (selected) removeAll(profile.packages.toSet()) else addAll(profile.packages)
                                }
                                input = next.distinct().joinToString(" ")
                            },
                            enabled = state.sessionActive,
                            label = { Text(profile.label) },
                        )
                    }
                }
                Text(
                    "설치 뒤 고정 first-run 검사를 실행하거나 터미널에서 버전을 직접 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableProfiles.forEach { profile ->
                        OutlinedButton(
                            onClick = { onRunToolSmoke(profile) },
                            enabled = state.sessionActive && state.operation == RuntimeHostOperation.IDLE,
                            modifier = Modifier.testTag("runtime_tool_smoke_${profile.id}"),
                        ) { Text("${profile.label} 검사") }
                    }
                }
                state.toolSmokeOutcome?.let { outcome ->
                    val profile = availableProfiles.firstOrNull { it.id == state.toolSmokeProfileId }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = if (outcome == RuntimeToolSmokeOutcome.COMPLETED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (outcome == RuntimeToolSmokeOutcome.COMPLETED) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${profile?.label ?: "개발 도구"} ${if (outcome == RuntimeToolSmokeOutcome.COMPLETED) "검사 완료" else "검사 실패"}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                if (outcome == RuntimeToolSmokeOutcome.COMPLETED) {
                                    "고정된 version 확인 명령이 성공했습니다. 명령 출력은 화면에 저장하지 않습니다."
                                } else {
                                    "도구가 설치되지 않았거나 실행할 수 없습니다. 설치 상태와 Runtime 상태를 확인하세요."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(120) },
                label = { Text("허용 패키지 검색") },
                placeholder = { Text("예: git, python") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("runtime_package_search"),
                enabled = state.sessionActive,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                matchingPackages.forEach { packageName ->
                    FilterChip(
                        selected = packageName in parsed.packages,
                        onClick = {
                            val next = parsed.packages.toMutableList().apply {
                                if (packageName in this) remove(packageName) else add(packageName)
                            }
                            input = next.joinToString(" ")
                        },
                        enabled = state.sessionActive,
                        label = { Text(packageName) },
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("선택한 패키지") },
                placeholder = { Text("예: git python3") },
                supportingText = {
                    if (input.isNotBlank() && !parsed.valid) {
                        Text("허용 목록에 있는 정확한 패키지 이름만 입력해 주세요.")
                    } else if (action == RuntimePackageAction.REMOVE && input.isNotBlank() && !removableSelection) {
                        Text("Python Gateway와 Runtime 핵심 package는 삭제할 수 없습니다.")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("runtime_package_selection"),
                enabled = state.sessionActive,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            if (parsed.packages.isNotEmpty()) {
                RuntimePackageEstimateSummary(
                    estimate = packageEstimate,
                    action = action,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { pending = parsed.packages },
                    enabled = actionSelectionValid &&
                        state.sessionActive &&
                        state.operation == RuntimeHostOperation.IDLE,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.testTag("runtime_package_review"),
                ) { Text("${action.userLabel()} 내용 확인") }
            }
            state.packageOutcome?.let { outcome ->
                val success = outcome == RuntimePackageInstallOutcome.INSTALLED
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (success) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            when (outcome) {
                                RuntimePackageInstallOutcome.INSTALLED -> "설치 완료"
                                RuntimePackageInstallOutcome.POLICY_DENIED -> "정책에서 거부됨"
                                RuntimePackageInstallOutcome.APPROVAL_DECLINED -> "사용자가 취소함"
                                RuntimePackageInstallOutcome.PREFLIGHT_FAILED -> "사전 확인 실패"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            when (outcome) {
                                RuntimePackageInstallOutcome.INSTALLED -> "터미널에서 설치된 도구를 사용할 수 있습니다."
                                RuntimePackageInstallOutcome.POLICY_DENIED -> "allowlist에 있는 정확한 패키지만 선택하세요."
                                RuntimePackageInstallOutcome.APPROVAL_DECLINED -> "Runtime은 변경되지 않았습니다."
                                RuntimePackageInstallOutcome.PREFLIGHT_FAILED ->
                                    "실제 설치는 시작하지 않았습니다. network·repository 상태를 확인한 뒤 다시 시도하세요."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.packageMutationOutcome?.let { outcome ->
                val succeeded = outcome == RuntimePackageMutationOutcome.COMPLETED
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (succeeded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${state.packageMutationAction?.userLabel() ?: "패키지 작업"} ${outcome.userLabel()}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            if (succeeded) "작업이 완료되었습니다. 필요하면 Gateway 상태를 다시 확인하세요."
                            else "정책 또는 사용자 승인에 의해 Runtime은 변경되지 않았습니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    pending?.let { packages ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("패키지를 ${action.userActionLabel()}할까요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(packages.joinToString(separator = "\n") { "• $it" })
                    RuntimePackageEstimateSummary(
                        estimate = packageCatalog.estimate(packages),
                        action = action,
                    )
                    Text(
                        action.confirmationMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    if (action == RuntimePackageAction.INSTALL) {
                        onApprovedInstall(packages)
                    } else {
                        onApprovedMutation(action, packages)
                    }
                }) { Text("승인하고 ${action.userActionLabel()}") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun RuntimePackageEstimateSummary(
    estimate: RuntimePackageEstimate,
    action: RuntimePackageAction,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("패키지 정보 (표시용 snapshot)", style = MaterialTheme.typography.labelLarge)
            if (estimate.metadata.isEmpty()) {
                Text(
                    "선택한 패키지의 snapshot 메타데이터가 없습니다. 설치 전 실제 repository 결과를 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                estimate.metadata.forEach { metadata ->
                    val resolved = if (metadata.resolvedPackageName == metadata.packageName) {
                        ""
                    } else {
                        " → ${metadata.resolvedPackageName}"
                    }
                    Text(
                        "${metadata.packageName}$resolved · ${metadata.version}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${metadata.licenseExpression} · 내려받기 ${formatPackageBytes(metadata.downloadBytes)} · 설치 payload ${formatPackageBytes(metadata.installedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                val source = estimate.metadata.map { it.snapshotId }.distinct().joinToString()
                if (estimate.totalBytesOverflowed) {
                    Text(
                        "합계는 snapshot 표시 범위를 넘었습니다. 실제 설치 용량으로 사용하지 마세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                } else {
                    Text(
                        "합계(알려진 항목): 내려받기 ${formatPackageBytes(estimate.downloadBytes)} · 설치 payload ${formatPackageBytes(estimate.installedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "기준: $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (estimate.missingPackageNames.isNotEmpty()) {
                Text(
                    "메타데이터 없음: ${estimate.missingPackageNames.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            Text(
                if (action == RuntimePackageAction.REMOVE) {
                    "삭제는 repository network가 필요하지 않습니다. 의존성 정리는 실제 apk 결과에 따라 달라질 수 있습니다."
                } else {
                    "network가 필요합니다. 이 값은 선택 package archive/payload만이며 의존성, index, cache, filesystem 여유 공간과 현재 repository 해석은 포함하지 않습니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun formatPackageBytes(bytes: Long): String {
    require(bytes >= 0) { "bytes must not be negative" }
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}

private fun RuntimePackageAction.userLabel(): String = when (this) {
    RuntimePackageAction.INSTALL -> "설치"
    RuntimePackageAction.REMOVE -> "삭제"
    RuntimePackageAction.UPDATE -> "업데이트"
}

private fun RuntimePackageAction.userActionLabel(): String = when (this) {
    RuntimePackageAction.INSTALL -> "설치"
    RuntimePackageAction.REMOVE -> "삭제"
    RuntimePackageAction.UPDATE -> "업데이트"
}

private fun RuntimePackageAction.description(): String = when (this) {
    RuntimePackageAction.INSTALL -> "설치: Alpine repository network와 앱 전용 저장 공간을 사용합니다."
    RuntimePackageAction.REMOVE -> "삭제: 허용된 선택형 개발 도구만 제거합니다. Gateway 핵심 Python은 보호됩니다."
    RuntimePackageAction.UPDATE -> "업데이트: 선택한 허용 package만 업데이트합니다. 전체 Runtime upgrade는 수행하지 않습니다."
}

private fun RuntimePackageAction.confirmationMessage(): String = when (this) {
    RuntimePackageAction.INSTALL -> "먼저 현재 repository index로 변경 없는 사전 확인을 실행합니다. 통과한 경우에만 Alpine repository network와 앱 전용 저장 공간을 사용해 설치합니다."
    RuntimePackageAction.REMOVE -> "먼저 변경 없는 사전 확인을 실행합니다. 통과한 경우 선택한 개발 도구와 사용되지 않는 의존성이 제거될 수 있으며, 현재 Gateway는 자동으로 다시 시작되지 않습니다."
    RuntimePackageAction.UPDATE -> "먼저 현재 repository index로 변경 없는 사전 확인을 실행합니다. 통과한 경우 선택한 package를 업데이트하며 network와 추가 저장 공간이 필요할 수 있습니다."
}

private fun RuntimePackageMutationOutcome.userLabel(): String = when (this) {
    RuntimePackageMutationOutcome.COMPLETED -> "완료"
    RuntimePackageMutationOutcome.POLICY_DENIED -> "정책 거부"
    RuntimePackageMutationOutcome.APPROVAL_DECLINED -> "사용자 취소"
    RuntimePackageMutationOutcome.PREFLIGHT_FAILED -> "사전 확인 실패"
}
