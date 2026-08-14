package dev.alpine.chat.provider.android.model

import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.CodexOAuthCompatibilityRegistry
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.AnthropicOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

enum class ProviderType(
    val wireName: String,
    val displayName: String,
    val description: String,
    val inferenceEndpointPlaceholder: String,
    val defaultScopes: String,
) {
    ANTHROPIC(
        wireName = "anthropic",
        displayName = "Anthropic (승인 필요)",
        description = "앱 소유 OAuth 등록과 Provider 승인이 필요합니다. consumer 기본값은 제공하지 않습니다.",
        inferenceEndpointPlaceholder = "https://api.anthropic.com/v1/messages",
        defaultScopes = "",
    ),
    GEMINI(
        wireName = "gemini",
        displayName = "Google Gemini",
        description = "앱이 소유한 Google OAuth로 공식 Gemini API에 연결합니다.",
        inferenceEndpointPlaceholder = GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT,
        defaultScopes = GeminiOAuthContract.SCOPES.joinToString(" "),
    ),
    OPENAI_COMPATIBLE(
        wireName = "openai_compatible",
        displayName = "OpenAI-compatible",
        description = "OAuth를 지원하는 Chat Completions endpoint에 연결합니다.",
        inferenceEndpointPlaceholder = "https://provider.example.com/v1/chat/completions",
        defaultScopes = "",
    ),
    CODEX(
        wireName = "codex",
        displayName = "OpenAI Responses (승인 필요)",
        description = "앱 소유 OAuth 등록과 승인된 Responses relay가 필요합니다. ChatGPT/CLI 기본값은 제공하지 않습니다.",
        inferenceEndpointPlaceholder = CodexOAuthContract.RESPONSES_ENDPOINT_PLACEHOLDER,
        defaultScopes = CodexOAuthContract.SCOPES.joinToString(" "),
    ),
    XAI(
        wireName = "xai",
        displayName = "xAI (승인 필요)",
        description = "앱 소유 OAuth 등록과 Provider 승인이 필요합니다. consumer 호환은 debug에서만 명시 승인합니다.",
        inferenceEndpointPlaceholder = XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT,
        defaultScopes = XaiOAuthContract.SCOPES.joinToString(" "),
    );

    companion object {
        fun fromWireName(value: String): ProviderType =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unsupported provider type")
    }
}

data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: ProviderType,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val inferenceEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    val model: String,
    val modelCatalog: List<ProviderModelCandidate> = emptyList(),
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
    /**
     * Binary/source compatibility only for profiles compiled before the direct-OAuth cleanup.
     * The value is neither restored from storage nor sent as a Provider header.
     */
    @Deprecated("Legacy compatibility field; direct OAuth beta headers are not supported")
    val anthropicBeta: String? = null,
    val googleProjectId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    /** Normalized local candidates. Empty pre-catalog objects are repaired from [model]. */
    fun resolvedModelCatalog(): List<ProviderModelCandidate> {
        val normalized = normalizeModelCatalog(modelCatalog)
        if (normalized.isNotEmpty()) return normalized
        return legacyModelCatalog(model)
    }

    fun enabledModelIds(): List<String> = resolvedModelCatalog()
        .filter(ProviderModelCandidate::enabled)
        .map(ProviderModelCandidate::modelId)

    fun containsEnabledModel(modelId: String): Boolean = modelId in enabledModelIds()

    fun requiresReauthenticationComparedTo(previous: ProviderProfile): Boolean =
        type != previous.type ||
            authorizationEndpoint != previous.authorizationEndpoint ||
            tokenEndpoint != previous.tokenEndpoint ||
            clientId != previous.clientId ||
            scopes != previous.scopes ||
            callbackPort != previous.callbackPort

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("type", type.wireName)
        .put("authorization_endpoint", authorizationEndpoint)
        .put("token_endpoint", tokenEndpoint)
        .put("inference_endpoint", inferenceEndpoint)
        .put("client_id", clientId)
        .put("scopes", scopes.joinToString(" "))
        .put("model", model)
        .put(
            "model_catalog",
            JSONArray().apply {
                resolvedModelCatalog().forEach { candidate ->
                    put(
                        JSONObject()
                            .put("model_id", candidate.modelId)
                            .put("source", candidate.source.wireName)
                            .put("enabled", candidate.enabled),
                    )
                }
            },
        )
        .put("callback_port", callbackPort)
        .putOpt("google_project_id", googleProjectId)
        .put("created_at_ms", createdAtMs)

    fun validationErrors(): Map<Field, String> = buildMap {
        if (label.isBlank()) put(Field.LABEL, "연결 이름을 입력하세요.")
        validateHttps(authorizationEndpoint, "Authorization endpoint")?.let {
            put(Field.AUTHORIZATION_ENDPOINT, it)
        }
        validateHttps(tokenEndpoint, "Token endpoint")?.let {
            put(Field.TOKEN_ENDPOINT, it)
        }
        validateHttps(inferenceEndpoint, "LLM endpoint")?.let {
            put(Field.INFERENCE_ENDPOINT, it)
        }
        if (type == ProviderType.GEMINI && !inferenceEndpoint.contains("{model}")) {
            put(Field.INFERENCE_ENDPOINT, "Gemini endpoint에 {model} 값을 포함하세요.")
        }
        if (type == ProviderType.ANTHROPIC) {
            AnthropicOAuthCompatibilityRegistry.matching(clientId)?.let { compatibility ->
                if (authorizationEndpoint != compatibility.authorizationEndpoint) {
                    put(Field.AUTHORIZATION_ENDPOINT, "Claude debug Authorization endpoint는 OAuth 계약의 고정값을 사용하세요.")
                }
                if (tokenEndpoint != compatibility.tokenEndpoint) {
                    put(Field.TOKEN_ENDPOINT, "Claude debug Token endpoint는 OAuth 계약의 고정값을 사용하세요.")
                }
                if (inferenceEndpoint != compatibility.messagesEndpoint) {
                    put(Field.INFERENCE_ENDPOINT, "Claude debug LLM endpoint는 OAuth 계약의 고정값을 사용하세요.")
                }
                if (scopes != compatibility.scopes) {
                    put(Field.SCOPES, "Claude debug OAuth scopes는 OAuth 계약의 고정값을 사용하세요.")
                }
                if (callbackPort != compatibility.callbackPort) {
                    put(Field.CALLBACK_PORT, "Claude debug callback port는 OAuth 계약의 고정값을 사용하세요.")
                }
                if (model !in compatibility.modelOptions) {
                    put(Field.MODEL, "승인된 debug OAuth 모델을 선택하세요.")
                }
            }
        }
        if (type == ProviderType.GEMINI) {
            if (authorizationEndpoint != GeminiOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "Gemini Authorization endpoint는 Provider 계약의 고정값을 사용하세요.",
                )
            }
            if (tokenEndpoint != GeminiOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "Gemini Token endpoint는 Provider 계약의 고정값을 사용하세요.",
                )
            }
            if (inferenceEndpoint != GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "Gemini LLM endpoint는 Provider 계약의 고정값을 사용하세요.",
                )
            }
            if (scopes != GeminiOAuthContract.SCOPES) {
                put(Field.SCOPES, "Gemini OAuth scopes는 Provider 계약의 고정값을 사용하세요.")
            }
            if (callbackPort != GeminiOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "Gemini callback port는 8085를 사용하세요.")
            }
        }
        if (type == ProviderType.CODEX) {
            CodexOAuthCompatibilityRegistry.current()?.takeIf {
                it.clientId == clientId && it.responsesEndpoint == inferenceEndpoint
            }?.let { compatibility ->
                if (model !in compatibility.modelOptions) {
                    put(Field.MODEL, "승인된 debug OAuth 모델을 선택하세요.")
                }
            }
            if (authorizationEndpoint != CodexOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "Codex Authorization endpoint는 OAuth 계약의 고정값을 사용하세요.",
                )
            }
            if (tokenEndpoint != CodexOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "Codex Token endpoint는 OAuth 계약의 고정값을 사용하세요.",
                )
            }
            if (scopes != CodexOAuthContract.SCOPES) {
                put(Field.SCOPES, "Codex OAuth scopes는 OAuth 계약의 고정값을 사용하세요.")
            }
            if (callbackPort != CodexOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "Codex callback port는 1455를 사용하세요.")
            }
        }
        if (type == ProviderType.XAI) {
            XaiOAuthCompatibilityRegistry.matching(clientId, inferenceEndpoint)?.let { compatibility ->
                if (model !in compatibility.modelOptions) {
                    put(Field.MODEL, "승인된 debug OAuth 모델을 선택하세요.")
                }
            }
            if (authorizationEndpoint != XaiOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "xAI Authorization endpoint는 OAuth 계약의 고정값을 사용하세요.",
                )
            }
            if (tokenEndpoint != XaiOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "xAI Token endpoint는 OAuth 계약의 고정값을 사용하세요.",
                )
            }
            if (inferenceEndpoint != XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "xAI LLM endpoint는 OAuth 계약의 고정값을 사용하세요.",
                )
            }
            val expectedScopes = XaiOAuthCompatibilityRegistry.matching(clientId)?.scopes
                ?: XaiOAuthContract.SCOPES
            if (scopes != expectedScopes) {
                put(Field.SCOPES, "xAI OAuth scopes는 OAuth 계약의 고정값을 사용하세요.")
            }
            if (callbackPort != XaiOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "xAI callback port는 56121을 사용하세요.")
            }
        }
        if (clientId.isBlank()) put(Field.CLIENT_ID, "OAuth Public Client ID를 입력하세요.")
        if (scopes.none(String::isNotBlank)) put(Field.SCOPES, "OAuth scope를 하나 이상 입력하세요.")
        if (model.isBlank()) put(Field.MODEL, "기본 모델을 선택하거나 입력하세요.")
        if (model.isNotBlank() && !containsEnabledModel(model)) {
            put(Field.MODEL, "기본 모델은 활성화된 모델 후보에서 선택하세요.")
        }
        if (enabledModelIds().isEmpty()) {
            put(Field.MODEL, "활성화된 모델 후보를 하나 이상 추가하세요.")
        }
        if (callbackPort !in 1..65533) {
            put(Field.CALLBACK_PORT, "Callback port는 1~65533 범위로 입력하세요.")
        }
    }

    enum class Field {
        LABEL,
        AUTHORIZATION_ENDPOINT,
        TOKEN_ENDPOINT,
        INFERENCE_ENDPOINT,
        CLIENT_ID,
        SCOPES,
        MODEL,
        CALLBACK_PORT,
    }

    companion object {
        const val DEFAULT_CALLBACK_PORT = 54545

        fun fromJson(json: JSONObject): ProviderProfile {
            val model = json.getString("model").trim()
            val catalog = if (json.has("model_catalog")) {
                parseModelCatalog(json.optJSONArray("model_catalog"), model)
            } else {
                legacyModelCatalog(model)
            }
            return ProviderProfile(
                id = json.getString("id"),
                label = json.getString("label"),
                type = ProviderType.fromWireName(json.getString("type")),
                authorizationEndpoint = json.getString("authorization_endpoint"),
                tokenEndpoint = json.getString("token_endpoint"),
                inferenceEndpoint = json.getString("inference_endpoint"),
                clientId = json.getString("client_id"),
                scopes = json.optString("scopes")
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank),
                model = model,
                modelCatalog = catalog,
                callbackPort = json.optInt("callback_port", DEFAULT_CALLBACK_PORT),
                googleProjectId = json.optString("google_project_id").ifBlank { null },
                createdAtMs = json.optLong("created_at_ms", System.currentTimeMillis()),
            )
        }

        fun draft(type: ProviderType, label: String): ProviderProfile {
            val anthropicCompatibility = if (type == ProviderType.ANTHROPIC) {
                AnthropicOAuthCompatibilityRegistry.current()
            } else {
                null
            }
            val codexCompatibility = if (type == ProviderType.CODEX) {
                CodexOAuthCompatibilityRegistry.current()
            } else {
                null
            }
            val xaiCompatibility = if (type == ProviderType.XAI) {
                XaiOAuthCompatibilityRegistry.current()
            } else {
                null
            }
            val defaultModel = when (type) {
                ProviderType.ANTHROPIC -> anthropicCompatibility?.defaultModel.orEmpty()
                ProviderType.GEMINI -> GeminiProfileDefaults.DEFAULT_MODEL
                ProviderType.CODEX -> codexCompatibility?.defaultModel.orEmpty()
                ProviderType.XAI -> xaiCompatibility?.defaultModel.orEmpty()
                else -> ""
            }
            val modelCatalog = when (type) {
                ProviderType.GEMINI -> GeminiProfileDefaults.MODELS.map { modelId ->
                    ProviderModelCandidate(modelId, ProviderModelSource.PROVIDER_APPROVED)
                }
                ProviderType.ANTHROPIC -> anthropicCompatibility?.modelOptions.orEmpty().map { modelId ->
                    ProviderModelCandidate(modelId, ProviderModelSource.USER_ADDED)
                }
                ProviderType.CODEX -> codexCompatibility?.modelOptions.orEmpty().map { modelId ->
                    ProviderModelCandidate(modelId, ProviderModelSource.USER_ADDED)
                }
                ProviderType.XAI -> xaiCompatibility?.modelOptions.orEmpty().map { modelId ->
                    ProviderModelCandidate(modelId, ProviderModelSource.USER_ADDED)
                }
                ProviderType.OPENAI_COMPATIBLE -> emptyList()
            }
            return ProviderProfile(
                label = label,
                type = type,
                authorizationEndpoint = when (type) {
                    ProviderType.ANTHROPIC -> anthropicCompatibility?.authorizationEndpoint.orEmpty()
                    ProviderType.GEMINI -> GeminiOAuthContract.AUTHORIZATION_ENDPOINT
                    ProviderType.CODEX -> CodexOAuthContract.AUTHORIZATION_ENDPOINT
                    ProviderType.XAI -> XaiOAuthContract.AUTHORIZATION_ENDPOINT
                    else -> ""
                },
                tokenEndpoint = when (type) {
                    ProviderType.ANTHROPIC -> anthropicCompatibility?.tokenEndpoint.orEmpty()
                    ProviderType.GEMINI -> GeminiOAuthContract.TOKEN_ENDPOINT
                    ProviderType.CODEX -> CodexOAuthContract.TOKEN_ENDPOINT
                    ProviderType.XAI -> XaiOAuthContract.TOKEN_ENDPOINT
                    else -> ""
                },
                inferenceEndpoint = codexCompatibility?.responsesEndpoint
                    ?: xaiCompatibility?.chatCompletionsEndpoint
                    ?: anthropicCompatibility?.messagesEndpoint
                    ?: type.inferenceEndpointPlaceholder,
                clientId = codexCompatibility?.clientId
                    ?: xaiCompatibility?.clientId
                    ?: anthropicCompatibility?.clientId.orEmpty(),
                scopes = xaiCompatibility?.scopes
                    ?: anthropicCompatibility?.scopes
                    ?: type.defaultScopes.split(" ").filter(String::isNotBlank),
                model = defaultModel,
                modelCatalog = modelCatalog,
                callbackPort = when (type) {
                    ProviderType.ANTHROPIC -> anthropicCompatibility?.callbackPort ?: DEFAULT_CALLBACK_PORT
                    ProviderType.GEMINI -> GeminiOAuthContract.CALLBACK_PORT
                    ProviderType.CODEX -> CodexOAuthContract.CALLBACK_PORT
                    ProviderType.XAI -> XaiOAuthContract.CALLBACK_PORT
                    else -> DEFAULT_CALLBACK_PORT
                },
            )
        }

        private fun validateHttps(value: String, label: String): String? {
            if (value.isBlank()) return "$label 값을 입력하세요."
            val uri = runCatching { URI(value.replace("{model}", "model")) }.getOrNull()
                ?: return "$label 형식의 올바른 URL을 입력하세요."
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                return "${label}에는 사용자 정보 없이 HTTPS URL을 사용하세요."
            }
            return null
        }

        private fun parseModelCatalog(array: JSONArray?, legacyModel: String): List<ProviderModelCandidate> {
            if (array == null) return legacyModelCatalog(legacyModel)
            var malformedItemFound = false
            val candidates = buildList {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index)
                    if (item == null) {
                        malformedItemFound = true
                        return@repeat
                    }
                    val modelId = item.optString("model_id").trim()
                    val source = ProviderModelSource.fromWireName(item.optString("source"))
                    if (modelId.isEmpty() || source == null) {
                        malformedItemFound = true
                        return@repeat
                    }
                    add(
                        ProviderModelCandidate(
                            modelId = modelId,
                            source = source,
                            enabled = item.optBoolean("enabled", true),
                        ),
                    )
                }
            }
            val normalized = normalizeModelCatalog(candidates)
            if (
                malformedItemFound &&
                legacyModel.isNotBlank() &&
                normalized.none { it.modelId.equals(legacyModel.trim(), ignoreCase = true) }
            ) {
                return normalized + legacyModelCatalog(legacyModel)
            }
            return normalized.ifEmpty { legacyModelCatalog(legacyModel) }
        }
    }
}
