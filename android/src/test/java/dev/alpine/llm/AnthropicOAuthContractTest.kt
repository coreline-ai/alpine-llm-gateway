package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnthropicOAuthContractTest {
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
        assertFalse(AnthropicOAuthContract::class.java.declaredMethods.any { it.name == "providerConfig" })
    }
}
