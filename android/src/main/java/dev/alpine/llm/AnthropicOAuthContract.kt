package dev.alpine.llm

/**
 * Anthropic endpoint placeholders for an application-owner-approved OAuth registration.
 *
 * No consumer authorization endpoint, third-party public client registration, scope or beta
 * header is bundled. A host must supply and verify its own approved OAuth contract before
 * enabling direct inference.
 */
object AnthropicOAuthContract {
    const val AUTHORIZATION_ENDPOINT_PLACEHOLDER = "https://provider.example.com/oauth/authorize"
    const val TOKEN_ENDPOINT_PLACEHOLDER = "https://provider.example.com/oauth/token"
    const val MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"

    fun providerConfig(
        providerId: String,
        clientId: String,
        refreshSkewMs: Long = 5 * 60 * 1000L,
        callbackTimeoutMs: Long = 5 * 60 * 1000L,
    ): OAuthProviderConfig {
        val compatibility = requireNotNull(AnthropicOAuthCompatibilityRegistry.matching(clientId)) {
            "Anthropic compatibility is not installed for this client"
        }
        return OAuthProviderConfig(
            providerId = providerId,
            authorizationEndpoint = compatibility.authorizationEndpoint,
            tokenEndpoint = compatibility.tokenEndpoint,
            clientId = clientId,
            scopes = compatibility.scopes,
            callbackPort = compatibility.callbackPort,
            redirectPath = compatibility.redirectPath,
            redirectHost = compatibility.redirectHost,
            callbackFallbackPorts = emptyList(),
            pkceMode = OAuthPkceMode.BASE64URL_96_BYTES,
            tokenRequestEncoding = OAuthTokenRequestEncoding.JSON,
            tokenRequestAdapter = AnthropicCompatibilityTokenRequestAdapter,
            tokenRequestMaxAttempts = 3,
            tokenRetryInitialDelayMs = 1_000L,
            refreshSkewMs = refreshSkewMs,
            callbackTimeoutMs = callbackTimeoutMs,
        )
    }
}
