package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiOAuthContractTest {
    @Test
    fun geminiContractPinsOfficialEndpointsScopesAndLoopback() {
        val config = GeminiOAuthContract.providerConfig(
            providerId = "gemini-profile",
            clientId = "app-owned-public-client",
        )

        assertEquals(GeminiOAuthContract.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
        assertEquals(GeminiOAuthContract.TOKEN_ENDPOINT, config.tokenEndpoint)
        assertEquals(GeminiOAuthContract.SCOPES, config.scopes)
        assertEquals("http://localhost:8085/oauth2callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals("offline", config.extraAuthorizationParams["access_type"])
        assertEquals("consent", config.extraAuthorizationParams["prompt"])
        assertEquals("true", config.extraAuthorizationParams["include_granted_scopes"])
        assertEquals(OAuthTokenRequestEncoding.FORM_URLENCODED, config.tokenRequestEncoding)
        assertEquals(OAuthProviderConfig.ClientAuthMethod.NONE, config.clientAuthMethod)
    }
}
