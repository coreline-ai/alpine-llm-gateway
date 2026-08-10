package dev.alpine.integrated

import dev.alpine.llm.CodexOAuthCompatibilityConfig
import dev.alpine.llm.CodexOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthCompatibilityConfig
import dev.alpine.llm.XaiOAuthCompatibilityRegistry
import dev.alpine.llm.XaiOAuthContract

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
        XaiOAuthCompatibilityRegistry.installApprovedDebug(
            XaiOAuthCompatibilityConfig(
                sourceRevision = "OpenMinis@9cf3a855fecd27bb5735b84cacbd56852a3ab8dd",
                clientId = "b1a00492-073a-47ea-816f-4c329264a828",
                chatCompletionsEndpoint = XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT,
                scopes = listOf(
                    "openid",
                    "profile",
                    "email",
                    "offline_access",
                    "grok-cli:access",
                    "api:access",
                ),
                defaultModel = "grok-4.5",
                modelOptions = listOf(
                    "grok-4.5",
                    "grok-4.3",
                    "grok-4.20-0309-reasoning",
                    "grok-4.20-0309-non-reasoning",
                    "grok-4.20-multi-agent-0309",
                    "grok-build-0.1",
                    "grok-3-mini",
                    "grok-3-mini-fast",
                    "grok-composer-2.5-fast",
                    "grok-4-fast",
                    "grok-4-fast-non-reasoning",
                    "grok-code-fast-1",
                ),
                extraAuthorizationParams = mapOf(
                    "plan" to "generic",
                    "referrer" to "minis",
                ),
            ),
        )
        super.onCreate()
    }
}
