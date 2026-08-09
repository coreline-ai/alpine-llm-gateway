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
    ): OAuthProviderConfig = OAuthProviderConfig(
        providerId = providerId,
        authorizationEndpoint = AUTHORIZATION_ENDPOINT,
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = clientId,
        scopes = SCOPES,
        callbackPort = CALLBACK_PORT,
        redirectPath = REDIRECT_PATH,
        redirectHost = REDIRECT_HOST,
        callbackFallbackPorts = emptyList(),
        extraAuthorizationParams = mapOf(
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs",
            "id_token_add_organizations" to "true",
        ),
        tokenRequestEncoding = OAuthTokenRequestEncoding.JSON,
        tokenRequestAdapter = StandardOAuthTokenRequestAdapter,
        tokenResponseAdapter = JwtClaimMetadataTokenResponseAdapter(),
        tokenRequestMaxAttempts = 3,
        tokenRetryInitialDelayMs = 1_000L,
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
