package dev.alpine.llm.demo.model

/** Codex CLI compatibility defaults used by the standalone demo app. */
object CodexProfileDefaults {
    const val PUBLIC_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val DEFAULT_MODEL = "gpt-5.6-luna"

    val MODELS = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.5",
        "gpt-5.4",
        "gpt-5.4-mini",
        "gpt-5.3-codex-spark",
    )
}
