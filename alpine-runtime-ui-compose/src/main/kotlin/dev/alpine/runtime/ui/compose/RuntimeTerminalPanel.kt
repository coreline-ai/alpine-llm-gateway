package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.host.RuntimeTerminalColor
import dev.alpine.runtime.host.RuntimeTerminalScreen
import dev.alpine.runtime.host.RuntimeTerminalSummary
import dev.alpine.runtime.host.RuntimeTerminalTextStyle

private enum class TerminalSignalAction {
    TERMINATE,
    KILL,
}

/**
 * Bounded, tabbed terminal presentation. The Runtime Host owns the output cap and session
 * isolation, while this UI renders its safe ANSI screen snapshot rather than raw escape bytes.
 */
@Composable
fun RuntimeTerminalPanel(
    state: RuntimeHostState,
    onOpen: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onClose: () -> Unit,
    onOpenAdditional: () -> Unit = onOpen,
    onSelectTerminal: (String) -> Unit = {},
    onRenameTerminal: (String, String) -> Unit = { _, _ -> },
    onSendRaw: (String) -> Unit = { onSend(it) },
    onTerminate: () -> Unit = onClose,
    onKill: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var command by remember(state.selectedTerminalId) { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<RuntimeTerminalSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var signalConfirmation by remember { mutableStateOf<TerminalSignalAction?>(null) }
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val terminalReady = state.terminalActive && state.operation == RuntimeHostOperation.IDLE
    val canOpen = state.sessionActive && state.operation == RuntimeHostOperation.IDLE
    val selectedTitle = state.terminalSessions.firstOrNull { it.id == state.selectedTerminalId }?.title
    val lastExit = state.lastTerminalExit
    val ansiScreen = state.terminalScreen
    val annotatedScreen = remember(ansiScreen) { ansiScreen?.toAnnotatedString() }

    fun submit() {
        val value = command
        if (value.isNotBlank() && terminalReady) {
            onSend(value)
            command = ""
        }
    }

    LaunchedEffect(state.selectedTerminalId, state.terminalText) {
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
                        when {
                            state.terminalActive -> "${selectedTitle ?: "현재 셸"} · ${state.terminalSessions.size.coerceAtLeast(1)}개 세션"
                            state.sessionActive -> "새 Alpine 셸을 열 수 있습니다"
                            else -> "Alpine Runtime을 시작하면 사용할 수 있습니다"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.terminalActive &&
                        state.terminalResizeSupport == RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY
                    ) {
                        Text("창 크기는 열 때만 적용됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = if (state.terminalActive) onOpenAdditional else onOpen,
                    enabled = canOpen,
                    modifier = Modifier.testTag("runtime_terminal_new"),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(if (state.terminalActive) "새 세션" else "열기") }
            }

            if (state.terminalSessions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.terminalSessions, key = { it.id }) { terminal ->
                        FilterChip(
                            selected = terminal.id == state.selectedTerminalId,
                            onClick = { onSelectTerminal(terminal.id) },
                            label = { Text(terminal.title, maxLines = 1) },
                            enabled = terminal.open,
                            modifier = Modifier.testTag("runtime_terminal_tab_${terminal.id}"),
                        )
                    }
                }
                selectedTitle?.let { title ->
                    TextButton(
                        onClick = {
                            renameTarget = state.terminalSessions.firstOrNull { it.id == state.selectedTerminalId }
                            renameText = title
                        },
                        enabled = terminalReady,
                    ) { Text("현재 세션 이름 변경") }
                }
            }

            lastExit?.let { exit ->
                val code = exit.exitCode?.let { "종료 코드 $it" } ?: "종료 코드를 확인할 수 없음"
                Text(
                    text = "${exit.title} 세션이 종료되었습니다. $code · 새 세션을 열어 계속할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .testTag("runtime_terminal_last_exit")
                        .semantics {
                            contentDescription = "마지막 터미널 종료 상태"
                        },
                )
            }

            SelectionContainer {
                if (annotatedScreen != null) {
                    Text(
                        text = annotatedScreen,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp)
                            .background(Color(0xFF10120F), MaterialTheme.shapes.medium)
                            .horizontalScroll(horizontalScrollState)
                            .verticalScroll(scrollState)
                            .padding(12.dp)
                            .semantics { contentDescription = "Alpine ANSI 터미널 화면" },
                    )
                } else {
                    Text(
                        text = state.terminalText.ifEmpty { "터미널 출력이 여기에 표시됩니다." },
                        color = if (state.terminalText.isEmpty()) Color(0xFFD2D6CF) else Color(0xFFF4F3ED),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp)
                            .background(Color(0xFF10120F), MaterialTheme.shapes.medium)
                            .horizontalScroll(horizontalScrollState)
                            .verticalScroll(scrollState)
                            .padding(12.dp)
                            .semantics { contentDescription = "Alpine 터미널 출력" },
                    )
                }
            }
            ansiScreen?.let { screen ->
                Text(
                    text = if (screen.usesAlternateScreen) {
                        "ANSI 화면 ${screen.columns} × ${screen.rows} · alternate screen"
                    } else {
                        "ANSI 화면 ${screen.columns} × ${screen.rows} · 출력 기록은 현재 세션에 제한 저장됩니다"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.terminalOutputTruncated) {
                Text("오래된 출력은 메모리 보호를 위해 생략되었습니다.", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("명령 입력") },
                enabled = terminalReady,
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
                    .testTag("runtime_terminal_input")
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Enter -> {
                                submit()
                                true
                            }
                            event.key == Key.Tab -> {
                                if (terminalReady) onSendRaw("\t")
                                true
                            }
                            event.key == Key.Escape -> {
                                if (terminalReady) onSendRaw("\u001b")
                                true
                            }
                            event.isCtrlPressed && event.key == Key.C -> {
                                if (terminalReady) onInterrupt()
                                true
                            }
                            else -> false
                        }
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { submit(); focusManager.moveFocus(FocusDirection.Previous) },
                    enabled = terminalReady && command.isNotBlank(),
                    modifier = Modifier.weight(1f).testTag("runtime_terminal_send"),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("보내기") }
                OutlinedButton(
                    onClick = { onSendRaw("\t") },
                    enabled = terminalReady,
                    modifier = Modifier.weight(0.65f),
                ) { Text("Tab") }
                OutlinedButton(
                    onClick = onInterrupt,
                    enabled = terminalReady,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (terminalReady) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) { Text("중단 Ctrl+C") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onSendRaw("\u001b") },
                    enabled = terminalReady,
                    modifier = Modifier.weight(1f),
                ) { Text("Esc") }
                OutlinedButton(
                    onClick = onClose,
                    enabled = terminalReady,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (terminalReady) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) { Text("현재 세션 닫기") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { signalConfirmation = TerminalSignalAction.TERMINATE },
                    enabled = terminalReady,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("runtime_terminal_terminate"),
                ) { Text("종료") }
                OutlinedButton(
                    onClick = { signalConfirmation = TerminalSignalAction.KILL },
                    enabled = terminalReady,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("runtime_terminal_kill"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(
                        1.25.dp,
                        if (terminalReady) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) { Text("강제 종료") }
            }
        }
    }

    signalConfirmation?.let { action ->
        val force = action == TerminalSignalAction.KILL
        AlertDialog(
            onDismissRequest = { signalConfirmation = null },
            title = { Text(if (force) "터미널 강제 종료" else "터미널 종료") },
            text = {
                Text(
                    if (force) {
                        "현재 세션에 SIGKILL을 보냅니다. 저장하지 않은 작업은 복구할 수 없습니다."
                    } else {
                        "현재 세션에 SIGTERM을 보냅니다. 종료되지 않으면 강제 종료를 직접 선택하세요."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (force) onKill() else onTerminate()
                        signalConfirmation = null
                    },
                    modifier = Modifier.testTag(
                        if (force) "runtime_terminal_kill_confirm" else "runtime_terminal_terminate_confirm",
                    ),
                ) { Text(if (force) "강제 종료" else "종료") }
            },
            dismissButton = {
                TextButton(
                    onClick = { signalConfirmation = null },
                    modifier = Modifier.testTag("runtime_terminal_signal_cancel"),
                ) { Text("취소") }
            },
        )
    }

    renameTarget?.let { terminal ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("터미널 이름 변경") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("세션 이름") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameTerminal(terminal.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("취소") }
            },
        )
    }
}

private fun RuntimeTerminalScreen.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { index, line ->
        line.spans.forEach { span ->
            withStyle(span.style.toSpanStyle()) { append(span.text) }
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private fun RuntimeTerminalTextStyle.toSpanStyle(): SpanStyle {
    val baseForeground = foreground.toTerminalColor(default = Color(0xFFF4F3ED))
    val baseBackground = background.toTerminalColor(default = Color(0xFF10120F))
    val effectiveForeground = if (inverse) baseBackground else baseForeground
    val effectiveBackground = if (inverse) baseForeground else baseBackground
    return SpanStyle(
        color = effectiveForeground,
        background = effectiveBackground,
        fontWeight = if (bold) FontWeight.Bold else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

private fun RuntimeTerminalColor.toTerminalColor(default: Color): Color = when (this) {
    RuntimeTerminalColor.DEFAULT -> default
    RuntimeTerminalColor.BLACK -> Color(0xFF191C1A)
    RuntimeTerminalColor.RED -> Color(0xFFFF8A80)
    RuntimeTerminalColor.GREEN -> Color(0xFFB9F227)
    RuntimeTerminalColor.YELLOW -> Color(0xFFFFD166)
    RuntimeTerminalColor.BLUE -> Color(0xFF8AB4F8)
    RuntimeTerminalColor.MAGENTA -> Color(0xFFE6A8FF)
    RuntimeTerminalColor.CYAN -> Color(0xFF7FE7DC)
    RuntimeTerminalColor.WHITE -> Color(0xFFF4F3ED)
    RuntimeTerminalColor.BRIGHT_BLACK -> Color(0xFF9DA39B)
    RuntimeTerminalColor.BRIGHT_RED -> Color(0xFFFFB4AB)
    RuntimeTerminalColor.BRIGHT_GREEN -> Color(0xFFD7FF84)
    RuntimeTerminalColor.BRIGHT_YELLOW -> Color(0xFFFFE29A)
    RuntimeTerminalColor.BRIGHT_BLUE -> Color(0xFFA8C7FA)
    RuntimeTerminalColor.BRIGHT_MAGENTA -> Color(0xFFF0C1FF)
    RuntimeTerminalColor.BRIGHT_CYAN -> Color(0xFFA7F3EA)
    RuntimeTerminalColor.BRIGHT_WHITE -> Color(0xFFFFFFFF)
}
