package ai.coreline.mobile_agent_auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class OAuthConfigurationTest {
    @Test
    fun validNativePublicClientConfigurationIsAccepted() {
        val configuration = OAuthConfiguration.from(validArguments())

        assertEquals("https://auth.mobileagent.example", configuration.issuer)
        assertEquals("mobile-agent-native", configuration.clientId)
        assertEquals(listOf("openid", "profile", "offline_access"), configuration.scopes)
    }

    @Test
    fun nonHttpsIssuerIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            OAuthConfiguration.from(validArguments() + ("issuer" to "http://auth.example"))
        }
    }

    @Test
    fun foreignCustomRedirectSchemeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            OAuthConfiguration.from(
                validArguments() + ("redirectUri" to "another.app:/oauth/callback"),
            )
        }
    }

    @Test
    fun unregisteredMobileAgentCallbackPathIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            OAuthConfiguration.from(
                validArguments() + ("redirectUri" to "ai.coreline.mobileagent:/unexpected"),
            )
        }
    }

    @Test
    fun confidentialClientMaterialIsRejectedAsAnUnknownField() {
        assertFailsWith<IllegalArgumentException> {
            OAuthConfiguration.from(validArguments() + ("clientSecret" to "must-not-cross"))
        }
    }

    @Test
    fun bffRevocationUrlMustRemainACleanHttpsOrigin() {
        assertTrue(
            NativeLlmTransportController.isValidBaseUrl(
                "https://bff.mobileagent.example",
            ),
        )
        assertFalse(
            NativeLlmTransportController.isValidBaseUrl(
                "http://bff.mobileagent.example",
            ),
        )
        assertFalse(
            NativeLlmTransportController.isValidBaseUrl(
                "https://bff.mobileagent.example?next=attacker",
            ),
        )
    }

    private fun validArguments(): Map<String, Any> = mapOf(
        "issuer" to "https://auth.mobileagent.example",
        "clientId" to "mobile-agent-native",
        "redirectUri" to "ai.coreline.mobileagent:/oauth/callback",
        "scopes" to listOf("openid", "profile", "offline_access"),
        "audience" to "mobile-agent-bff",
    )
}
