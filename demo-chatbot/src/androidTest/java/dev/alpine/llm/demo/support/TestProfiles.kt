package dev.alpine.llm.demo.support

import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType

fun testProfile(
    id: String,
    label: String,
    type: ProviderType,
    model: String,
    createdAtMs: Long,
): ProviderProfile = ProviderProfile.draft(type, label).let { draft ->
    draft.copy(
        id = id,
        authorizationEndpoint = if (
            type == ProviderType.ANTHROPIC || type == ProviderType.GEMINI ||
                type == ProviderType.CODEX || type == ProviderType.XAI
        ) {
            draft.authorizationEndpoint
        } else {
            "https://identity.example.test/oauth/authorize"
        },
        tokenEndpoint = if (
            type == ProviderType.ANTHROPIC || type == ProviderType.GEMINI ||
            type == ProviderType.CODEX || type == ProviderType.XAI
        ) {
            draft.tokenEndpoint
        } else {
            "https://identity.example.test/oauth/token"
        },
        clientId = if (
            type == ProviderType.ANTHROPIC ||
                type == ProviderType.CODEX || type == ProviderType.XAI
        ) {
            draft.clientId
        } else {
            "android-public-client"
        },
        model = model,
        anthropicBeta = draft.anthropicBeta,
        googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
        createdAtMs = createdAtMs,
    )
}
