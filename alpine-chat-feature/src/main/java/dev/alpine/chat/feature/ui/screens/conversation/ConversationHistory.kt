package dev.alpine.chat.feature.ui.screens.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alpine.chat.feature.model.ConversationGenerationState
import dev.alpine.chat.feature.model.ConversationSummary
import dev.alpine.chat.feature.ui.ConnectedProviderOption
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationHistory(
    conversations: List<ConversationSummary>,
    activeConversationId: String,
    providers: List<ConnectedProviderOption>,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onStop: (String) -> Unit,
    onClose: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 360.dp)
            .testTag("conversation_history_list"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "대화 기록",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "이 기기에 안전하게 저장됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    onNewChat()
                    onClose()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("history_new_chat")
                    .semantics { contentDescription = "대화 기록에서 새 대화 시작" },
            ) {
                Icon(Icons.Outlined.AddComment, contentDescription = null)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("history_close")
                    .semantics { contentDescription = "대화 기록 닫기" },
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null)
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(conversations, key = { it.id }) { summary ->
                ConversationRow(
                    summary = summary,
                    selected = summary.id == activeConversationId,
                    providerLabel = providers
                        .firstOrNull { it.profileId == summary.selectedProfileId }
                        ?.label,
                    onSelect = {
                        onSelect(summary.id)
                        onClose()
                    },
                    onRename = { renameTarget = summary },
                    onDelete = { deleteTarget = summary },
                    onStop = { onStop(summary.id) },
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameConversationDialog(
            initialTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                onRename(target.id, title)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("대화를 삭제할까요?") },
            text = { Text("이 기기에 저장된 메시지가 삭제되며 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag("confirm_delete_conversation"),
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    selected: Boolean,
    providerLabel: String?,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag("conversation_item_${summary.id}")
            .clickable(
                onClickLabel = "${summary.title} 대화 열기",
                onClick = onSelect,
            )
            .semantics {
                stateDescription = summary.accessibilityState(selected)
            },
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    summary.badgeLabel()?.let { label ->
                        Spacer(Modifier.size(6.dp))
                        AssistChip(
                            onClick = onSelect,
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
                Text(
                    text = summary.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        providerLabel ?: summary.selectedProfileId?.let { "연결 해제된 LLM" },
                        summary.selectedModel,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(summary.updatedAtMs)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column {
                if (summary.generationState == ConversationGenerationState.STREAMING) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("stop_conversation_${summary.id}")
                            .semantics {
                                contentDescription = "${summary.title} 답변 생성 중지"
                            },
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    }
                }
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "${summary.title} 대화 작업"
                        },
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("이름 변경") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("삭제") },
                        leadingIcon = {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameConversationDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대화 이름 변경") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("대화 이름") },
                modifier = Modifier.testTag("rename_conversation_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("confirm_rename_conversation"),
            ) {
                Text("저장")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun ConversationSummary.badgeLabel(): String? = when {
    generationState == ConversationGenerationState.STREAMING -> "생성 중"
    generationState == ConversationGenerationState.FAILED -> "실패"
    generationState == ConversationGenerationState.CANCELLED -> "중지됨"
    hasUnreadCompletion -> "새 답변"
    else -> null
}

private fun ConversationSummary.accessibilityState(selected: Boolean): String =
    listOfNotNull(
        "현재 대화".takeIf { selected },
        badgeLabel(),
    ).ifEmpty { listOf("저장된 대화") }.joinToString(", ")
