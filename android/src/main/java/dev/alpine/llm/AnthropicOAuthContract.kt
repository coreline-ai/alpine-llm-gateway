package dev.alpine.llm

/**
 * Anthropic endpoint placeholders for an application-owner-approved OAuth registration.
 *
 * No consumer authorization endpoint, third-party public client registration, scope or beta
 * header is bundled. A host must supply and verify its own approved OAuth contract before
 * enabling direct inference. MobileAgent production traffic uses its server-side BFF path.
 */
object AnthropicOAuthContract {
    const val AUTHORIZATION_ENDPOINT_PLACEHOLDER = "https://provider.example.com/oauth/authorize"
    const val TOKEN_ENDPOINT_PLACEHOLDER = "https://provider.example.com/oauth/token"
    const val MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
}
