package dev.alpine.llm.demo.ui.screens.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.model.AnthropicProfileDefaults
import dev.alpine.llm.demo.model.CodexProfileDefaults
import dev.alpine.llm.demo.model.GeminiProfileDefaults
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.model.XaiProfileDefaults
import dev.alpine.llm.demo.ui.theme.AlpineTheme

private val ContentMaxWidth = 840.dp

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
) {
    var showChooser by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("provider_list_screen"),
        topBar = {
            TopAppBar(
                title = { Text(text = "LLM connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("add_provider"),
                onClick = { showChooser = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add LLM") },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (connections.isEmpty()) {
                EmptyProviders()
            } else {
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
                    items(connections, key = { it.profile.id }) { connection ->
                        ProviderProfileCard(
                            connection = connection,
                            isAuthorizing = authorizingProfileId == connection.profile.id,
                            authorizationInProgress = authorizingProfileId != null,
                            onEdit = { onEdit(connection) },
                            onConnectionAction = { onConnectionAction(connection) },
                            onDelete = { onDelete(connection) },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
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
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete LLM profile?") },
            text = {
                Text("OAuth credentials for this profile will also be removed.")
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(connection) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyProviders() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No LLM profiles yet.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add a provider, then connect it with OAuth to start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderProfileCard(
    connection: ProviderConnection,
    isAuthorizing: Boolean,
    authorizationInProgress: Boolean,
    onEdit: () -> Unit,
    onConnectionAction: () -> Unit,
    onDelete: () -> Unit,
) {
    val profile = connection.profile
    val status = when (connection.state) {
        ProviderConnectionState.AUTHENTICATED -> "Connected"
        ProviderConnectionState.SIGNED_OUT -> "Not connected"
        ProviderConnectionState.REAUTHENTICATION_REQUIRED -> "Reconnect required"
    }
    val statusColors = when (connection.state) {
        ProviderConnectionState.AUTHENTICATED -> AlpineTheme.statusColors.connected to
            AlpineTheme.statusColors.onConnected
        ProviderConnectionState.SIGNED_OUT -> MaterialTheme.colorScheme.surfaceContainerHigh to
            MaterialTheme.colorScheme.onSurfaceVariant
        ProviderConnectionState.REAUTHENTICATION_REQUIRED -> AlpineTheme.statusColors.warning to
            AlpineTheme.statusColors.onWarning
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMonogram(profile.type)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        profile.type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProviderOverflow(onEdit = onEdit, onDelete = onDelete)
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = CircleShape,
                color = statusColors.first,
                contentColor = statusColors.second,
            ) {
                Text(
                    text = if (isAuthorizing) "Connecting…" else status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Model · ${profile.model.ifBlank { "Not configured" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection.state == ProviderConnectionState.AUTHENTICATED) {
                    TextButton(onClick = onConnectionAction, enabled = !authorizationInProgress) {
                        Text("Logout")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onConnectionAction,
                        enabled = !authorizationInProgress,
                    ) {
                        Text(if (connection.state == ProviderConnectionState.REAUTHENTICATION_REQUIRED) "Reconnect" else "Connect")
                    }
                }
                TextButton(onClick = onEdit, enabled = !authorizationInProgress) { Text("Edit") }
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
private fun ProviderOverflow(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Profile actions")
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { expanded = false; onEdit() },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { expanded = false; onDelete() },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ProviderChooser(
    onDismiss: () -> Unit,
    onSelected: (ProviderType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an LLM type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChoiceCard(ProviderType.ANTHROPIC, "provider_card_anthropic", onSelected)
                ProviderChoiceCard(ProviderType.GEMINI, "provider_card_gemini", onSelected)
                ProviderChoiceCard(ProviderType.OPENAI_COMPATIBLE, "provider_card_openai", onSelected)
                ProviderChoiceCard(ProviderType.CODEX, "provider_card_codex", onSelected)
                ProviderChoiceCard(ProviderType.XAI, "provider_card_xai", onSelected)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProviderChoiceCard(
    type: ProviderType,
    tag: String,
    onSelected: (ProviderType) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSelected(type) }
            .testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMonogram(type)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onSave: (ProviderProfile) -> Map<ProviderProfile.Field, String>,
) {
    var label by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.label) }
    var authorizationEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.authorizationEndpoint) }
    var tokenEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.tokenEndpoint) }
    var inferenceEndpoint by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.inferenceEndpoint) }
    var clientId by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.clientId) }
    var scopes by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.scopes.joinToString(" ")) }
    var model by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.model) }
    var callbackPort by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.callbackPort.toString()) }
    var anthropicBeta by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.anthropicBeta.orEmpty()) }
    var googleProjectId by rememberSaveable(initialProfile.id) { mutableStateOf(initialProfile.googleProjectId.orEmpty()) }
    var errors by remember { mutableStateOf<Map<ProviderProfile.Field, String>>(emptyMap()) }
    val fixedProtocolContract = initialProfile.type == ProviderType.ANTHROPIC ||
        initialProfile.type == ProviderType.GEMINI ||
        initialProfile.type == ProviderType.CODEX ||
        initialProfile.type == ProviderType.XAI
    val fixedClientRegistration = initialProfile.type == ProviderType.ANTHROPIC ||
        initialProfile.type == ProviderType.CODEX ||
        initialProfile.type == ProviderType.XAI
    val selectableModels = when (initialProfile.type) {
        ProviderType.ANTHROPIC -> AnthropicProfileDefaults.MODELS
        ProviderType.GEMINI -> GeminiProfileDefaults.MODELS
        ProviderType.CODEX -> CodexProfileDefaults.MODELS
        ProviderType.XAI -> XaiProfileDefaults.MODELS
        else -> emptyList()
    }

    fun profile() = initialProfile.copy(
        label = label.trim(),
        authorizationEndpoint = authorizationEndpoint.trim(),
        tokenEndpoint = tokenEndpoint.trim(),
        inferenceEndpoint = inferenceEndpoint.trim(),
        clientId = clientId.trim(),
        scopes = scopes.trim().split(Regex("\\s+")).filter(String::isNotBlank),
        model = model.trim(),
        callbackPort = callbackPort.trim().toIntOrNull() ?: 0,
        anthropicBeta = anthropicBeta.trim().ifBlank { null },
        googleProjectId = googleProjectId.trim().ifBlank { null },
    )
    fun save() { errors = onSave(profile()) }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit LLM" else "Add LLM") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = ::save) { Text("Save") }
                },
            )
        },
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
                item { ProviderTypeHeader(initialProfile.type) }
                item { SectionTitle("Profile") }
                item {
                    ProfileTextField(
                        value = label, onValueChange = { label = it }, label = "Profile name",
                        error = errors[ProviderProfile.Field.LABEL], tag = "profile_label",
                    )
                }
                item { SectionTitle("OAuth") }
                item {
                    ProfileTextField(
                        value = authorizationEndpoint, onValueChange = { authorizationEndpoint = it },
                        label = "Authorization endpoint", error = errors[ProviderProfile.Field.AUTHORIZATION_ENDPOINT],
                        tag = "authorization_endpoint", keyboardType = KeyboardType.Uri,
                        readOnly = fixedProtocolContract,
                    )
                }
                item {
                    ProfileTextField(
                        value = tokenEndpoint, onValueChange = { tokenEndpoint = it }, label = "Token endpoint",
                        error = errors[ProviderProfile.Field.TOKEN_ENDPOINT], tag = "token_endpoint", keyboardType = KeyboardType.Uri,
                        readOnly = fixedProtocolContract,
                    )
                }
                item {
                    ProfileTextField(
                        value = clientId, onValueChange = { clientId = it }, label = "OAuth public client ID",
                        error = errors[ProviderProfile.Field.CLIENT_ID], tag = "client_id",
                        readOnly = fixedClientRegistration,
                    )
                }
                item {
                    ProfileTextField(
                        value = scopes, onValueChange = { scopes = it }, label = "OAuth scopes (space-separated)",
                        error = errors[ProviderProfile.Field.SCOPES], tag = "scopes",
                        readOnly = fixedProtocolContract,
                    )
                }
                item {
                    ProfileTextField(
                        value = callbackPort, onValueChange = { callbackPort = it }, label = "Loopback callback port",
                        error = errors[ProviderProfile.Field.CALLBACK_PORT], tag = "callback_port", keyboardType = KeyboardType.Number,
                        readOnly = fixedProtocolContract,
                    )
                }
                item { SectionTitle("LLM") }
                item {
                    ProfileTextField(
                        value = inferenceEndpoint, onValueChange = { inferenceEndpoint = it }, label = "LLM endpoint",
                        placeholder = initialProfile.type.inferenceEndpointPlaceholder,
                        error = errors[ProviderProfile.Field.INFERENCE_ENDPOINT], tag = "inference_endpoint", keyboardType = KeyboardType.Uri,
                        readOnly = fixedProtocolContract,
                    )
                }
                item {
                    if (selectableModels.isNotEmpty()) {
                        ProviderModelSelector(
                            value = model,
                            models = selectableModels,
                            onValueChange = { model = it },
                            error = errors[ProviderProfile.Field.MODEL],
                        )
                    } else {
                        ProfileTextField(
                            value = model, onValueChange = { model = it }, label = "Default model",
                            error = errors[ProviderProfile.Field.MODEL], tag = "model",
                        )
                    }
                }
                if (initialProfile.type == ProviderType.ANTHROPIC) {
                    item { SectionTitle("Anthropic options") }
                    item {
                        ProfileTextField(
                            value = anthropicBeta, onValueChange = { anthropicBeta = it },
                            label = "Anthropic OAuth beta header", tag = "anthropic_beta",
                            error = errors[ProviderProfile.Field.ANTHROPIC_BETA],
                            readOnly = true,
                        )
                    }
                    item {
                        Text(
                            text = "OpenMinis 공개 소스의 OAuth·PKCE·Messages 값입니다. " +
                                "비공개 Claude Code 식별 시스템 프롬프트와 CLI 지문은 포함하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (initialProfile.type == ProviderType.GEMINI) {
                    item { SectionTitle("Google options") }
                    item {
                        Text(
                            text = "Use a Google OAuth Desktop client ID and quota project " +
                                "owned by this app. Gemini CLI credentials are not reused.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        ProfileTextField(
                            value = googleProjectId, onValueChange = { googleProjectId = it },
                            label = "Google Cloud quota project ID", tag = "google_project",
                        )
                    }
                }
                item {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_profile"),
                        onClick = ::save,
                    ) { Text("Save profile") }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
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
            label = { Text("Default model") },
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
private fun SectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
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
