package dev.alpine.llm.demo.model

/** xAI Grok compatibility defaults mirrored from the inspected OpenMinis Android source. */
object XaiProfileDefaults {
    const val PUBLIC_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val DEFAULT_MODEL = "grok-4.5"

    val MODELS = listOf(
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
    )
}
