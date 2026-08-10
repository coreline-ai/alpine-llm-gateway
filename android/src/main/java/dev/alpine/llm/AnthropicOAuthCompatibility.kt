package dev.alpine.llm

/**
 * Process-local Claude Code OAuth settings installed only by an explicitly approved debug host.
 *
 * This contract covers authorization and token storage only. It deliberately contains no Claude
 * Code identifier prompt, CLI user-agent, or other inference fingerprint.
 */
data class AnthropicOAuthCompatibilityConfig(
    val sourceRevision: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val messagesEndpoint: String,
    val scopes: List<String>,
    val callbackPort: Int,
    val redirectHost: String,
    val redirectPath: String,
    val defaultModel: String,
    val modelOptions: List<String>,
) {
    init {
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        ProviderAdapterJson.requireHttps(authorizationEndpoint, "authorizationEndpoint")
        ProviderAdapterJson.requireHttps(tokenEndpoint, "tokenEndpoint")
        require(messagesEndpoint == AnthropicOAuthContract.MESSAGES_ENDPOINT) {
            "messagesEndpoint must use the Anthropic Messages endpoint"
        }
        require(scopes.isNotEmpty() && scopes.all(String::isNotBlank)) {
            "scopes must not be empty or blank"
        }
        require(scopes.distinct().size == scopes.size) { "scopes must not contain duplicates" }
        require(callbackPort in 1..65535) { "callbackPort must be valid" }
        require(redirectHost in LOOPBACK_HOSTS) { "redirectHost must be a loopback host" }
        require(redirectPath.startsWith("/")) { "redirectPath must start with /" }
        require(modelOptions.isNotEmpty() && modelOptions.all(String::isNotBlank)) {
            "modelOptions must not be empty or blank"
        }
        require(modelOptions.distinct().size == modelOptions.size) {
            "modelOptions must not contain duplicates"
        }
        require(defaultModel in modelOptions) { "defaultModel must be in modelOptions" }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
    }
}

object AnthropicOAuthCompatibilityRegistry {
    @Volatile
    private var active: AnthropicOAuthCompatibilityConfig? = null

    fun installApprovedDebug(config: AnthropicOAuthCompatibilityConfig) {
        active = config
    }

    fun current(): AnthropicOAuthCompatibilityConfig? = active

    fun matching(
        clientId: String,
        authorizationEndpoint: String? = null,
        tokenEndpoint: String? = null,
        messagesEndpoint: String? = null,
    ): AnthropicOAuthCompatibilityConfig? = active?.takeIf { config ->
        config.clientId == clientId &&
            (authorizationEndpoint == null || config.authorizationEndpoint == authorizationEndpoint) &&
            (tokenEndpoint == null || config.tokenEndpoint == tokenEndpoint) &&
            (messagesEndpoint == null || config.messagesEndpoint == messagesEndpoint)
    }

    fun clear() {
        active = null
    }
}

/** Claude Code's authorization-code exchange echoes the original state in its JSON body. */
object AnthropicCompatibilityTokenRequestAdapter : OAuthTokenRequestAdapter {
    override fun adapt(context: OAuthTokenRequestContext): Map<String, String> = when (context.grantType) {
        OAuthTokenGrantType.AUTHORIZATION_CODE -> context.parameters + mapOf(
            "state" to requireNotNull(context.state) {
                "Anthropic authorization-code exchange requires state"
            },
        )
        OAuthTokenGrantType.REFRESH_TOKEN -> context.parameters
    }
}
