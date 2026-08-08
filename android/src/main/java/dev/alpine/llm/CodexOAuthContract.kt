package dev.alpine.llm

/**
 * Optional OpenAI account OAuth configuration supplied by the application owner.
 *
 * The public client ID identifies an OAuth registration and must not be copied from another
 * app or CLI. This class does not authorize a ChatGPT consumer endpoint for inference; callers
 * must provide an approved HTTPS Responses endpoint separately.
 */
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
        tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
        tokenRequestAdapter = StandardOAuthTokenRequestAdapter,
        tokenRequestMaxAttempts = 3,
        tokenRetryInitialDelayMs = 1_000L,
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
