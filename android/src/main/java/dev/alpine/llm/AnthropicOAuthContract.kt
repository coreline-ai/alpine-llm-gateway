package dev.alpine.llm

/**
 * Claude account OAuth contract mirrored from the OpenMinis Android reference.
 *
 * This compatibility registration is suitable for local demo validation only.
 * Production apps should use an Anthropic-owned registration and supported API terms.
 */
object AnthropicOAuthContract {
    const val AUTHORIZATION_ENDPOINT = "https://claude.ai/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://console.anthropic.com/v1/oauth/token"
    const val MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val PUBLIC_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val CALLBACK_PORT = 54545
    const val REDIRECT_PATH = "/callback"
    const val REDIRECT_HOST = "localhost"
    const val OAUTH_BETA = "oauth-2025-04-20"

    val SCOPES = listOf(
        "org:create_api_key",
        "user:profile",
        "user:inference",
    )

    fun providerConfig(
        providerId: String,
        clientId: String = PUBLIC_CLIENT_ID,
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
        pkceMode = OAuthPkceMode.BASE64URL_96_BYTES,
        tokenRequestEncoding = OAuthTokenRequestEncoding.JSON,
        tokenRequestAdapter = OAuthTokenRequestAdapter { context ->
            if (context.grantType == OAuthTokenGrantType.AUTHORIZATION_CODE) {
                context.parameters + ("state" to requireNotNull(context.state))
            } else {
                context.parameters
            }
        },
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
