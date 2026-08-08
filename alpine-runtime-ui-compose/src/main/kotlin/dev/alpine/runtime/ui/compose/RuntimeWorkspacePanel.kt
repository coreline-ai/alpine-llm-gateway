package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.alpine.workspace.api.WorkspaceEntry
import dev.alpine.workspace.api.WorkspaceEntryType
import dev.alpine.workspace.api.WorkspaceDiffLineKind
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceHostOperation
import dev.alpine.workspace.api.WorkspaceHostState
import dev.alpine.workspace.api.WorkspacePath
import dev.alpine.workspace.api.workspaceTextDiff

/** App-neutral workspace explorer/editor. SAF and share actions are supplied by the Android host. */
@Composable
fun RuntimeWorkspacePanel(
    state: WorkspaceHostState,
    onRefresh: () -> Unit,
    onNavigate: (WorkspacePath) -> Unit,
    onOpen: (WorkspacePath) -> Unit,
    onSave: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRenameSelected: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onSearch: (String) -> Unit,
    onRequestImport: () -> Unit,
    onRequestExport: (WorkspacePath) -> Unit,
    onRequestShare: (WorkspacePath) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(state.selectedFile, state.editorText) { mutableStateOf(state.editorText) }
    var searchText by remember { mutableStateOf(state.searchQuery) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var newName by remember { mutableStateOf("") }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember(state.selectedFile) { mutableStateOf(state.selectedFile?.name.orEmpty()) }
    var deleteOpen by remember { mutableStateOf(false) }
    var diffOpen by remember { mutableStateOf(false) }
    val idle = state.operation == WorkspaceHostOperation.IDLE
    val selected = state.selectedFile

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("작업공간 파일", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.directory.value.ifEmpty { "/" }} · 앱 전용 /workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = idle) { Text("새로고침") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { state.directory.parent?.let(onNavigate) },
                    enabled = idle && state.directory.parent != null,
                    modifier = Modifier.weight(1f),
                ) { Text("상위") }
                OutlinedButton(
                    onClick = { createKind = CreateKind.FILE; newName = "" },
                    enabled = idle,
                    modifier = Modifier.weight(1f),
                ) { Text("파일 추가") }
                OutlinedButton(
                    onClick = { createKind = CreateKind.DIRECTORY; newName = "" },
                    enabled = idle,
                    modifier = Modifier.weight(1f),
                ) { Text("폴더 추가") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .testTag("workspace_file_list"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.entries.isEmpty()) {
                    item { Text("표시할 파일이 없습니다.", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.entries, key = { it.path.value }) { entry ->
                    WorkspaceEntryRow(entry, enabled = idle, onNavigate = onNavigate, onOpen = onOpen)
                }
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("파일명 검색") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("workspace_search"),
                trailingIcon = {
                    TextButton(onClick = { onSearch(searchText) }, enabled = idle) { Text("검색") }
                },
            )
            if (state.searchQuery.isNotBlank()) {
                Text("검색 결과 ${state.searchResults.size}개", style = MaterialTheme.typography.bodySmall)
                state.searchResults.take(8).forEach { entry ->
                    TextButton(
                        onClick = {
                            if (entry.type == WorkspaceEntryType.DIRECTORY) onNavigate(entry.path) else onOpen(entry.path)
                        },
                        enabled = idle,
                    ) { Text(entry.path.value) }
                }
            }
            selected?.let { file ->
                Text("편집: ${file.value}", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = idle,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .testTag("workspace_editor"),
                    label = { Text("텍스트 편집기") },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(draft) },
                        enabled = idle,
                        modifier = Modifier.weight(1f).testTag("workspace_save"),
                    ) { Text("저장") }
                    OutlinedButton(
                        onClick = onRequestImport,
                        enabled = idle,
                        modifier = Modifier.weight(1f).testTag("workspace_import"),
                    ) { Text("가져오기") }
                    OutlinedButton(
                        onClick = { onRequestExport(file) },
                        enabled = idle,
                        modifier = Modifier.weight(1f).testTag("workspace_export"),
                    ) { Text("내보내기") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onRequestShare(file) },
                        enabled = idle,
                        modifier = Modifier.testTag("workspace_share"),
                    ) { Text("공유") }
                    TextButton(
                        onClick = { diffOpen = true },
                        enabled = idle && draft != state.editorText,
                        modifier = Modifier.testTag("workspace_diff"),
                    ) { Text("변경 비교") }
                    TextButton(onClick = { renameOpen = true }, enabled = idle) { Text("이름 변경") }
                    TextButton(
                        onClick = { deleteOpen = true },
                        enabled = idle,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("삭제") }
                }
            } ?: Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRequestImport,
                    enabled = idle,
                    modifier = Modifier.weight(1f).testTag("workspace_import"),
                ) {
                    Text("파일 가져오기")
                }
            }
            state.lastErrorCode?.let { error ->
                Text(
                    workspaceErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "텍스트 읽기 2 MB · 쓰기 16 MB까지. 심볼릭 링크와 경로 이탈은 허용하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    createKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { createKind = null },
            title = { Text(if (kind == CreateKind.FILE) "새 파일" else "새 폴더") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("이름") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (kind == CreateKind.FILE) onCreateFile(newName) else onCreateDirectory(newName)
                        createKind = null
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("생성") }
            },
            dismissButton = { TextButton(onClick = { createKind = null }) { Text("취소") } },
        )
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("파일 이름 변경") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                Button(
                    onClick = { onRenameSelected(renameText); renameOpen = false },
                    enabled = renameText.isNotBlank(),
                ) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("취소") } },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("파일을 삭제할까요?") },
            text = { Text("선택한 파일은 작업공간에서 제거됩니다.") },
            confirmButton = {
                Button(
                    onClick = { onDeleteSelected(); deleteOpen = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("취소") } },
        )
    }
    if (diffOpen && selected != null) {
        val diff = workspaceTextDiff(state.editorText, draft)
        AlertDialog(
            onDismissRequest = { diffOpen = false },
            title = { Text("저장 전 변경 비교") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (diff.changed) {
                            "삭제 ${diff.removedLineCount}줄 · 추가 ${diff.addedLineCount}줄"
                        } else {
                            "저장된 파일과 같습니다."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        items(diff.lines) { line ->
                            val prefix = when (line.kind) {
                                WorkspaceDiffLineKind.CONTEXT -> " "
                                WorkspaceDiffLineKind.REMOVED -> "−"
                                WorkspaceDiffLineKind.ADDED -> "+"
                                WorkspaceDiffLineKind.TRUNCATED -> "…"
                            }
                            val color = when (line.kind) {
                                WorkspaceDiffLineKind.REMOVED -> MaterialTheme.colorScheme.error
                                WorkspaceDiffLineKind.ADDED -> MaterialTheme.colorScheme.primary
                                WorkspaceDiffLineKind.TRUNCATED -> MaterialTheme.colorScheme.onSurfaceVariant
                                WorkspaceDiffLineKind.CONTEXT -> Color.Unspecified
                            }
                            Text(
                                "$prefix ${line.text}",
                                color = color,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    Text(
                        "표시는 최대 160줄·각 2,048자까지이며 저장 동작은 수행하지 않습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { diffOpen = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun WorkspaceEntryRow(
    entry: WorkspaceEntry,
    enabled: Boolean,
    onNavigate: (WorkspacePath) -> Unit,
    onOpen: (WorkspacePath) -> Unit,
) {
    OutlinedButton(
        onClick = {
            if (entry.type == WorkspaceEntryType.DIRECTORY) onNavigate(entry.path) else onOpen(entry.path)
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (entry.type == WorkspaceEntryType.DIRECTORY) "▸ ${entry.path.name}/" else entry.path.name,
            modifier = Modifier.weight(1f),
        )
        if (entry.type == WorkspaceEntryType.FILE) Text("${entry.sizeBytes} B", style = MaterialTheme.typography.labelSmall)
    }
}

private enum class CreateKind { FILE, DIRECTORY }

private fun workspaceErrorMessage(error: WorkspaceErrorCode): String = when (error) {
    WorkspaceErrorCode.NOT_FOUND -> "파일을 찾을 수 없습니다. 목록을 새로고침해 주세요."
    WorkspaceErrorCode.ALREADY_EXISTS -> "같은 이름의 파일 또는 폴더가 있습니다."
    WorkspaceErrorCode.INVALID_PATH -> "작업공간 안의 안전한 이름만 사용할 수 있습니다."
    WorkspaceErrorCode.NOT_A_FILE -> "텍스트 파일만 열 수 있습니다."
    WorkspaceErrorCode.NOT_A_DIRECTORY -> "폴더를 선택해 주세요."
    WorkspaceErrorCode.DIRECTORY_NOT_EMPTY -> "비어 있지 않은 폴더는 삭제할 수 없습니다."
    WorkspaceErrorCode.LIMIT_EXCEEDED -> "파일 크기 또는 목록 한도를 초과했습니다."
    WorkspaceErrorCode.SYMLINK_NOT_ALLOWED -> "심볼릭 링크는 작업공간에서 지원하지 않습니다."
    WorkspaceErrorCode.NOT_TEXT -> "텍스트 편집기는 이진 파일을 열지 않습니다."
    WorkspaceErrorCode.IO_FAILED -> "파일 작업을 완료하지 못했습니다. 다시 시도해 주세요."
}
