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
        authorizationEndpoint = if (type == ProviderType.GEMINI) {
            draft.authorizationEndpoint
        } else {
            "https://identity.example.test/oauth/authorize"
        },
        tokenEndpoint = if (type == ProviderType.GEMINI) {
            draft.tokenEndpoint
        } else {
            "https://identity.example.test/oauth/token"
        },
        // Credential-free instrumentation owns this synthetic public client identifier.
        // Production defaults intentionally remain blank and require an app-owned registration.
        clientId = "android-public-client",
        scopes = if (type == ProviderType.GEMINI) {
            draft.scopes
        } else {
            listOf("openid", "profile", "offline_access")
        },
        model = model,
        googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
        createdAtMs = createdAtMs,
    )
}
