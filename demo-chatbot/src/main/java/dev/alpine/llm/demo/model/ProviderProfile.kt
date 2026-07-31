package dev.alpine.llm.demo.model

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
        displayName = "Claude / Anthropic",
        description = "Anthropic Messages-compatible OAuth endpoint",
        inferenceEndpointPlaceholder = "https://api.anthropic.com/v1/messages",
        defaultScopes = "openid profile offline_access",
    ),
    GEMINI(
        wireName = "gemini",
        displayName = "Google Gemini",
        description = "Gemini generateContent OAuth endpoint",
        inferenceEndpointPlaceholder =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        defaultScopes = "openid profile https://www.googleapis.com/auth/cloud-platform",
    ),
    OPENAI_COMPATIBLE(
        wireName = "openai_compatible",
        displayName = "OpenAI-compatible",
        description = "OAuth-enabled Chat Completions endpoint",
        inferenceEndpointPlaceholder = "https://provider.example.com/v1/chat/completions",
        defaultScopes = "openid profile offline_access",
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
            authorizationEndpoint = "",
            tokenEndpoint = "",
            inferenceEndpoint = type.inferenceEndpointPlaceholder,
            clientId = "",
            scopes = type.defaultScopes.split(" "),
            model = "",
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
