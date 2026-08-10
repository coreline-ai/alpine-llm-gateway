package dev.alpine.llm

/** OpenAI Codex OAuth Authorization Code + PKCE configuration. */
object CodexOAuthContract {
    const val AUTHORIZATION_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
    const val RESPONSES_ENDPOINT_PLACEHOLDER = "https://provider.example.com/v1/responses"
    const val CALLBACK_PORT = 1455
    const val REDIRECT_PATH = "/auth/callback"
    const val REDIRECT_HOST = "localhost"

    val SCOPES = listOf("openid", "profile", "email", "offline_access")

    fun providerConfig(
        providerId: String,
        clientId: String,
        refreshSkewMs: Long = 5 * 60 * 1000L,
        callbackTimeoutMs: Long = 5 * 60 * 1000L,
    ): OAuthProviderConfig {
        val compatibility = CodexOAuthCompatibilityRegistry.matching(clientId)
        return OAuthProviderConfig(
        providerId = providerId,
        authorizationEndpoint = AUTHORIZATION_ENDPOINT,
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = clientId,
        scopes = SCOPES,
        callbackPort = CALLBACK_PORT,
        redirectPath = REDIRECT_PATH,
        redirectHost = REDIRECT_HOST,
        callbackFallbackPorts = emptyList(),
        extraAuthorizationParams = compatibility?.extraAuthorizationParams.orEmpty(),
        tokenRequestEncoding = OAuthTokenRequestEncoding.JSON,
        tokenRequestAdapter = StandardOAuthTokenRequestAdapter,
        tokenResponseAdapter = if (compatibility == null) {
            StandardOAuthTokenResponseAdapter()
        } else {
            JwtClaimMetadataTokenResponseAdapter()
        },
        tokenRequestMaxAttempts = 3,
        tokenRetryInitialDelayMs = 1_000L,
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
        )
    }
}
