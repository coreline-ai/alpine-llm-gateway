package dev.alpine.llm.demo.model

/** Claude model catalog and fast default mirrored from the OpenMinis reference. */
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
