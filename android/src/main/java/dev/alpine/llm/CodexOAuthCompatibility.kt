package dev.alpine.llm

/**
 * Process-local Codex consumer compatibility settings installed by an explicitly approved host.
 *
 * The Android libraries intentionally ship without a third-party registration or private endpoint.
 * A debug application may install those values from its debug-only source set after the user has
 * approved that compatibility boundary.
 */
data class CodexOAuthCompatibilityConfig(
    val sourceRevision: String,
    val clientId: String,
    val responsesEndpoint: String,
    val defaultModel: String,
    val modelOptions: List<String>,
    val extraAuthorizationParams: Map<String, String>,
    val requestHeaders: Map<String, String>,
    val accountIdHeader: String,
    val includeEncryptedReasoning: Boolean = false,
    val reasoningEffort: String? = null,
) {
    init {
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        ProviderAdapterJson.requireHttps(responsesEndpoint, "responsesEndpoint")
        require(modelOptions.isNotEmpty()) { "modelOptions must not be empty" }
        require(modelOptions.all(String::isNotBlank)) { "modelOptions must not contain blanks" }
        require(modelOptions.distinct().size == modelOptions.size) {
            "modelOptions must not contain duplicates"
        }
        require(defaultModel in modelOptions) { "defaultModel must be in modelOptions" }
        require(extraAuthorizationParams.keys.all { it.matches(PARAMETER_NAME) }) {
            "extraAuthorizationParams contains an invalid name"
        }
        require(extraAuthorizationParams.values.all { it.isNotBlank() }) {
            "extraAuthorizationParams contains a blank value"
        }
        ProviderAdapterJson.requireSafeHeaders(requestHeaders)
        require(accountIdHeader.matches(HEADER_NAME)) { "accountIdHeader is invalid" }
        require(requestHeaders.keys.none { it.equals(accountIdHeader, ignoreCase = true) }) {
            "requestHeaders must not pre-set accountIdHeader"
        }
        require(reasoningEffort == null || reasoningEffort in REASONING_EFFORTS) {
            "reasoningEffort is unsupported"
        }
    }

    private companion object {
        val PARAMETER_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,80}")
        val REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh", "max")
    }
}

object CodexOAuthCompatibilityRegistry {
    @Volatile
    private var active: CodexOAuthCompatibilityConfig? = null

    fun installApprovedDebug(config: CodexOAuthCompatibilityConfig) {
        active = config
    }

    fun current(): CodexOAuthCompatibilityConfig? = active

    fun matching(
        clientId: String,
        responsesEndpoint: String? = null,
    ): CodexOAuthCompatibilityConfig? = active?.takeIf { config ->
        config.clientId == clientId &&
            (responsesEndpoint == null || config.responsesEndpoint == responsesEndpoint)
    }

    fun clear() {
        active = null
    }
}
