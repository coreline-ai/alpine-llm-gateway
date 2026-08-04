package dev.alpine.chat.provider.android.model

import dev.alpine.llm.AnthropicOAuthContract
import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.XaiOAuthContract
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
        displayName = "Anthropic compatibility",
        description = "Reference-only direct OAuth; not a MobileAgent release connector",
        inferenceEndpointPlaceholder = AnthropicOAuthContract.MESSAGES_ENDPOINT,
        defaultScopes = AnthropicOAuthContract.SCOPES.joinToString(" "),
    ),
    GEMINI(
        wireName = "gemini",
        displayName = "Google Gemini",
        description = "Official Gemini API with app-owned Google OAuth",
        inferenceEndpointPlaceholder = GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT,
        defaultScopes = GeminiOAuthContract.SCOPES.joinToString(" "),
    ),
    OPENAI_COMPATIBLE(
        wireName = "openai_compatible",
        displayName = "OpenAI-compatible",
        description = "OAuth-enabled Chat Completions endpoint",
        inferenceEndpointPlaceholder = "https://provider.example.com/v1/chat/completions",
        defaultScopes = "openid profile offline_access",
    ),
    CODEX(
        wireName = "codex",
        displayName = "Codex compatibility",
        description = "Reference-only direct OAuth; not a MobileAgent release connector",
        inferenceEndpointPlaceholder = CodexOAuthContract.RESPONSES_ENDPOINT,
        defaultScopes = CodexOAuthContract.SCOPES.joinToString(" "),
    ),
    XAI(
        wireName = "xai",
        displayName = "xAI compatibility",
        description = "Reference-only direct OAuth; not a MobileAgent release connector",
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
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
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
        .putOpt("anthropic_beta", anthropicBeta)
        .putOpt("google_project_id", googleProjectId)
        .put("created_at_ms", createdAtMs)

    fun validationErrors(): Map<Field, String> = buildMap {
        if (label.isBlank()) put(Field.LABEL, "Profile name is required")
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
            put(Field.INFERENCE_ENDPOINT, "Gemini endpoint must contain {model}")
        }
        if (type == ProviderType.GEMINI) {
            if (authorizationEndpoint != GeminiOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "Gemini authorization endpoint is fixed by the Provider contract",
                )
            }
            if (tokenEndpoint != GeminiOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "Gemini token endpoint is fixed by the Provider contract",
                )
            }
            if (inferenceEndpoint != GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "Gemini inference endpoint is fixed by the Provider contract",
                )
            }
            if (scopes != GeminiOAuthContract.SCOPES) {
                put(Field.SCOPES, "Gemini OAuth scopes are fixed by the Provider contract")
            }
            if (callbackPort != GeminiOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "Gemini callback port must be 8085")
            }
        }
        if (type == ProviderType.ANTHROPIC) {
            if (authorizationEndpoint != AnthropicOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "Claude authorization endpoint is fixed by the Provider contract",
                )
            }
            if (tokenEndpoint != AnthropicOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "Claude token endpoint is fixed by the Provider contract",
                )
            }
            if (inferenceEndpoint != AnthropicOAuthContract.MESSAGES_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "Claude inference endpoint is fixed by the Provider contract",
                )
            }
            if (scopes != AnthropicOAuthContract.SCOPES) {
                put(Field.SCOPES, "Claude OAuth scopes are fixed by the Provider contract")
            }
            if (callbackPort != AnthropicOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "Claude callback port must be 54545")
            }
            if (anthropicBeta != AnthropicOAuthContract.OAUTH_BETA) {
                put(Field.ANTHROPIC_BETA, "Claude OAuth beta header is fixed by the Provider contract")
            }
        }
        if (type == ProviderType.CODEX) {
            if (authorizationEndpoint != CodexOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "Codex authorization endpoint is fixed by the Provider contract",
                )
            }
            if (tokenEndpoint != CodexOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "Codex token endpoint is fixed by the Provider contract",
                )
            }
            if (inferenceEndpoint != CodexOAuthContract.RESPONSES_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "Codex inference endpoint is fixed by the Provider contract",
                )
            }
            if (scopes != CodexOAuthContract.SCOPES) {
                put(Field.SCOPES, "Codex OAuth scopes are fixed by the Provider contract")
            }
            if (callbackPort != CodexOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "Codex callback port must be 1455")
            }
        }
        if (type == ProviderType.XAI) {
            if (authorizationEndpoint != XaiOAuthContract.AUTHORIZATION_ENDPOINT) {
                put(
                    Field.AUTHORIZATION_ENDPOINT,
                    "xAI authorization endpoint is fixed by the Provider contract",
                )
            }
            if (tokenEndpoint != XaiOAuthContract.TOKEN_ENDPOINT) {
                put(
                    Field.TOKEN_ENDPOINT,
                    "xAI token endpoint is fixed by the Provider contract",
                )
            }
            if (inferenceEndpoint != XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT) {
                put(
                    Field.INFERENCE_ENDPOINT,
                    "xAI inference endpoint is fixed by the Provider contract",
                )
            }
            if (scopes != XaiOAuthContract.SCOPES) {
                put(Field.SCOPES, "xAI OAuth scopes are fixed by the Provider contract")
            }
            if (callbackPort != XaiOAuthContract.CALLBACK_PORT) {
                put(Field.CALLBACK_PORT, "xAI callback port must be 56121")
            }
        }
        if (clientId.isBlank()) put(Field.CLIENT_ID, "Public client ID is required")
        if (scopes.none(String::isNotBlank)) put(Field.SCOPES, "At least one scope is required")
        if (model.isBlank()) put(Field.MODEL, "Default model is required")
        if (callbackPort !in 1..65533) {
            put(Field.CALLBACK_PORT, "Callback port must be between 1 and 65533")
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
        ANTHROPIC_BETA,
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
            anthropicBeta = json.optString("anthropic_beta").ifBlank { null },
            googleProjectId = json.optString("google_project_id").ifBlank { null },
            createdAtMs = json.optLong("created_at_ms", System.currentTimeMillis()),
        )

        fun draft(type: ProviderType, label: String): ProviderProfile = ProviderProfile(
            label = label,
            type = type,
            authorizationEndpoint = when (type) {
                ProviderType.ANTHROPIC -> AnthropicOAuthContract.AUTHORIZATION_ENDPOINT
                ProviderType.GEMINI -> GeminiOAuthContract.AUTHORIZATION_ENDPOINT
                ProviderType.CODEX -> CodexOAuthContract.AUTHORIZATION_ENDPOINT
                ProviderType.XAI -> XaiOAuthContract.AUTHORIZATION_ENDPOINT
                else -> ""
            },
            tokenEndpoint = when (type) {
                ProviderType.ANTHROPIC -> AnthropicOAuthContract.TOKEN_ENDPOINT
                ProviderType.GEMINI -> GeminiOAuthContract.TOKEN_ENDPOINT
                ProviderType.CODEX -> CodexOAuthContract.TOKEN_ENDPOINT
                ProviderType.XAI -> XaiOAuthContract.TOKEN_ENDPOINT
                else -> ""
            },
            inferenceEndpoint = type.inferenceEndpointPlaceholder,
            clientId = "",
            scopes = type.defaultScopes.split(" "),
            model = when (type) {
                ProviderType.ANTHROPIC -> AnthropicProfileDefaults.DEFAULT_MODEL
                ProviderType.GEMINI -> GeminiProfileDefaults.DEFAULT_MODEL
                ProviderType.CODEX -> CodexProfileDefaults.DEFAULT_MODEL
                ProviderType.XAI -> XaiProfileDefaults.DEFAULT_MODEL
                else -> ""
            },
            callbackPort = when (type) {
                ProviderType.ANTHROPIC -> AnthropicOAuthContract.CALLBACK_PORT
                ProviderType.GEMINI -> GeminiOAuthContract.CALLBACK_PORT
                ProviderType.CODEX -> CodexOAuthContract.CALLBACK_PORT
                ProviderType.XAI -> XaiOAuthContract.CALLBACK_PORT
                else -> DEFAULT_CALLBACK_PORT
            },
            anthropicBeta = if (type == ProviderType.ANTHROPIC) {
                AnthropicOAuthContract.OAUTH_BETA
            } else {
                null
            },
        )

        private fun validateHttps(value: String, label: String): String? {
            if (value.isBlank()) return "$label is required"
            val uri = runCatching { URI(value.replace("{model}", "model")) }.getOrNull()
                ?: return "$label must be a valid URL"
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                return "$label must use HTTPS"
            }
            return null
        }
    }
}
