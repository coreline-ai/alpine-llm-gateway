package dev.alpine.llm

/** Credential stays in the Android host and is never written into Alpine. */
data class OAuthCredential(
    val accessToken: String,
    val tokenType: String = "Bearer",
)

data class HostLlmResult(
    val bodyJson: String,
    val statusCode: Int = 200,
)

class HostLlmRequestException(message: String) : Exception(message)

/**
 * Implement this in the Android application layer using its Provider client.
 * The implementation may stream internally, but the first contract is a
 * complete response so it can be used by the current Alpine CLI.
 */
interface HostLlmBridge {
    suspend fun complete(requestJson: String, credential: OAuthCredential): HostLlmResult
}

class OAuthLlmSession(
    private val oauth: OAuthManager,
    private val bridge: HostLlmBridge,
) {
    suspend fun complete(requestJson: String): HostLlmResult {
        val token = oauth.validToken()
            ?: throw OAuthRequiredException(oauth)
        val first = bridge.complete(
            requestJson,
            OAuthCredential(token.accessToken, token.tokenType),
        )
        if (first.statusCode != 401) return first

        val refreshed = oauth.refreshAfterUnauthorized(token.accessToken)
            ?: throw OAuthRequiredException(oauth)
        val retried = bridge.complete(
            requestJson,
            OAuthCredential(refreshed.accessToken, refreshed.tokenType),
        )
        if (retried.statusCode == 401) {
            oauth.invalidateIfCurrent(refreshed.accessToken)
            throw OAuthRequiredException(oauth)
        }
        return retried
    }
}

class OAuthRequiredException(@Suppress("UNUSED_PARAMETER") manager: OAuthManager) : Exception(
    "OAuth login is required before calling the LLM provider",
)
