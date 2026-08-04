package dev.alpine.llm

/**
 * Claude account OAuth compatibility contract.
 *
 * No third-party public client registration is bundled. Hosts must inject a
 * registration that they own and are authorized to use. This contract remains
 * reference-only; MobileAgent production traffic uses the server-side
 * Anthropic API adapter behind the MobileAgent BFF.
 */
object AnthropicOAuthContract {
    const val AUTHORIZATION_ENDPOINT = "https://claude.ai/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://console.anthropic.com/v1/oauth/token"
    const val MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
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
        clientId: String,
        refreshSkewMs: Long = 5 * 60 * 1000L,
        callbackTimeoutMs: Long = 5 * 60 * 1000L,
    ): OAuthProviderConfig {
        require(clientId.isNotBlank()) { "clientId must be supplied by the host" }
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
}
