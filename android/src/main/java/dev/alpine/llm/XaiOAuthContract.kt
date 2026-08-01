package dev.alpine.llm

/** xAI Grok browser OAuth and inference contract used by the Android host. */
object XaiOAuthContract {
    const val DISCOVERY_ENDPOINT = "https://auth.x.ai/.well-known/openid-configuration"
    const val AUTHORIZATION_ENDPOINT = "https://auth.x.ai/oauth2/authorize"
    const val TOKEN_ENDPOINT = "https://auth.x.ai/oauth2/token"
    const val CHAT_COMPLETIONS_ENDPOINT = "https://api.x.ai/v1/chat/completions"
    const val CALLBACK_PORT = 56121
    const val REDIRECT_PATH = "/callback"
    const val REDIRECT_HOST = "127.0.0.1"
    const val REFERRER = "alpine-llm-gateway"

    val SCOPES = listOf(
        "openid",
        "profile",
        "email",
        "offline_access",
        "grok-cli:access",
        "api:access",
    )

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
        includeAuthorizationNonce = true,
        pkceMode = OAuthPkceMode.HEX_32_BYTES,
        extraAuthorizationParams = mapOf(
            "plan" to "generic",
            "referrer" to REFERRER,
        ),
        tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
        tokenRequestAdapter = OAuthTokenRequestAdapter { context ->
            if (context.grantType == OAuthTokenGrantType.AUTHORIZATION_CODE) {
                context.parameters + mapOf(
                    "code_challenge" to requireNotNull(context.codeChallenge),
                    "code_challenge_method" to "S256",
                )
            } else {
                context.parameters
            }
        },
        tokenResponseAdapter = JwtClaimMetadataTokenResponseAdapter(),
        tokenRequestMaxAttempts = 3,
        tokenRetryInitialDelayMs = 1_000L,
        discoveryEndpoint = DISCOVERY_ENDPOINT,
        trustedDiscoveryEndpointHosts = setOf("auth.x.ai"),
        callbackCorsAllowedOrigins = setOf(
            "https://auth.x.ai",
            "https://accounts.x.ai",
        ),
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
