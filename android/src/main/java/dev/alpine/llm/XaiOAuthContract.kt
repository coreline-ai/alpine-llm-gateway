package dev.alpine.llm

/** xAI endpoint metadata for an app-owned OAuth registration. */
object XaiOAuthContract {
    const val DISCOVERY_ENDPOINT = "https://auth.x.ai/.well-known/openid-configuration"
    const val AUTHORIZATION_ENDPOINT = "https://auth.x.ai/oauth2/authorize"
    const val TOKEN_ENDPOINT = "https://auth.x.ai/oauth2/token"
    const val CHAT_COMPLETIONS_ENDPOINT = "https://api.x.ai/v1/chat/completions"
    const val CALLBACK_PORT = 56121
    const val REDIRECT_PATH = "/callback"
    const val REDIRECT_HOST = "127.0.0.1"

    val SCOPES = listOf("openid", "profile", "email", "offline_access")

    fun providerConfig(
        providerId: String,
        clientId: String,
        refreshSkewMs: Long = 5 * 60 * 1000L,
        callbackTimeoutMs: Long = 5 * 60 * 1000L,
    ): OAuthProviderConfig {
        val compatibility = XaiOAuthCompatibilityRegistry.matching(clientId)
        return OAuthProviderConfig(
            providerId = providerId,
            authorizationEndpoint = AUTHORIZATION_ENDPOINT,
            tokenEndpoint = TOKEN_ENDPOINT,
            clientId = clientId,
            scopes = compatibility?.scopes ?: SCOPES,
            callbackPort = CALLBACK_PORT,
            redirectPath = REDIRECT_PATH,
            redirectHost = REDIRECT_HOST,
            callbackFallbackPorts = emptyList(),
            extraAuthorizationParams = compatibility?.extraAuthorizationParams.orEmpty(),
            includeAuthorizationNonce = true,
            tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
            tokenRequestAdapter = if (compatibility == null) {
                StandardOAuthTokenRequestAdapter
            } else {
                XaiCompatibilityTokenRequestAdapter
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
}

/** xAI consumer compatibility sends the PKCE challenge only during code exchange. */
object XaiCompatibilityTokenRequestAdapter : OAuthTokenRequestAdapter {
    override fun adapt(context: OAuthTokenRequestContext): Map<String, String> = when (context.grantType) {
        OAuthTokenGrantType.AUTHORIZATION_CODE -> context.parameters + mapOf(
            "code_challenge" to requireNotNull(context.codeChallenge) {
                "xAI authorization-code exchange requires a PKCE challenge"
            },
            "code_challenge_method" to "S256",
        )
        OAuthTokenGrantType.REFRESH_TOKEN -> context.parameters
    }
}
