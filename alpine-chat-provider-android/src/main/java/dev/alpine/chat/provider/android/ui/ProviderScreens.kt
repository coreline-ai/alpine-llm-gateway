package dev.alpine.chat.provider.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.alpine.chat.provider.android.session.ProviderConnection
import dev.alpine.chat.provider.android.session.ProviderConnectionIssue
import dev.alpine.chat.provider.android.session.ProviderConnectionState
import dev.alpine.chat.provider.android.model.ProviderModelCandidate
import dev.alpine.chat.provider.android.model.ProviderModelSource
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderSaveAction
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.llm.AnthropicOAuthCompatibilityRegistry
import dev.alpine.llm.CodexOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthCompatibilityRegistry
import dev.alpine.chat.feature.ui.designsystem.AlpineConfirmDialog
import dev.alpine.chat.feature.ui.designsystem.AlpineEmptyState
import dev.alpine.chat.feature.ui.designsystem.AlpinePrimaryAction
import dev.alpine.chat.feature.ui.designsystem.AlpineProductHeader
import dev.alpine.chat.feature.ui.designsystem.AlpineSectionCard
import dev.alpine.chat.feature.ui.designsystem.AlpineStatusRail
import dev.alpine.chat.feature.ui.designsystem.AlpineStatusTone
import dev.alpine.chat.feature.ui.designsystem.AlpineStepLabel
import java.util.Locale

private val ContentMaxWidth = 840.dp

private val ProviderValidationErrorsSaver = listSaver<
    Map<ProviderProfile.Field, String>,
    String,
>(
    save = { errors ->
        errors.entries
            .sortedBy { it.key.name }
            .flatMap { (field, message) -> listOf(field.name, message) }
    },
    restore = { saved ->
        saved.chunked(2).mapNotNull { entry ->
            val field = entry.getOrNull(0)
                ?.let { name -> ProviderProfile.Field.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            val message = entry.getOrNull(1) ?: return@mapNotNull null
            field to message
        }.toMap()
    },
)

private val ProviderModelCatalogSaver = listSaver<
    List<ProviderModelCandidate>,
    String,
>(
    save = { candidates ->
        candidates.flatMap { candidate ->
            listOf(candidate.modelId, candidate.source.wireName, candidate.enabled.toString())
        }
    },
    restore = { saved ->
        saved.chunked(3).mapNotNull { entry ->
            val modelId = entry.getOrNull(0)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val source = entry.getOrNull(1)
                ?.let(ProviderModelSource::fromWireName)
                ?: return@mapNotNull null
            ProviderModelCandidate(
                modelId = modelId,
                source = source,
                enabled = entry.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
            )
        }
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ProviderProfilesScreen(
    connections: List<ProviderConnection>,
    authorizingProfileId: String?,
    deleteCandidate: ProviderConnection?,
    onBack: () -> Unit,
    onAddProvider: (ProviderType) -> Unit,
    onEdit: (ProviderConnection) -> Unit,
    onConnectionAction: (ProviderConnection) -> Unit,
    onDelete: (ProviderConnection) -> Unit,
    onConfirmDelete: (ProviderConnection) -> Unit,
    onDismissDelete: () -> Unit,
    connectionIssues: Map<String, ProviderConnectionIssue> = emptyMap(),
    onCancelAuthorization: () -> Unit = {},
) {
    var showChooser by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("provider_list_screen"),
        topBar = {
            AlpineProductHeader(
                title = "LLM 연결",
                subtitle = "OAUTH PROVIDER · MODEL",
                statusLabel = if (connections.any { it.state == ProviderConnectionState.AUTHENTICATED }) {
                    "CONNECTED"
                } else {
                    "SETUP"
                },
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .testTag("profile_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connections.isEmpty()) {
                    item {
                        EmptyProviders(onAddProvider = { showChooser = true })
                    }
                } else {
                    item {
                        ProviderOverview(
                            connectedCount = connections.count {
                                it.state == ProviderConnectionState.AUTHENTICATED
                            },
                            totalCount = connections.size,
                            onAddProvider = { showChooser = true },
                        )
                    }
                    items(connections, key = { it.profile.id }) { connection ->
                        ProviderProfileCard(
                            connection = connection,
                            isAuthorizing = authorizingProfileId == connection.profile.id,
                            authorizationInProgress = authorizingProfileId != null,
                            issue = connectionIssues[connection.profile.id],
                            onEdit = { onEdit(connection) },
                            onConnectionAction = { onConnectionAction(connection) },
                            onCancelAuthorization = onCancelAuthorization,
                            onDelete = { onDelete(connection) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (showChooser) {
        ProviderChooser(
            onDismiss = { showChooser = false },
            onSelected = {
                showChooser = false
                onAddProvider(it)
            },
        )
    }
    deleteCandidate?.let { connection ->
        AlpineConfirmDialog(
            title = "LLM 연결을 삭제할까요?",
            message = "이 profile에 저장된 OAuth 인증 정보도 함께 삭제됩니다.",
            confirmLabel = "삭제",
            onConfirm = { onConfirmDelete(connection) },
            onDismiss = onDismissDelete,
        )
    }
}

@Composable
private fun EmptyProviders(onAddProvider: () -> Unit) {
    AlpineEmptyState(
        title = "연결된 LLM이 없습니다",
        message = "앱이 소유한 OAuth registration으로 Provider를 추가한 뒤 로그인하세요.",
        actionLabel = "새 LLM 연결",
        onAction = onAddProvider,
        actionModifier = Modifier.testTag("add_provider"),
    )
}

@Composable
private fun ProviderOverview(
    connectedCount: Int,
    totalCount: Int,
    onAddProvider: () -> Unit,
) {
    AlpineSectionCard {
        Text("외부 LLM 연결", style = MaterialTheme.typography.headlineSmall)
        Text(
            "OAuth credential은 Android Host에만 저장하고 Alpine Guest에는 전달하지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AlpineStatusRail(
            label = "연결 상태",
            message = "${totalCount}개 profile 중 ${connectedCount}개 연결됨",
            tone = if (connectedCount > 0) AlpineStatusTone.CONNECTED else AlpineStatusTone.NEUTRAL,
        )
        AlpinePrimaryAction(
            text = "새 LLM 연결",
            onClick = onAddProvider,
            modifier = Modifier.fillMaxWidth().testTag("add_provider"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderProfileCard(
    connection: ProviderConnection,
    isAuthorizing: Boolean,
    authorizationInProgress: Boolean,
    issue: ProviderConnectionIssue?,
    onEdit: () -> Unit,
    onConnectionAction: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onDelete: () -> Unit,
) {
    val profile = connection.profile
    val status = when (connection.state) {
        ProviderConnectionState.AUTHENTICATED -> "연결됨"
        ProviderConnectionState.SIGNED_OUT -> "연결 안 됨"
        ProviderConnectionState.REAUTHENTICATION_REQUIRED -> "재로그인 필요"
    }
    val tone = when (connection.state) {
        ProviderConnectionState.AUTHENTICATED -> AlpineStatusTone.CONNECTED
        ProviderConnectionState.SIGNED_OUT -> AlpineStatusTone.NEUTRAL
        ProviderConnectionState.REAUTHENTICATION_REQUIRED -> AlpineStatusTone.WARNING
    }
    val modelLabel = profile.model.ifBlank { "설정 필요" }
    val accessibilityState = when {
        isAuthorizing -> "연결 중. 브라우저 인증 완료 대기 중"
        issue != null -> "오류 ${issue.code}. ${issue.message} ${issue.nextAction}"
        else -> "$status. 모델 $modelLabel"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}")
            .semantics { stateDescription = accessibilityState },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMonogram(profile.type)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.label,
                        modifier = Modifier
                            .testTag("profile_label_${profile.id}")
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        profile.type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProviderOverflow(
                    profileLabel = profile.label,
                    profileId = profile.id,
                    enabled = !authorizationInProgress,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
            Spacer(Modifier.height(14.dp))
            when {
                isAuthorizing -> {
                    AlpineStatusRail(
                        label = "연결 중",
                        message = "브라우저 인증 완료를 기다리고 있습니다.",
                        tone = AlpineStatusTone.WARNING,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("authorization_progress_${profile.id}")
                            .semantics {
                                contentDescription = "${profile.label} 로그인 진행 중"
                            },
                    )
                }
                issue != null -> AlpineStatusRail(
                    label = "오류 · ${issue.code}",
                    message = "${issue.message}\n${issue.nextAction}",
                    tone = AlpineStatusTone.ERROR,
                    modifier = Modifier.testTag("connection_issue_${profile.id}"),
                )
                else -> AlpineStatusRail(
                    label = status,
                    message = "MODEL · $modelLabel",
                    tone = tone,
                    modifier = Modifier.testTag("profile_model_${profile.id}"),
                    messageMaxLines = 3,
                    messageOverflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isAuthorizing) {
                    FilledTonalButton(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("cancel_authorization_${profile.id}")
                            .semantics {
                                contentDescription = "${profile.label} 로그인 취소"
                            },
                        onClick = onCancelAuthorization,
                    ) {
                        Text("로그인 취소")
                    }
                } else if (connection.state == ProviderConnectionState.AUTHENTICATED) {
                    TextButton(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("connection_action_${profile.id}")
                            .semantics {
                                contentDescription = "${profile.label} 로그아웃"
                            },
                        onClick = onConnectionAction,
                        enabled = !authorizationInProgress,
                    ) {
                        Text("로그아웃")
                    }
                } else {
                    val actionLabel = if (
                        connection.state == ProviderConnectionState.REAUTHENTICATION_REQUIRED
                    ) {
                        "다시 로그인"
                    } else {
                        "로그인"
                    }
                    FilledTonalButton(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("connection_action_${profile.id}")
                            .semantics {
                                contentDescription = "${profile.label} $actionLabel"
                            },
                        onClick = onConnectionAction,
                        enabled = !authorizationInProgress,
                    ) {
                        Text(actionLabel)
                    }
                }
                TextButton(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("edit_profile_${profile.id}")
                        .semantics {
                            contentDescription = "${profile.label} 설정"
                        },
                    onClick = onEdit,
                    enabled = !authorizationInProgress,
                ) {
                    Text("설정")
                }
            }
        }
    }
}

@Composable
private fun ProviderMonogram(type: ProviderType) {
    val letter = when (type) {
        ProviderType.ANTHROPIC -> "A"
        ProviderType.GEMINI -> "G"
        ProviderType.OPENAI_COMPATIBLE -> "O"
        ProviderType.CODEX -> "C"
        ProviderType.XAI -> "X"
    }
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProviderOverflow(
    profileLabel: String,
    profileId: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("profile_actions_$profileId")
                .semantics {
                    contentDescription = "$profileLabel 작업 메뉴"
                },
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("편집") },
                onClick = { expanded = false; onEdit() },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("삭제") },
                onClick = { expanded = false; onDelete() },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderChooser(
    onDismiss: () -> Unit,
    onSelected: (ProviderType) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LLM Provider 선택",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("dismiss_provider_chooser"),
                    onClick = onDismiss,
                ) {
                    Text("취소")
                }
            }
            Text(
                "제품 지원 범위와 OAuth registration 요구사항을 확인한 뒤 선택하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                item { ProviderChoiceCard(ProviderType.ANTHROPIC, "provider_card_anthropic", onSelected) }
                item { ProviderChoiceCard(ProviderType.GEMINI, "provider_card_gemini", onSelected) }
                item { ProviderChoiceCard(ProviderType.OPENAI_COMPATIBLE, "provider_card_openai", onSelected) }
                item { ProviderChoiceCard(ProviderType.CODEX, "provider_card_codex", onSelected) }
                item { ProviderChoiceCard(ProviderType.XAI, "provider_card_xai", onSelected) }
            }
        }
    }
}

@Composable
private fun ProviderChoiceCard(
    type: ProviderType,
    tag: String,
    onSelected: (ProviderType) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSelected(type) }
            .testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.25.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMonogram(type)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = CircleShape,
                color = when (type) {
                    ProviderType.GEMINI -> MaterialTheme.colorScheme.primary
                    ProviderType.OPENAI_COMPATIBLE -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = when (type) {
                        ProviderType.GEMINI -> "지원"
                        ProviderType.OPENAI_COMPATIBLE -> "설정 필요"
                        else -> "호환성"
                    },
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ProviderEditScreen(
    initialProfile: ProviderProfile,
    isEditing: Boolean,
    onBack: () -> Unit,
    onSave: (ProviderProfile, ProviderSaveAction) -> Map<ProviderProfile.Field, String>,
) {
    var label by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.label) }
    var authorizationEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.authorizationEndpoint) }
    var tokenEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.tokenEndpoint) }
    var inferenceEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.inferenceEndpoint) }
    var clientId by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.clientId) }
    var scopes by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.scopes.joinToString(" ")) }
    var model by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.model) }
    var modelCatalog by rememberSaveable(
        initialProfile.id,
        stateSaver = ProviderModelCatalogSaver,
    ) {
        mutableStateOf(initialProfile.resolvedModelCatalog())
    }
    var newModelId by rememberSaveable(initialProfile.id) { mutableStateOf("") }
    var callbackPort by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.callbackPort.toString()) }
    var googleProjectId by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.googleProjectId.orEmpty()) }
    var showProtocolDetails by rememberSaveable(initialProfile.id) { mutableStateOf(true) }
    var showDiscardConfirmation by rememberSaveable(initialProfile.id) { mutableStateOf(false) }
    var errors by rememberSaveable(
        initialProfile.id,
        stateSaver = ProviderValidationErrorsSaver,
    ) {
        mutableStateOf(emptyMap())
    }
    val anthropicCompatibility = AnthropicOAuthCompatibilityRegistry.matching(
        clientId = initialProfile.clientId,
        authorizationEndpoint = initialProfile.authorizationEndpoint,
        tokenEndpoint = initialProfile.tokenEndpoint,
        messagesEndpoint = initialProfile.inferenceEndpoint,
    )
    val codexCompatibility = CodexOAuthCompatibilityRegistry.matching(
        clientId = initialProfile.clientId,
        responsesEndpoint = initialProfile.inferenceEndpoint,
    )
    val xaiCompatibility = XaiOAuthCompatibilityRegistry.matching(
        clientId = initialProfile.clientId,
        chatCompletionsEndpoint = initialProfile.inferenceEndpoint,
    )
    val debugCompatibility = anthropicCompatibility != null ||
        codexCompatibility != null || xaiCompatibility != null
    val fixedOAuthContract = initialProfile.type == ProviderType.GEMINI ||
        initialProfile.type == ProviderType.CODEX ||
        initialProfile.type == ProviderType.XAI ||
        anthropicCompatibility != null
    val fixedInferenceContract = initialProfile.type == ProviderType.GEMINI ||
        initialProfile.type == ProviderType.XAI ||
        codexCompatibility != null || anthropicCompatibility != null
    val enabledModels = modelCatalog.filter(ProviderModelCandidate::enabled)
        .map(ProviderModelCandidate::modelId)

    val editedProfile = initialProfile.copy(
        label = label.trim(),
        authorizationEndpoint = authorizationEndpoint.trim(),
        tokenEndpoint = tokenEndpoint.trim(),
        inferenceEndpoint = inferenceEndpoint.trim(),
        clientId = clientId.trim(),
        scopes = scopes.trim().split(Regex("\\s+")).filter(String::isNotBlank),
        model = model.trim(),
        modelCatalog = modelCatalog,
        callbackPort = callbackPort.trim().toIntOrNull() ?: 0,
        googleProjectId = googleProjectId.trim().ifBlank { null },
    )
    val hasUnsavedChanges = editedProfile != initialProfile || newModelId.isNotBlank()
    fun save(action: ProviderSaveAction) { errors = onSave(editedProfile, action) }
    fun requestBack() {
        if (hasUnsavedChanges) {
            showDiscardConfirmation = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = !showDiscardConfirmation) { requestBack() }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            AlpineProductHeader(
                title = if (isEditing) "LLM 연결 수정" else "새 LLM 연결",
                subtitle = "PROVIDER · OAUTH · MODEL",
                statusLabel = if (isEditing) "EDIT" else "NEW",
                onBack = ::requestBack,
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlpinePrimaryAction(
                        text = "저장하고 로그인",
                        onClick = { save(ProviderSaveAction.SAVE_AND_LOGIN) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_and_login"),
                    )
                    TextButton(
                        modifier = Modifier.testTag("save_for_later"),
                        onClick = { save(ProviderSaveAction.SAVE_FOR_LATER) },
                    ) {
                        Text("나중에 로그인")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .testTag("provider_edit_screen"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AlpineStepLabel(
                        step = 1,
                        title = "Provider 확인",
                        description = "연결할 LLM과 제품 지원 범위를 확인합니다.",
                    )
                }
                item { ProviderTypeHeader(initialProfile.type) }
                item {
                    AlpineStepLabel(
                        step = 2,
                        title = if (!debugCompatibility) {
                            "앱 소유 OAuth 정보"
                        } else {
                            "승인된 debug OAuth 정보"
                        },
                        description = if (!debugCompatibility) {
                            "다른 앱이나 CLI의 Client ID를 복사하지 마세요."
                        } else {
                            "사용자가 승인한 OpenMinis 호환 등록을 debug 앱에서만 사용합니다."
                        },
                    )
                }
                item {
                    ProfileTextField(
                        value = label, onValueChange = { label = it }, label = "연결 이름",
                        error = errors[ProviderProfile.Field.LABEL], tag = "profile_label",
                    )
                }
                item {
                    ProfileTextField(
                        value = clientId, onValueChange = { clientId = it }, label = "OAuth Public Client ID",
                        error = errors[ProviderProfile.Field.CLIENT_ID], tag = "client_id",
                        readOnly = debugCompatibility,
                    )
                }
                item {
                    AlpineStatusRail(
                        label = if (!debugCompatibility) {
                            "앱 소유 registration 필요"
                        } else {
                            "OpenMinis 호환 · DEBUG ONLY"
                        },
                        message = if (!debugCompatibility) {
                            "공식 CLI·OpenMinis·다른 앱의 Client ID와 fingerprint를 제품에 포함하지 않습니다."
                        } else {
                            "승인된 참조 revision의 OAuth 계약입니다. release에는 포함되지 않습니다."
                        },
                        tone = if (!debugCompatibility) {
                            AlpineStatusTone.WARNING
                        } else {
                            AlpineStatusTone.CONNECTED
                        },
                    )
                }
                if (initialProfile.type == ProviderType.GEMINI) {
                    item {
                        ProfileTextField(
                            value = googleProjectId, onValueChange = { googleProjectId = it },
                            label = "Google Cloud Quota Project ID", tag = "google_project",
                        )
                    }
                }
                item {
                    AlpineStepLabel(
                        step = 3,
                        title = "기본 모델 선택",
                        description = "연결 직후 사용할 모델을 선택합니다.",
                    )
                }
                item {
                    if (enabledModels.isNotEmpty()) {
                        ProviderModelSelector(
                            value = model,
                            models = enabledModels,
                            onValueChange = { model = it },
                            error = errors[ProviderProfile.Field.MODEL],
                        )
                    } else {
                        AlpineStatusRail(
                            label = "활성 모델 필요",
                            message = errors[ProviderProfile.Field.MODEL]
                                ?: "모델 ID를 추가한 뒤 기본 모델을 선택하세요.",
                            tone = AlpineStatusTone.WARNING,
                        )
                    }
                }
                item {
                    ProfileTextField(
                        value = newModelId,
                        onValueChange = { newModelId = it },
                        label = "모델 ID 추가",
                        tag = "model_candidate_input",
                        error = errors[ProviderProfile.Field.MODEL],
                    )
                }
                item {
                    FilledTonalButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("add_model_candidate")
                            .semantics {
                                contentDescription = "모델 후보 추가"
                                stateDescription = if (newModelId.isBlank()) {
                                    "모델 ID 입력 필요"
                                } else {
                                    "추가 준비됨"
                                }
                            },
                        onClick = {
                            val candidate = newModelId.trim()
                            when {
                                candidate.isEmpty() -> {
                                    errors = errors + (
                                        ProviderProfile.Field.MODEL to "추가할 모델 ID를 입력하세요."
                                    )
                                }
                                modelCatalog.any { it.modelId.equals(candidate, ignoreCase = true) } -> {
                                    errors = errors + (
                                        ProviderProfile.Field.MODEL to "이미 추가된 모델 ID입니다."
                                    )
                                }
                                else -> {
                                    modelCatalog = modelCatalog + ProviderModelCandidate(
                                        modelId = candidate,
                                        source = ProviderModelSource.USER_ADDED,
                                    )
                                    if (model.isBlank()) model = candidate
                                    newModelId = ""
                                    errors = errors - ProviderProfile.Field.MODEL
                                }
                            }
                        },
                    ) {
                        Text("모델 추가")
                    }
                }
                items(modelCatalog, key = { it.modelId.lowercase(Locale.ROOT) }) { candidate ->
                    ProviderModelCandidateRow(
                        candidate = candidate,
                        isDefault = candidate.modelId.equals(model, ignoreCase = true),
                        onToggle = {
                            modelCatalog = modelCatalog.map { current ->
                                if (current.modelId.equals(candidate.modelId, ignoreCase = true)) {
                                    current.copy(enabled = !current.enabled)
                                } else {
                                    current
                                }
                            }
                        },
                    )
                }
                item {
                    Text(
                        text = "모델 후보는 계정·지역·요금제의 실제 사용 권한을 보장하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("model_catalog_disclaimer"),
                    )
                }
                item {
                    ProtocolSectionHeader(
                        title = when {
                            fixedInferenceContract -> "프로토콜 정보 · 읽기 전용"
                            fixedOAuthContract -> "프로토콜 정보 · OAuth 읽기 전용"
                            else -> "고급 프로토콜 설정"
                        },
                        expanded = showProtocolDetails,
                        onToggle = { showProtocolDetails = !showProtocolDetails },
                    )
                }
                if (showProtocolDetails) {
                    item {
                        ProfileTextField(
                            value = authorizationEndpoint, onValueChange = { authorizationEndpoint = it },
                            label = "Authorization Endpoint", error = errors[ProviderProfile.Field.AUTHORIZATION_ENDPOINT],
                            tag = "authorization_endpoint", keyboardType = KeyboardType.Uri,
                            readOnly = fixedOAuthContract,
                        )
                    }
                    item {
                        ProfileTextField(
                            value = tokenEndpoint, onValueChange = { tokenEndpoint = it }, label = "Token Endpoint",
                            error = errors[ProviderProfile.Field.TOKEN_ENDPOINT], tag = "token_endpoint", keyboardType = KeyboardType.Uri,
                            readOnly = fixedOAuthContract,
                        )
                    }
                    item {
                        ProfileTextField(
                            value = scopes, onValueChange = { scopes = it }, label = "OAuth Scopes",
                            error = errors[ProviderProfile.Field.SCOPES], tag = "scopes",
                            readOnly = fixedOAuthContract,
                        )
                    }
                    item {
                        ProfileTextField(
                            value = callbackPort, onValueChange = { callbackPort = it }, label = "Loopback Callback Port",
                            error = errors[ProviderProfile.Field.CALLBACK_PORT], tag = "callback_port", keyboardType = KeyboardType.Number,
                            readOnly = fixedOAuthContract,
                        )
                    }
                item {
                    ProfileTextField(
                        value = inferenceEndpoint, onValueChange = { inferenceEndpoint = it }, label = "LLM Endpoint",
                        placeholder = initialProfile.type.inferenceEndpointPlaceholder,
                        error = errors[ProviderProfile.Field.INFERENCE_ENDPOINT], tag = "inference_endpoint", keyboardType = KeyboardType.Uri,
                        readOnly = fixedInferenceContract,
                    )
                }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    if (showDiscardConfirmation) {
        AlpineConfirmDialog(
            title = "변경사항을 버릴까요?",
            message = "저장하지 않은 Provider 설정이 있습니다. 나가면 입력한 내용이 삭제됩니다.",
            confirmLabel = "버리고 나가기",
            onConfirm = {
                showDiscardConfirmation = false
                onBack()
            },
            onDismiss = { showDiscardConfirmation = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelSelector(
    value: String,
    models: List<String>,
    onValueChange: (String) -> Unit,
    error: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = MenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                )
                .testTag("model"),
            value = value,
            onValueChange = {},
            label = { Text("기본 모델") },
            supportingText = error?.let { { Text(it) } },
            isError = error != null,
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("model_option_$model"),
                    text = { Text(model) },
                    onClick = {
                        onValueChange(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderModelCandidateRow(
    candidate: ProviderModelCandidate,
    isDefault: Boolean,
    onToggle: () -> Unit,
) {
    val toggleEnabled = !isDefault || !candidate.enabled
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_candidate_${candidate.modelId}")
            .semantics {
                contentDescription = "${candidate.modelId} 모델 후보"
                stateDescription = buildString {
                    append(if (candidate.enabled) "사용 중" else "사용 중지")
                    if (isDefault) append(", 기본 모델")
                    append(", ${candidate.source.displayLabel()}")
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(candidate.modelId, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = buildString {
                        append(candidate.source.displayLabel())
                        if (isDefault) append(" · 기본 모델")
                        if (!candidate.enabled) append(" · 사용 중지")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("toggle_model_${candidate.modelId}")
                    .semantics {
                        contentDescription = if (candidate.enabled) {
                            "${candidate.modelId} 모델 사용 중지"
                        } else {
                            "${candidate.modelId} 모델 다시 사용"
                        }
                        stateDescription = if (!toggleEnabled) {
                            "기본 모델은 사용 중지할 수 없음"
                        } else if (candidate.enabled) {
                            "사용 중"
                        } else {
                            "사용 중지"
                        }
                    },
                enabled = toggleEnabled,
                onClick = onToggle,
            ) {
                Text(if (candidate.enabled) "사용 중지" else "다시 사용")
            }
        }
    }
}

private fun ProviderModelSource.displayLabel(): String = when (this) {
    ProviderModelSource.PROVIDER_APPROVED -> "Provider 승인 후보"
    ProviderModelSource.USER_ADDED -> "사용자 추가"
    ProviderModelSource.LEGACY_MIGRATED -> "기존 설정에서 이전"
}

@Composable
private fun ProviderTypeHeader(type: ProviderType) {
    Card(
        modifier = Modifier.testTag("provider_type"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMonogram(type)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(type.displayName, style = MaterialTheme.typography.titleMedium)
                Text(type.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ProtocolSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                modifier = Modifier.testTag("protocol_details_toggle"),
                onClick = onToggle,
            ) {
                Text(if (expanded) "접기" else "보기")
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    error: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        readOnly = readOnly,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
