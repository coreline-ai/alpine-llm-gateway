package ai.coreline.mobile_agent_auth

import java.net.URI

internal data class OAuthConfiguration(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val audience: String?,
) {
    companion object {
        private val CLIENT_ID = Regex("^[A-Za-z0-9._~-]{3,200}$")
        private val SCOPE = Regex("^[A-Za-z0-9:._~-]+$")
        private val ALLOWED_KEYS = setOf(
            "issuer",
            "clientId",
            "redirectUri",
            "scopes",
            "audience",
        )

        fun from(arguments: Map<*, *>?): OAuthConfiguration {
            requireNotNull(arguments) { "configuration is required" }
            require(arguments.keys.all { it is String && it in ALLOWED_KEYS }) {
                "configuration contains an unsupported field"
            }
            val issuer = arguments["issuer"] as? String
                ?: throw IllegalArgumentException("issuer is required")
            val clientId = arguments["clientId"] as? String
                ?: throw IllegalArgumentException("clientId is required")
            val redirectUri = arguments["redirectUri"] as? String
                ?: throw IllegalArgumentException("redirectUri is required")
            val scopes = (arguments["scopes"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?: throw IllegalArgumentException("scopes are required")
            val audience = (arguments["audience"] as? String)?.trim()?.ifBlank { null }

            validateIssuer(issuer)
            require(CLIENT_ID.matches(clientId)) { "clientId is invalid" }
            validateRedirect(redirectUri)
            require("openid" in scopes && scopes.all(SCOPE::matches)) {
                "scopes must include openid and contain safe values"
            }
            return OAuthConfiguration(issuer, clientId, redirectUri, scopes, audience)
        }

        private fun validateIssuer(value: String) {
            val uri = URI(value)
            require(
                uri.scheme == "https" &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null,
            ) { "issuer must be a clean HTTPS URL" }
        }

        private fun validateRedirect(value: String) {
            val uri = URI(value)
            val validHttps = uri.scheme == "https" && !uri.host.isNullOrBlank()
            val validMobileAgentScheme =
                value == "ai.coreline.mobileagent:/oauth/callback"
            require((validHttps || validMobileAgentScheme) && uri.userInfo == null) {
                "redirect URI is not allowed"
            }
        }
    }
}
