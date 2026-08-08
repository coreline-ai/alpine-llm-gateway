package dev.alpine.chat.feature.ui.screens.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.alpine.chat.feature.assistant.AssistantCatalog

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AssistantModeControl(
    selectedSkillId: String,
    selectedPersonaId: String,
    defaultSkillId: String,
    defaultPersonaId: String,
    streaming: Boolean,
    enabled: Boolean,
    onSelect: (String, String, Boolean) -> Unit,
    onReset: (Boolean) -> Unit,
) {
    var sheetVisible by remember { mutableStateOf(false) }
    val selectedSkill = AssistantCatalog.skill(selectedSkillId)
    val selectedPersona = AssistantCatalog.persona(selectedPersonaId)

    AssistChip(
        onClick = { sheetVisible = true },
        enabled = enabled,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        label = { Text("${selectedSkill.title} · ${selectedPersona.title}") },
        shape = RoundedCornerShape(16.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .heightIn(min = 48.dp)
            .testTag("assistant_mode_selector")
            .semantics {
                contentDescription =
                    "응답 설정. 현재 ${selectedSkill.title}, ${selectedPersona.title}"
            },
    )

    if (sheetVisible) {
        AssistantModeSheet(
            selectedSkillId = selectedSkill.id,
            selectedPersonaId = selectedPersona.id,
            defaultSkillId = defaultSkillId,
            defaultPersonaId = defaultPersonaId,
            streaming = streaming,
            onDismiss = { sheetVisible = false },
            onSelect = onSelect,
            onReset = onReset,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
private fun AssistantModeSheet(
    selectedSkillId: String,
    selectedPersonaId: String,
    defaultSkillId: String,
    defaultPersonaId: String,
    streaming: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String, String, Boolean) -> Unit,
    onReset: (Boolean) -> Unit,
) {
    var useAsDefault by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("assistant_mode_sheet")
            .semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "응답 설정",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("assistant_mode_close")
                        .semantics { contentDescription = "응답 설정 닫기" },
                ) {
                    Text("닫기")
                }
            }
            Text(
                text = if (streaming) {
                    "변경 내용은 다음 메시지부터 적용되며 현재 답변은 그대로 계속됩니다."
                } else {
                    "이 대화에 사용할 기본 스킬과 응답 페르소나를 하나씩 선택하세요."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            SectionTitle("기본 스킬")
            AssistantCatalog.skills.forEach { skill ->
                SelectionRow(
                    title = skill.title,
                    description = skill.description,
                    selected = skill.id == selectedSkillId,
                    testTag = "skill_option_${skill.id}",
                    onClick = { onSelect(skill.id, selectedPersonaId, useAsDefault) },
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("응답 페르소나")
            AssistantCatalog.personas.forEach { persona ->
                SelectionRow(
                    title = persona.title,
                    description = persona.description,
                    selected = persona.id == selectedPersonaId,
                    testTag = "persona_option_${persona.id}",
                    onClick = { onSelect(selectedSkillId, persona.id, useAsDefault) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = useAsDefault,
                        role = Role.Switch,
                    ) {
                        useAsDefault = !useAsDefault
                        if (useAsDefault) {
                            onSelect(selectedSkillId, selectedPersonaId, true)
                        }
                    }
                    .testTag("assistant_mode_default_toggle")
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("새 대화의 기본값으로 사용")
                    Text(
                        "현재 앱 기본값: ${AssistantCatalog.skill(defaultSkillId).title} · " +
                            AssistantCatalog.persona(defaultPersonaId).title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useAsDefault,
                    onCheckedChange = null,
                )
            }
            TextButton(
                onClick = { onReset(useAsDefault) },
                modifier = Modifier.testTag("assistant_mode_reset"),
            ) {
                Text("General assistant · Balanced로 초기화")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SelectionRow(
    title: String,
    description: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = "$title. $description" },
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
