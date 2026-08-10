package dev.alpine.chat.provider.android.model

import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.CodexOAuthCompatibilityConfig
import dev.alpine.llm.CodexOAuthCompatibilityRegistry
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.XaiOAuthCompatibilityConfig
import dev.alpine.llm.XaiOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthContract
import dev.alpine.chat.provider.android.session.toChatBackendDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ProviderProfileTest {
    @After
    fun clearCompatibility() {
        CodexOAuthCompatibilityRegistry.clear()
        XaiOAuthCompatibilityRegistry.clear()
    }

    @Test
    fun `all supported provider profiles round trip without credentials`() {
        ProviderType.entries.forEach { type ->
            val profile = validProfile(type)
            val raw = profile.toJson().toString()
            assertEquals(profile, ProviderProfile.fromJson(profile.toJson()))
            assertFalse(raw.contains("access_token"))
            assertFalse(raw.contains("refresh_token"))
            assertFalse(raw.contains("client_secret"))
        }
    }

    @Test
    fun `gemini draft pins official protocol and requires client id`() {
        val draft = ProviderProfile.draft(ProviderType.GEMINI, "Gemini")

        assertEquals(GeminiOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(GeminiOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT, draft.inferenceEndpoint)
        assertEquals(GeminiOAuthContract.SCOPES, draft.scopes)
        assertEquals(GeminiOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertEquals(GeminiProfileDefaults.DEFAULT_MODEL, draft.model)
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
    }

    @Test
    fun `gemini rejects modified official protocol contract`() {
        val invalid = validProfile(ProviderType.GEMINI).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://relay.example.test/models/{model}:generateContent",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `codex draft pins oauth protocol and requires app registration and model`() {
        val draft = ProviderProfile.draft(ProviderType.CODEX, "Codex")

        assertEquals(CodexOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(CodexOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(CodexOAuthContract.SCOPES, draft.scopes)
        assertEquals(CodexOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.MODEL))
    }

    @Test
    fun `codex rejects modified oauth protocol contract`() {
        val invalid = validProfile(ProviderType.CODEX).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `xai draft pins oauth protocol and requires app registration and model`() {
        val draft = ProviderProfile.draft(ProviderType.XAI, "xAI")

        assertEquals(XaiOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(XaiOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT, draft.inferenceEndpoint)
        assertEquals(XaiOAuthContract.SCOPES, draft.scopes)
        assertEquals(XaiOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.MODEL))
    }

    @Test
    fun `xai rejects modified oauth protocol contract`() {
        val invalid = validProfile(ProviderType.XAI).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://api.example.test/v1/chat/completions",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `approved debug compatibility seeds codex registration endpoint and models`() {
        val compatibility = CodexOAuthCompatibilityConfig(
            sourceRevision = "reference@revision",
            clientId = "approved-debug-public-client",
            responsesEndpoint = "https://approved.example.test/codex/responses",
            defaultModel = "gpt-current",
            modelOptions = listOf("gpt-current", "gpt-fast"),
            extraAuthorizationParams = mapOf("approved_flow" to "true"),
            requestHeaders = mapOf("Originator" to "approved-debug"),
            accountIdHeader = "Provider-Account-Id",
        )
        CodexOAuthCompatibilityRegistry.installApprovedDebug(compatibility)

        val draft = ProviderProfile.draft(ProviderType.CODEX, "Codex")

        assertEquals(compatibility.clientId, draft.clientId)
        assertEquals(compatibility.responsesEndpoint, draft.inferenceEndpoint)
        assertEquals(compatibility.defaultModel, draft.model)
        assertTrue(draft.validationErrors().isEmpty())
        assertEquals(compatibility.modelOptions, draft.toChatBackendDescriptor().modelOptions)
        assertTrue(
            draft.copy(model = "unknown-model")
                .validationErrors()
                .containsKey(ProviderProfile.Field.MODEL),
        )
    }

    @Test
    fun `approved debug compatibility seeds xai registration endpoint and models`() {
        val compatibility = XaiOAuthCompatibilityConfig(
            sourceRevision = "reference@revision",
            clientId = "approved-debug-public-client",
            chatCompletionsEndpoint = XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT,
            scopes = XaiOAuthContract.SCOPES + "debug.compatibility",
            defaultModel = "grok-current",
            modelOptions = listOf("grok-current", "grok-fast"),
            extraAuthorizationParams = mapOf("approved_flow" to "true"),
        )
        XaiOAuthCompatibilityRegistry.installApprovedDebug(compatibility)

        val draft = ProviderProfile.draft(ProviderType.XAI, "xAI")

        assertEquals(compatibility.clientId, draft.clientId)
        assertEquals(compatibility.chatCompletionsEndpoint, draft.inferenceEndpoint)
        assertEquals(compatibility.scopes, draft.scopes)
        assertEquals(compatibility.defaultModel, draft.model)
        assertTrue(draft.validationErrors().isEmpty())
        assertEquals(compatibility.modelOptions, draft.toChatBackendDescriptor().modelOptions)
        assertTrue(
            draft.copy(model = "unknown-model")
                .validationErrors()
                .containsKey(ProviderProfile.Field.MODEL),
        )
    }

    @Test
    fun `configurable provider draft fails closed until host owned oauth data is entered`() {
        ProviderType.entries
            .filter {
                it != ProviderType.GEMINI && it != ProviderType.CODEX && it != ProviderType.XAI
            }
            .forEach { type ->
            val draft = ProviderProfile.draft(type, type.displayName)
            val errors = draft.validationErrors()

            assertTrue(draft.authorizationEndpoint.isBlank())
            assertTrue(draft.tokenEndpoint.isBlank())
            assertTrue(draft.scopes.isEmpty())
            assertTrue(draft.model.isBlank())
            assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
            assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
            assertTrue(errors.containsKey(ProviderProfile.Field.CLIENT_ID))
            assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
            assertTrue(errors.containsKey(ProviderProfile.Field.MODEL))
        }
    }

    @Test
    fun `non gemini profiles accept explicit host owned configuration`() {
        ProviderType.entries.filter { it != ProviderType.GEMINI }.forEach { type ->
            assertTrue(validProfile(type).validationErrors().isEmpty())
        }
    }

    @Test
    fun `legacy anthropic beta json is ignored on restore`() {
        val restored = ProviderProfile.fromJson(
            validProfile(ProviderType.ANTHROPIC).toJson().put("anthropic_beta", "legacy-value"),
        )
        assertFalse(restored.toJson().has("anthropic_beta"))
    }

    @Test
    fun `profile requires https public client scopes and model`() {
        val invalid = validProfile(ProviderType.OPENAI_COMPATIBLE).copy(
            authorizationEndpoint = "http://provider.example/auth",
            tokenEndpoint = "",
            clientId = "",
            scopes = emptyList(),
            model = "",
            callbackPort = 70000,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.MODEL))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `validation rejects credentials embedded in endpoint and malformed urls`() {
        val invalid = validProfile(ProviderType.OPENAI_COMPATIBLE).copy(
            authorizationEndpoint = "https://user:password@identity.example.test/oauth/authorize",
            tokenEndpoint = "not-a-url",
            inferenceEndpoint = "https://",
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
    }

    @Test
    fun `validation rejects blank and boundary callback ports`() {
        val blank = validProfile(ProviderType.OPENAI_COMPATIBLE).copy(label = " ", scopes = listOf(" "))
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.LABEL))
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 0)
            .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT))
        assertTrue(validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 65534)
            .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT))
        assertFalse(validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 1)
            .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT))
        assertFalse(validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 65533)
            .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `malformed profile json fails closed`() {
        assertThrows(Exception::class.java) {
            ProviderProfile.fromJson(JSONObject("{\"id\":\"missing-fields\"}"))
        }
        assertThrows(Exception::class.java) {
            ProviderProfile.fromJson(validProfile(ProviderType.GEMINI).toJson().put("type", "unsupported"))
        }
    }

    @Test
    fun `oauth identity edits require login but model and endpoint edits do not`() {
        val original = validProfile(ProviderType.OPENAI_COMPATIBLE)

        assertTrue(original.copy(clientId = "another-public-client").requiresReauthenticationComparedTo(original))
        assertTrue(original.copy(scopes = original.scopes + "models.read").requiresReauthenticationComparedTo(original))
        assertTrue(original.copy(authorizationEndpoint = "https://identity-2.example.test/oauth/authorize")
            .requiresReauthenticationComparedTo(original))
        assertTrue(original.copy(tokenEndpoint = "https://identity-2.example.test/oauth/token")
            .requiresReauthenticationComparedTo(original))
        assertTrue(original.copy(callbackPort = original.callbackPort + 1)
            .requiresReauthenticationComparedTo(original))
        assertFalse(original.copy(
            label = "Renamed",
            model = "another-model",
            inferenceEndpoint = "https://api.example.test/v1/messages",
        ).requiresReauthenticationComparedTo(original))
    }

    private fun validProfile(type: ProviderType): ProviderProfile {
        val draft = ProviderProfile.draft(type, type.displayName)
        return when (type) {
            ProviderType.GEMINI -> draft.copy(
                id = "profile-${type.wireName}",
                clientId = "host-owned-public-client",
                googleProjectId = "test-project",
                createdAtMs = 1234L,
            )
            ProviderType.CODEX -> draft.copy(
                id = "profile-${type.wireName}",
                inferenceEndpoint = "https://relay.example.test/v1/responses",
                clientId = "host-owned-public-client",
                model = "test-model",
                createdAtMs = 1234L,
            )
            ProviderType.XAI -> draft.copy(
                id = "profile-${type.wireName}",
                clientId = "host-owned-public-client",
                model = "test-model",
                createdAtMs = 1234L,
            )
            else -> draft.copy(
                id = "profile-${type.wireName}",
                authorizationEndpoint = "https://identity.example.test/oauth/authorize",
                tokenEndpoint = "https://identity.example.test/oauth/token",
                inferenceEndpoint = "https://api.example.test/v1/chat/completions",
                clientId = "host-owned-public-client",
                scopes = listOf("openid", "profile", "offline_access"),
                model = "test-model",
                createdAtMs = 1234L,
            )
        }
    }
}
