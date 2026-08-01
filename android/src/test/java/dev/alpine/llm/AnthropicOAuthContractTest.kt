package dev.alpine.llm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicOAuthContractTest {
    @Test
    fun anthropicContractPinsOpenMinisEndpointsScopesLoopbackAndPkce() {
        val config = AnthropicOAuthContract.providerConfig("claude-profile")

        assertEquals(AnthropicOAuthContract.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
        assertEquals(AnthropicOAuthContract.TOKEN_ENDPOINT, config.tokenEndpoint)
        assertEquals(AnthropicOAuthContract.PUBLIC_CLIENT_ID, config.clientId)
        assertEquals(AnthropicOAuthContract.SCOPES, config.scopes)
        assertEquals("http://localhost:54545/callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals(OAuthPkceMode.BASE64URL_96_BYTES, config.pkceMode)
        assertEquals(OAuthTokenRequestEncoding.JSON, config.tokenRequestEncoding)

        val pkce = OAuthPkce.create(config.pkceMode)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        assertEquals(128, pkce.verifier.length)
        assertEquals(expectedChallenge, pkce.challenge)
    }

    @Test
    fun anthropicTokenExchangeEchoesStateInJsonButRefreshDoesNot() {
        val adapter = AnthropicOAuthContract.providerConfig("claude-profile").tokenRequestAdapter
        val exchange = adapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                parameters = mapOf("grant_type" to "authorization_code"),
                state = "expected-state",
            ),
        )
        val refresh = adapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.REFRESH_TOKEN,
                parameters = mapOf("grant_type" to "refresh_token"),
            ),
        )

        assertEquals("expected-state", exchange["state"])
        assertFalse("state" in refresh)
    }
}
