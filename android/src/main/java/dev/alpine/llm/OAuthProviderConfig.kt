package dev.alpine.llm

/** Provider-neutral OAuth 2.0 Authorization Code + PKCE configuration. */
data class OAuthProviderConfig(
    val providerId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    val callbackPort: Int,
    val redirectPath: String = "/oauth/callback",
    val clientSecret: String? = null,
    val clientAuthMethod: ClientAuthMethod = ClientAuthMethod.NONE,
    val extraAuthorizationParams: Map<String, String> = emptyMap(),
    val extraTokenParams: Map<String, String> = emptyMap(),
    val tokenRequestEncoding: OAuthTokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
    val tokenRequestAdapter: OAuthTokenRequestAdapter = StandardOAuthTokenRequestAdapter,
    val tokenResponseAdapter: OAuthTokenResponseAdapter = StandardOAuthTokenResponseAdapter(),
    val refreshSkewMs: Long = 5 * 60 * 1000L,
    val callbackTimeoutMs: Long = 5 * 60 * 1000L,
) {
    enum class ClientAuthMethod { NONE, BODY, BASIC }

    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(authorizationEndpoint.startsWith("https://")) {
            "authorizationEndpoint must use HTTPS"
        }
        require(tokenEndpoint.startsWith("https://")) { "tokenEndpoint must use HTTPS" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(callbackPort in 1..65533) { "callbackPort must allow two fallback ports" }
        require(redirectPath.startsWith("/")) { "redirectPath must start with /" }
        require(refreshSkewMs >= 0) { "refreshSkewMs must not be negative" }
        require(callbackTimeoutMs > 0) { "callbackTimeoutMs must be positive" }
        if (clientAuthMethod != ClientAuthMethod.NONE) {
            require(!clientSecret.isNullOrBlank()) {
                "clientSecret is required by the selected clientAuthMethod"
            }
        }
    }

    fun redirectUri(port: Int = callbackPort): String =
        "http://127.0.0.1:$port$redirectPath"
}
