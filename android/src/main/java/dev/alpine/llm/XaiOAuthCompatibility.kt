package dev.alpine.llm

/**
 * Process-local xAI consumer compatibility settings installed only by an explicitly approved
 * debug host. Android libraries intentionally ship without a third-party registration.
 */
data class XaiOAuthCompatibilityConfig(
    val sourceRevision: String,
    val clientId: String,
    val chatCompletionsEndpoint: String,
    val scopes: List<String>,
    val defaultModel: String,
    val modelOptions: List<String>,
    val extraAuthorizationParams: Map<String, String>,
) {
    init {
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(chatCompletionsEndpoint == XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT) {
            "chatCompletionsEndpoint must use the xAI contract endpoint"
        }
        require(scopes.isNotEmpty() && scopes.all(String::isNotBlank)) {
            "scopes must not be empty or blank"
        }
        require(scopes.distinct().size == scopes.size) { "scopes must not contain duplicates" }
        require(scopes.containsAll(XaiOAuthContract.SCOPES)) {
            "scopes must retain the xAI contract scopes"
        }
        require(modelOptions.isNotEmpty() && modelOptions.all(String::isNotBlank)) {
            "modelOptions must not be empty or blank"
        }
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
    }

    private companion object {
        val PARAMETER_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
    }
}

object XaiOAuthCompatibilityRegistry {
    @Volatile
    private var active: XaiOAuthCompatibilityConfig? = null

    fun installApprovedDebug(config: XaiOAuthCompatibilityConfig) {
        active = config
    }

    fun current(): XaiOAuthCompatibilityConfig? = active

    fun matching(
        clientId: String,
        chatCompletionsEndpoint: String? = null,
    ): XaiOAuthCompatibilityConfig? = active?.takeIf { config ->
        config.clientId == clientId &&
            (chatCompletionsEndpoint == null ||
                config.chatCompletionsEndpoint == chatCompletionsEndpoint)
    }

    fun clear() {
        active = null
    }
}
