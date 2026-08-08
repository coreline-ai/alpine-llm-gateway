package dev.alpine.chat.provider.android.model

import dev.alpine.llm.GeminiOAuthContract
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
        inferenceEndpointPlaceholder = "https://provider.example.com/v1/responses",
        defaultScopes = "",
    ),
    XAI(
        wireName = "xai",
        displayName = "xAI (승인 필요)",
        description = "앱 소유 OAuth 등록과 Provider 승인이 필요합니다. CLI scope 기본값은 제공하지 않습니다.",
        inferenceEndpointPlaceholder = "https://api.x.ai/v1/chat/completions",
        defaultScopes = "",
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
        if (clientId.isBlank()) put(Field.CLIENT_ID, "OAuth Public Client ID를 입력하세요.")
        if (scopes.none(String::isNotBlank)) put(Field.SCOPES, "OAuth scope를 하나 이상 입력하세요.")
        if (model.isBlank()) put(Field.MODEL, "기본 모델을 선택하거나 입력하세요.")
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

        fun fromJson(json: JSONObject): ProviderProfile = ProviderProfile(
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
            model = json.getString("model"),
            callbackPort = json.optInt("callback_port", DEFAULT_CALLBACK_PORT),
            googleProjectId = json.optString("google_project_id").ifBlank { null },
            createdAtMs = json.optLong("created_at_ms", System.currentTimeMillis()),
        )

        fun draft(type: ProviderType, label: String): ProviderProfile = ProviderProfile(
            label = label,
            type = type,
            authorizationEndpoint = when (type) {
                ProviderType.GEMINI -> GeminiOAuthContract.AUTHORIZATION_ENDPOINT
                else -> ""
            },
            tokenEndpoint = when (type) {
                ProviderType.GEMINI -> GeminiOAuthContract.TOKEN_ENDPOINT
                else -> ""
            },
            inferenceEndpoint = type.inferenceEndpointPlaceholder,
            clientId = "",
            scopes = type.defaultScopes.split(" ").filter(String::isNotBlank),
            model = when (type) {
                ProviderType.GEMINI -> GeminiProfileDefaults.DEFAULT_MODEL
                else -> ""
            },
            callbackPort = when (type) {
                ProviderType.GEMINI -> GeminiOAuthContract.CALLBACK_PORT
                else -> DEFAULT_CALLBACK_PORT
            },
        )

        private fun validateHttps(value: String, label: String): String? {
            if (value.isBlank()) return "$label 값을 입력하세요."
            val uri = runCatching { URI(value.replace("{model}", "model")) }.getOrNull()
                ?: return "$label 형식의 올바른 URL을 입력하세요."
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                return "${label}에는 사용자 정보 없이 HTTPS URL을 사용하세요."
            }
            return null
        }
    }
}
