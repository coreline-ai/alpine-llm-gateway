package dev.alpine.integrated

import dev.alpine.llm.CodexOAuthCompatibilityConfig
import dev.alpine.llm.CodexOAuthCompatibilityRegistry

/** User-approved OpenMinis compatibility boundary for the side-by-side debug package only. */
class DebugIntegratedApplication : IntegratedApplication() {
    override fun onCreate() {
        CodexOAuthCompatibilityRegistry.installApprovedDebug(
            CodexOAuthCompatibilityConfig(
                sourceRevision = "OpenMinis@9cf3a855fecd27bb5735b84cacbd56852a3ab8dd",
                clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
                responsesEndpoint = "https://chatgpt.com/backend-api/codex/responses",
                defaultModel = "gpt-5.6-sol",
                modelOptions = listOf(
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna",
                    "gpt-5.5",
                    "gpt-5.4",
                    "gpt-5.3-codex",
                    "gpt-5.3-codex-spark",
                    "gpt-5-codex-mini",
                    "gpt-5.2",
                    "gpt-5.3",
                    "gpt-5",
                ),
                extraAuthorizationParams = mapOf(
                    "codex_cli_simplified_flow" to "true",
                    "originator" to "codex_cli_rs",
                    "id_token_add_organizations" to "true",
                ),
                requestHeaders = mapOf(
                    "Version" to "0.144.1",
                    "Openai-Beta" to "responses=experimental",
                    "User-Agent" to "codex_cli_rs/0.144.1 (Android; arm64)",
                    "Originator" to "codex_cli_rs",
                ),
                accountIdHeader = "Chatgpt-Account-Id",
                includeEncryptedReasoning = true,
                reasoningEffort = "low",
            ),
        )
        super.onCreate()
    }
}
