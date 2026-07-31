package dev.alpine.llm.demo.support

import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType

fun testProfile(
    id: String,
    label: String,
    type: ProviderType,
    model: String,
    createdAtMs: Long,
): ProviderProfile = ProviderProfile(
    id = id,
    label = label,
    type = type,
    authorizationEndpoint = "https://identity.example.test/oauth/authorize",
    tokenEndpoint = "https://identity.example.test/oauth/token",
    inferenceEndpoint = type.inferenceEndpointPlaceholder,
    clientId = "android-public-client",
    scopes = type.defaultScopes.split(" "),
    model = model,
    callbackPort = ProviderProfile.DEFAULT_CALLBACK_PORT,
    anthropicBeta = if (type == ProviderType.ANTHROPIC) "oauth-test" else null,
    googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
    createdAtMs = createdAtMs,
)
