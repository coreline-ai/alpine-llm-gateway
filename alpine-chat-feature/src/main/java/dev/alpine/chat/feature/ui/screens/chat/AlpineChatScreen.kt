package dev.alpine.chat.feature.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatMessageState
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.ui.ChatUiState
import dev.alpine.chat.feature.ui.ConnectedProviderOption
import dev.alpine.chat.feature.ui.components.AdaptiveContent
import dev.alpine.chat.feature.ui.components.ChatMarkdown
import dev.alpine.chat.feature.ui.screens.assistant.AssistantModeControl
import dev.alpine.chat.feature.ui.screens.conversation.ConversationHistory
import dev.alpine.chat.feature.ui.state.ChatFailure
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.chat.feature.R
import kotlinx.coroutines.launch

/** Main chat surface. It only renders redacted [ChatFailure] data. */
@Composable
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
fun AlpineChatScreen(
    state: ChatUiState,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onSelectAssistantMode: (String, String, Boolean) -> Unit,
    onResetAssistantMode: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onManageProviders: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    failure: ChatFailure? = null,
    onDismissFailure: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var historyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) historyVisible = false
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            if (historyVisible) {
                ConversationHistory(
                    conversations = state.conversations,
                    activeConversationId = state.activeConversationId,
                    providers = state.providers,
                    onNewChat = onNewChat,
                    onSelect = onSelectConversation,
                    onRename = onRenameConversation,
                    onDelete = onDeleteConversation,
                    onClose = {
                        historyVisible = false
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            modifier = Modifier
                .testTag("chat_screen")
                .semantics { testTagsAsResourceId = true },
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                historyVisible = true
                                scope.launch { drawerState.open() }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("conversation_history")
                                .semantics { contentDescription = "Conversation history" },
                        ) {
                            Icon(Icons.Outlined.History, contentDescription = null)
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.conversationTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.activeGenerationCount > 1) {
                                Text(
                                    text = "${state.activeGenerationCount} chats generating",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onNewChat,
                            enabled = !state.isLoadingConversations,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("new_chat")
                                .semantics { contentDescription = "Start new chat" },
                        ) {
                            Icon(Icons.Outlined.AddComment, contentDescription = null)
                        }
                        IconButton(
                            onClick = onManageProviders,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("manage_providers")
                                .semantics { contentDescription = "Manage LLM connections" },
                        ) {
                            Icon(Icons.Outlined.Hub, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                AdaptiveContent(horizontalPadding = 12.dp) {
                    ChatComposer(
                        value = state.draft,
                        enabled = state.selectedProfileId != null &&
                            !state.isLoadingConversations,
                        streaming = state.isStreaming,
                        onValueChange = onDraftChange,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            },
        ) { padding ->
            AdaptiveContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(Modifier.fillMaxSize()) {
                    state.storageWarning?.let { warning ->
                        StorageWarning(warning)
                    }
                    ProviderSelector(
                        providers = state.providers,
                        selectedProfileId = state.selectedProfileId,
                        enabled = state.providers.isNotEmpty() && !state.isLoadingConversations,
                        streaming = state.isStreaming,
                        onSelect = onSelectProvider,
                        onSelectModel = onSelectModel,
                        onManageProviders = onManageProviders,
                    )
                    AssistantModeControl(
                        selectedSkillId = state.selectedSkillId,
                        selectedPersonaId = state.selectedPersonaId,
                        defaultSkillId = state.defaultSkillId,
                        defaultPersonaId = state.defaultPersonaId,
                        streaming = state.isStreaming,
                        enabled = !state.isLoadingConversations,
                        onSelect = onSelectAssistantMode,
                        onReset = onResetAssistantMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    state.statusMessage
                        ?.takeIf { state.selectedProfileId != null || state.messages.isNotEmpty() }
                        ?.let { message -> StatusMessage(message) }
                    failure?.let { visibleFailure ->
                        FailureAction(
                            failure = visibleFailure,
                            onAction = {
                                when (visibleFailure.recoveryAction) {
                                    ChatRecoveryAction.RETRY -> onRetry()
                                    ChatRecoveryAction.RECONNECT,
                                    ChatRecoveryAction.CHECK_SETTINGS -> onManageProviders()
                                }
                            },
                            onDismiss = onDismissFailure,
                        )
                    }
                    if (state.messages.isEmpty()) {
                        EmptyChatState(
                            hasProvider = state.selectedProfileId != null,
                            onManageProviders = onManageProviders,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        MessageList(
                            messages = state.messages,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(
    providers: List<ConnectedProviderOption>,
    selectedProfileId: String?,
    enabled: Boolean,
    streaming: Boolean,
    onSelect: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onManageProviders: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.profileId == selectedProfileId }
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled && providers.isNotEmpty()) expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("provider_selector")
                .semantics {
                    if (!enabled) disabled()
                },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = MenuAnchorType.PrimaryNotEditable,
                        enabled = enabled,
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled && providers.isNotEmpty()) { expanded = true }
                    .semantics { contentDescription = "Connected LLM selector" },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selected?.label ?: "No connected LLM",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selected?.model ?: "Connect an LLM to begin",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (providers.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Manage LLM connections") },
                        onClick = {
                            expanded = false
                            onManageProviders()
                        },
                    )
                } else {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(provider.label)
                                    Text(provider.model, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(provider.profileId)
                            },
                            trailingIcon = {
                                if (provider.profileId == selectedProfileId) {
                                    AssistChip(onClick = {}, label = { Text("Active") })
                                }
                            },
                        )
                    }
                }
            }
        }
        selected?.takeIf { it.modelOptions.size > 1 }?.let { provider ->
            ModelQuickSwitcher(
                models = provider.modelOptions,
                selectedModel = provider.model,
                enabled = enabled,
                streaming = streaming,
                onSelect = { model -> onSelectModel(provider.profileId, model) },
            )
        }
    }
}

@Composable
private fun ModelQuickSwitcher(
    models: List<String>,
    selectedModel: String,
    enabled: Boolean,
    streaming: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp),
    ) {
        Text(
            text = if (streaming) {
                "Next message model · current response continues unchanged"
            } else {
                "Quick model switch"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LazyRow(
            modifier = Modifier.testTag("model_quick_switcher"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(models, key = { it }) { model ->
                FilterChip(
                    selected = model == selectedModel,
                    onClick = { onSelect(model) },
                    enabled = enabled,
                    label = { Text(model, maxLines = 1) },
                    modifier = Modifier
                        .testTag("quick_model_$model")
                        .semantics { contentDescription = "Use model $model" },
                )
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    hasProvider: Boolean,
    onManageProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp).size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (hasProvider) "Start a conversation" else "Connect an LLM to start",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (hasProvider) {
                    "Your selected provider and model will be shown on every response."
                } else {
                    "Add an OAuth-connected provider, then choose it here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasProvider) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onManageProviders) { Text("Manage LLM connections") }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("messages_list"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
private fun FailureAction(
    failure: ChatFailure,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = failure.userMessage()
    val actionLabel = failure.actionLabel()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .testTag("retry_button")
                    .semantics { contentDescription = actionLabel },
            ) {
                Text(actionLabel)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Dismiss error" },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun StorageWarning(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StatusMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val isError = message.role == ChatRole.ERROR || message.state == ChatMessageState.FAILED
    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            Modifier
                .fillMaxWidth(0.84f)
                .widthIn(max = 620.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (!isUser && message.providerLabel != null) {
                Text(
                    text = listOfNotNull(
                        message.providerLabel,
                        message.model,
                        message.assistantSkillId?.let { id ->
                            dev.alpine.chat.feature.assistant.AssistantCatalog.skill(id).title
                        },
                        message.assistantPersonaId?.let { id ->
                            dev.alpine.chat.feature.assistant.AssistantCatalog.persona(id).title
                        },
                    )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 20.dp,
                ),
                color = bubbleColor,
                contentColor = contentColor,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val displayText = message.displayText()
                    if (!isUser && !isError && displayText.isNotBlank()) {
                        ChatMarkdown(source = displayText)
                    } else {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    message.statusLabel()?.let { status ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    enabled: Boolean,
    streaming: Boolean,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("message_input"),
                enabled = enabled,
                placeholder = {
                    Text(
                        when {
                            !enabled -> "Connect an LLM first"
                            streaming -> "Type your next message while this response finishes"
                            else -> "Message your LLM"
                        },
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (enabled && !streaming && value.isNotBlank()) {
                            onSend(value)
                        }
                    },
                ),
            )
            if (streaming) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("stop_button")
                        .semantics { contentDescription = "Stop generating" },
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                }
            } else {
                IconButton(
                    onClick = {
                        if (enabled && value.isNotBlank()) {
                            onSend(value)
                        }
                    },
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_button")
                        .semantics { contentDescription = "Send message" },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ChatMessage.displayText(): String = when {
    text.isNotBlank() -> text
    state == ChatMessageState.STREAMING -> stringResource(R.string.chat_thinking)
    state == ChatMessageState.CANCELLED -> stringResource(R.string.chat_generation_stopped)
    state == ChatMessageState.FAILED -> stringResource(R.string.chat_request_failed)
    else -> ""
}

@Composable
private fun ChatMessage.statusLabel(): String? = when (state) {
    ChatMessageState.STREAMING -> stringResource(R.string.chat_status_streaming)
    ChatMessageState.CANCELLED -> stringResource(R.string.chat_status_stopped)
    ChatMessageState.FAILED -> stringResource(R.string.chat_status_failed)
    ChatMessageState.COMPLETE -> null
}

@Composable
private fun ChatFailure.userMessage(): String = when (recoveryAction) {
    ChatRecoveryAction.RECONNECT -> stringResource(R.string.failure_reconnect)
    ChatRecoveryAction.RETRY -> when (kind) {
        dev.alpine.chat.feature.ui.state.ChatFailureKind.OVERLOADED ->
            stringResource(R.string.failure_overloaded)
        dev.alpine.chat.feature.ui.state.ChatFailureKind.TIMEOUT ->
            stringResource(R.string.failure_timeout)
        dev.alpine.chat.feature.ui.state.ChatFailureKind.PROVIDER_UNAVAILABLE ->
            stringResource(R.string.failure_unavailable)
        dev.alpine.chat.feature.ui.state.ChatFailureKind.INVALID_RESPONSE ->
            stringResource(R.string.failure_invalid_response)
        dev.alpine.chat.feature.ui.state.ChatFailureKind.NETWORK ->
            stringResource(R.string.failure_network)
        else -> stringResource(R.string.failure_generic)
    }
    ChatRecoveryAction.CHECK_SETTINGS -> stringResource(R.string.failure_check_settings)
}

@Composable
private fun ChatFailure.actionLabel(): String = when (recoveryAction) {
    ChatRecoveryAction.RETRY -> stringResource(R.string.failure_action_retry)
    ChatRecoveryAction.RECONNECT -> stringResource(R.string.failure_action_reconnect)
    ChatRecoveryAction.CHECK_SETTINGS -> stringResource(R.string.failure_action_connections)
}
