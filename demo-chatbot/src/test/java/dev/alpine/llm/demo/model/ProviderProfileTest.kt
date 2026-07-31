package dev.alpine.llm.demo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {
    @Test
    fun `all supported provider profiles round trip without credentials`() {
        ProviderType.entries.forEach { type ->
            val profile = validProfile(type)
            val raw = profile.toJson().toString()
            val restored = ProviderProfile.fromJson(profile.toJson())

            assertEquals(profile, restored)
            assertFalse(raw.contains("access_token"))
            assertFalse(raw.contains("refresh_token"))
            assertFalse(raw.contains("client_secret"))
        }
    }

    @Test
    fun `gemini accepts model placeholder but requires it`() {
        val valid = validProfile(ProviderType.GEMINI)
        assertTrue(valid.validationErrors().isEmpty())

        val invalid = valid.copy(
            inferenceEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/fixed:generateContent",
        )
        assertTrue(
            invalid.validationErrors().containsKey(
                ProviderProfile.Field.INFERENCE_ENDPOINT,
            ),
        )
    }

    @Test
    fun `profile requires https public client and model`() {
        val invalid = validProfile(ProviderType.ANTHROPIC).copy(
            authorizationEndpoint = "http://provider.example/auth",
            tokenEndpoint = "",
            clientId = "",
            model = "",
            callbackPort = 70000,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(errors.containsKey(ProviderProfile.Field.MODEL))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `oauth identity edits require login but model and endpoint edits do not`() {
        val original = validProfile(ProviderType.ANTHROPIC)

        assertTrue(
            original.copy(clientId = "another-public-client")
                .requiresReauthenticationComparedTo(original),
        )
        assertTrue(
            original.copy(scopes = original.scopes + "models.read")
                .requiresReauthenticationComparedTo(original),
        )
        assertFalse(
            original.copy(
                label = "Renamed",
                model = "another-model",
                inferenceEndpoint = "https://api.example.test/v1/messages",
            ).requiresReauthenticationComparedTo(original),
        )
    }

    private fun validProfile(type: ProviderType): ProviderProfile = ProviderProfile(
        id = "profile-${type.wireName}",
        label = type.displayName,
        type = type,
        authorizationEndpoint = "https://identity.example.test/oauth/authorize",
        tokenEndpoint = "https://identity.example.test/oauth/token",
        inferenceEndpoint = type.inferenceEndpointPlaceholder,
        clientId = "android-public-client",
        scopes = type.defaultScopes.split(" "),
        model = "test-model",
        callbackPort = 54545,
        anthropicBeta = if (type == ProviderType.ANTHROPIC) "oauth-test" else null,
        googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
        createdAtMs = 1234L,
    )
}
