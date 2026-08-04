package dev.alpine.chat.provider.android.model

/** Reference-only model labels used by the standalone compatibility demo. */
object AnthropicProfileDefaults {
    const val DEFAULT_MODEL = "claude-haiku-4-5"

    val MODELS = listOf(
        "claude-haiku-4-5",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-opus-4-6",
        "claude-opus-4-8",
        "claude-fable-5",
    )
}
