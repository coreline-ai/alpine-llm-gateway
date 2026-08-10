package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class AnthropicOAuthContractTest {
    @After
    fun clearCompatibility() {
        AnthropicOAuthCompatibilityRegistry.clear()
    }

    @Test
    fun anthropicDefaultsExposeOnlySafeHostConfigurationPlaceholders() {
        assertEquals(
            "https://provider.example.com/oauth/authorize",
            AnthropicOAuthContract.AUTHORIZATION_ENDPOINT_PLACEHOLDER,
        )
        assertEquals(
            "https://provider.example.com/oauth/token",
            AnthropicOAuthContract.TOKEN_ENDPOINT_PLACEHOLDER,
        )
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicOAuthContract.MESSAGES_ENDPOINT)
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicOAuthContract.providerConfig("claude", "unapproved-client")
        }
    }

    @Test
    fun approvedDebugCompatibilityUsesClaudePkceAndJsonStateExchange() {
        val compatibility = AnthropicOAuthCompatibilityConfig(
            sourceRevision = "reference@revision",
            clientId = "approved-debug-public-client",
            authorizationEndpoint = "https://auth.example.test/authorize",
            tokenEndpoint = "https://auth.example.test/token",
            messagesEndpoint = AnthropicOAuthContract.MESSAGES_ENDPOINT,
            scopes = listOf("profile", "inference"),
            callbackPort = 54545,
            redirectHost = "localhost",
            redirectPath = "/callback",
            defaultModel = "claude-current",
            modelOptions = listOf("claude-current", "claude-fast"),
        )
        AnthropicOAuthCompatibilityRegistry.installApprovedDebug(compatibility)

        val config = AnthropicOAuthContract.providerConfig("claude", compatibility.clientId)

        assertEquals("http://localhost:54545/callback", config.redirectUri())
        assertEquals(OAuthPkceMode.BASE64URL_96_BYTES, config.pkceMode)
        assertEquals(OAuthTokenRequestEncoding.JSON, config.tokenRequestEncoding)
        assertEquals(
            mapOf("grant_type" to "authorization_code", "code_verifier" to "verifier", "state" to "state"),
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(
                    grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                    parameters = mapOf("grant_type" to "authorization_code", "code_verifier" to "verifier"),
                    state = "state",
                ),
            ),
        )
        assertTrue(
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(
                    grantType = OAuthTokenGrantType.REFRESH_TOKEN,
                    parameters = mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh"),
                ),
            ).containsKey("refresh_token"),
        )
    }
}
