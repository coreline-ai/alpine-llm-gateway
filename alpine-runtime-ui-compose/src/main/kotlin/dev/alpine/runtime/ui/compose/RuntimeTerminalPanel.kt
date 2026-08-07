package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport

@Composable
fun RuntimeTerminalPanel(
    state: RuntimeHostState,
    onOpen: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    fun submit() {
        val value = command
        if (value.isNotBlank() && state.terminalActive) {
            onSend(value)
            command = ""
        }
    }
    LaunchedEffect(state.terminalText) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
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
                    Text("Linux 터미널", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.terminalActive) "대화형 Alpine 셸 연결됨" else "터미널이 닫혀 있습니다",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.terminalActive &&
                        state.terminalResizeSupport == RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY
                    ) {
                        Text("터미널 크기는 열 때 적용됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.terminalActive) {
                    Button(
                        onClick = onOpen,
                        enabled = state.sessionActive && state.operation == RuntimeHostOperation.IDLE,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("열기") }
                }
            }
            SelectionContainer {
                Text(
                    text = state.terminalText.ifEmpty { "터미널 출력이 여기에 표시됩니다." },
                    color = if (state.terminalText.isEmpty()) Color(0xFFD2D6CF) else Color(0xFFF4F3ED),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .background(Color(0xFF10120F), MaterialTheme.shapes.medium)
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                        .semantics { contentDescription = "Alpine 터미널 출력" },
                )
            }
            if (state.terminalOutputTruncated) {
                Text("오래된 터미널 출력은 메모리 보호를 위해 생략되었습니다.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("명령 입력") },
                enabled = state.terminalActive,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { submit(); focusManager.moveFocus(FocusDirection.Previous) },
                    enabled = state.terminalActive && command.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("보내기") }
                OutlinedButton(
                    onClick = onInterrupt,
                    enabled = state.terminalActive,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (state.terminalActive) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("중단 Ctrl+C") }
            }
            OutlinedButton(
                onClick = onClose,
                enabled = state.terminalActive,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(
                    1.25.dp,
                    if (state.terminalActive) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("터미널 닫기") }
        }
    }
}
