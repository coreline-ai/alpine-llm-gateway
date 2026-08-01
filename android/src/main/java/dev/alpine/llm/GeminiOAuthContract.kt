package dev.alpine.llm

/** Official Google OAuth and Gemini generateContent contract for app-owned clients. */
object GeminiOAuthContract {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val GENERATE_CONTENT_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    const val CALLBACK_PORT = 8085
    const val REDIRECT_PATH = "/oauth2callback"
    const val REDIRECT_HOST = "localhost"

    val SCOPES = listOf(
        "openid",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
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
        extraAuthorizationParams = mapOf(
            "access_type" to "offline",
            "prompt" to "consent",
            "include_granted_scopes" to "true",
        ),
        tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
