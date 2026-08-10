package dev.alpine.chat.feature.ui.screens.chat

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatMessageState
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.assistant.AssistantCatalog
import dev.alpine.chat.feature.ui.ChatUiState
import dev.alpine.chat.feature.ui.ConnectedProviderOption
import dev.alpine.chat.feature.ui.components.AdaptiveContent
import dev.alpine.chat.feature.ui.components.ChatMarkdown
import dev.alpine.chat.feature.ui.screens.assistant.AssistantModeControl
import dev.alpine.chat.feature.ui.screens.conversation.ConversationHistory
import dev.alpine.chat.feature.ui.state.ChatFailure
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.chat.feature.ui.theme.AlpineTheme
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
    onStopConversation: ((String) -> Unit)? = null,
    failure: ChatFailure? = null,
    onDismissFailure: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRecoveryAction: ((ChatRecoveryAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
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
                    onStop = { conversationId ->
                        if (onStopConversation != null) {
                            onStopConversation(conversationId)
                        } else {
                            onSelectConversation(conversationId)
                            onStop()
                        }
                    },
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
            modifier = modifier
                .testTag("chat_screen")
                .semantics { testTagsAsResourceId = true },
            topBar = {
                ChatToolbar(
                    title = state.conversationTitle,
                    backgroundGenerationCount = state.backgroundGenerationCount,
                    newChatEnabled = !state.isLoadingConversations,
                    onHistory = {
                        historyVisible = true
                        scope.launch { drawerState.open() }
                    },
                    onNewChat = onNewChat,
                    onManageProviders = onManageProviders,
                )
            },
            bottomBar = {
                AdaptiveContent(horizontalPadding = 12.dp) {
                    ChatComposer(
                        value = state.draft,
                        enabled = state.selectedProfileId != null &&
                            !state.isLoadingConversations,
                        streaming = state.isStreaming,
                        generationCapacityAvailable = state.generationCapacityAvailable,
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
                    if (state.providers.isEmpty()) {
                        ConnectionNotice()
                    }
                    ChatContextControls(
                        providers = state.providers,
                        selectedProfileId = state.selectedProfileId,
                        selectedSkillId = state.selectedSkillId,
                        selectedPersonaId = state.selectedPersonaId,
                        defaultSkillId = state.defaultSkillId,
                        defaultPersonaId = state.defaultPersonaId,
                        enabled = !state.isLoadingConversations,
                        streaming = state.isStreaming,
                        onSelectProvider = onSelectProvider,
                        onSelectModel = onSelectModel,
                        onManageProviders = onManageProviders,
                        onSelectAssistantMode = onSelectAssistantMode,
                        onResetAssistantMode = onResetAssistantMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    state.statusMessage
                        ?.takeIf { state.selectedProfileId != null || state.messages.isNotEmpty() }
                        ?.let { message -> StatusMessage(message) }
                    failure?.let { visibleFailure ->
                        FailureAction(
                            failure = visibleFailure,
                            onAction = {
                                val action = visibleFailure.recoveryAction
                                if (onRecoveryAction != null) {
                                    onRecoveryAction(action)
                                } else {
                                    when (action) {
                                        ChatRecoveryAction.RETRY -> onRetry()
                                        ChatRecoveryAction.RECONNECT,
                                        ChatRecoveryAction.CHECK_SETTINGS,
                                        ChatRecoveryAction.INSTALL_RUNTIME,
                                        ChatRecoveryAction.REPAIR_RUNTIME,
                                        ChatRecoveryAction.RESTART_RUNTIME,
                                        -> onManageProviders()
                                    }
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

@Composable
private fun ChatContextControls(
    providers: List<ConnectedProviderOption>,
    selectedProfileId: String?,
    selectedSkillId: String,
    selectedPersonaId: String,
    defaultSkillId: String,
    defaultPersonaId: String,
    enabled: Boolean,
    streaming: Boolean,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onManageProviders: () -> Unit,
    onSelectAssistantMode: (String, String, Boolean) -> Unit,
    onResetAssistantMode: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedProvider = providers.firstOrNull { it.profileId == selectedProfileId }
    val selectedSkill = AssistantCatalog.skill(selectedSkillId)
    val selectedPersona = AssistantCatalog.persona(selectedPersonaId)
    val providerSummary = selectedProvider?.let { "${it.label} · ${it.model}" } ?: "LLM 연결 필요"
    val assistantSummary = "${selectedSkill.title} · ${selectedPersona.title}"

    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp)
                .testTag("chat_context_toggle")
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { expanded = !expanded }
                .semantics {
                    contentDescription = "대화 설정. $providerSummary, $assistantSummary"
                    stateDescription = if (expanded) "펼쳐짐" else "접힘"
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = providerSummary,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = assistantSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            ProviderSelector(
                providers = providers,
                selectedProfileId = selectedProfileId,
                enabled = providers.isNotEmpty() && enabled,
                streaming = streaming,
                onSelect = onSelectProvider,
                onSelectModel = onSelectModel,
                onManageProviders = onManageProviders,
            )
            AssistantModeControl(
                selectedSkillId = selectedSkillId,
                selectedPersonaId = selectedPersonaId,
                defaultSkillId = defaultSkillId,
                defaultPersonaId = defaultPersonaId,
                streaming = streaming,
                enabled = enabled,
                onSelect = onSelectAssistantMode,
                onReset = onResetAssistantMode,
            )
        }
    }
}

@Composable
private fun ChatToolbar(
    title: String,
    backgroundGenerationCount: Int,
    newChatEnabled: Boolean,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onManageProviders: () -> Unit,
) {
    AdaptiveContent(horizontalPadding = 12.dp) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onHistory,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("conversation_history")
                        .semantics { contentDescription = "대화 기록 열기" },
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (backgroundGenerationCount > 0) {
                        Text(
                            text = "다른 대화 ${backgroundGenerationCount}개 생성 중",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("background_generation_count"),
                        )
                    }
                }
                IconButton(
                    onClick = onNewChat,
                    enabled = newChatEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("new_chat")
                        .semantics { contentDescription = "새 대화 시작" },
                ) {
                    Icon(Icons.Outlined.AddComment, contentDescription = null)
                }
                IconButton(
                    onClick = onManageProviders,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("manage_providers")
                        .semantics { contentDescription = "LLM 연결 관리" },
                ) {
                    Icon(Icons.Outlined.Hub, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ConnectionNotice() {
    val status = AlpineTheme.statusColors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = status.warning,
        contentColor = status.onWarning,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column {
                Text("연결된 LLM 없음", style = MaterialTheme.typography.titleSmall)
                Text(
                    "OAuth Provider를 연결한 뒤 모델을 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
    val canOpen = providers.isEmpty() || enabled
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled && providers.isNotEmpty()) expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("provider_selector")
                .semantics {
                    if (!canOpen) disabled()
                },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = MenuAnchorType.PrimaryNotEditable,
                        enabled = enabled,
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = canOpen) {
                        if (providers.isEmpty()) onManageProviders() else expanded = true
                    }
                    .semantics {
                        contentDescription = if (selected == null) {
                            "LLM Provider 선택 및 연결"
                        } else {
                            "연결된 LLM 선택. 현재 ${selected.label}, ${selected.model}"
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selected?.label ?: "LLM 연결",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selected?.model ?: "Provider 선택",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        if (providers.isEmpty()) Icons.AutoMirrored.Outlined.ArrowForward
                        else Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                    )
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (providers.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("LLM 연결 관리") },
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
                                    AssistChip(onClick = {}, label = { Text("사용 중") })
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
                "다음 메시지 모델 · 현재 답변은 그대로 계속됨"
            } else {
                "빠른 모델 변경"
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
                        .semantics { contentDescription = "$model 모델 사용" },
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
    Box(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "QUICK CHAT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = if (hasProvider) "대화를 시작하세요" else "먼저 LLM을 연결하세요",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (hasProvider) {
                        "선택한 Provider와 모델이 각 답변에 표시됩니다."
                    } else {
                        "Codex, Claude, Gemini 또는 Grok을 연결한 뒤 모델을 선택하세요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.90f),
                )
                if (!hasProvider) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onManageProviders,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("LLM 연결 관리")
                    }
                }
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
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
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
                    .semantics { contentDescription = "오류 안내 닫기" },
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
    val displayText = message.displayText()
    val statusLabel = message.statusLabel()
    val accessibilityStateLabel = when (message.state) {
        ChatMessageState.COMPLETE -> null
        ChatMessageState.STREAMING -> "생성 중"
        ChatMessageState.CANCELLED -> "중지됨"
        ChatMessageState.FAILED -> "실패"
    }
    val senderLabel = when (message.role) {
        ChatRole.USER -> "사용자 메시지"
        ChatRole.ASSISTANT -> "AI 답변"
        ChatRole.ERROR -> "오류 메시지"
    }
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
                modifier = Modifier
                    .testTag("message_${message.id}")
                    .semantics(mergeDescendants = true) {
                        contentDescription = senderLabel
                        accessibilityStateLabel?.let { stateDescription = it }
                    },
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 20.dp,
                ),
                color = bubbleColor,
                contentColor = contentColor,
                border = BorderStroke(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                ),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (!isUser && !isError && displayText.isNotBlank()) {
                        ChatMarkdown(source = displayText)
                    } else {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    statusLabel?.let { status ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = contentColor.copy(alpha = 0.88f),
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
    generationCapacityAvailable: Boolean,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
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
                    .testTag("message_input")
                    .semantics { contentDescription = "메시지 입력" },
                enabled = enabled,
                placeholder = {
                    Text(
                        when {
                            !enabled -> "먼저 LLM을 연결하세요"
                            streaming -> "현재 답변 중에도 다음 메시지를 입력할 수 있습니다"
                            !generationCapacityAvailable ->
                                "다른 답변이 끝나면 이 메시지를 전송할 수 있습니다"
                            else -> "LLM에 메시지 보내기"
                        },
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (
                            enabled &&
                            !streaming &&
                            generationCapacityAvailable &&
                            value.isNotBlank()
                        ) {
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
                        .semantics { contentDescription = "답변 생성 중지" },
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                }
            } else {
                IconButton(
                    onClick = {
                        if (enabled && generationCapacityAvailable && value.isNotBlank()) {
                            onSend(value)
                        }
                    },
                    enabled = enabled && generationCapacityAvailable && value.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_button")
                        .semantics { contentDescription = "메시지 전송" },
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
        dev.alpine.chat.feature.ui.state.ChatFailureKind.RUNTIME_BUSY ->
            stringResource(R.string.failure_runtime_busy)
        else -> stringResource(R.string.failure_generic)
    }
    ChatRecoveryAction.CHECK_SETTINGS -> when (kind) {
        dev.alpine.chat.feature.ui.state.ChatFailureKind.RESPONSE_TOO_LARGE ->
            stringResource(R.string.failure_response_too_large)
        else -> stringResource(R.string.failure_check_settings)
    }
    ChatRecoveryAction.INSTALL_RUNTIME -> stringResource(R.string.failure_runtime_not_installed)
    ChatRecoveryAction.REPAIR_RUNTIME -> stringResource(R.string.failure_runtime_repair)
    ChatRecoveryAction.RESTART_RUNTIME -> when (kind) {
        dev.alpine.chat.feature.ui.state.ChatFailureKind.FALLBACK_DECLINED ->
            stringResource(R.string.failure_fallback_declined)
        else -> stringResource(R.string.failure_runtime_start)
    }
}

@Composable
private fun ChatFailure.actionLabel(): String = when (recoveryAction) {
    ChatRecoveryAction.RETRY -> stringResource(R.string.failure_action_retry)
    ChatRecoveryAction.RECONNECT -> stringResource(R.string.failure_action_reconnect)
    ChatRecoveryAction.CHECK_SETTINGS -> stringResource(R.string.failure_action_connections)
    ChatRecoveryAction.INSTALL_RUNTIME -> stringResource(R.string.failure_action_install_runtime)
    ChatRecoveryAction.REPAIR_RUNTIME -> stringResource(R.string.failure_action_repair_runtime)
    ChatRecoveryAction.RESTART_RUNTIME -> stringResource(R.string.failure_action_restart_runtime)
}
