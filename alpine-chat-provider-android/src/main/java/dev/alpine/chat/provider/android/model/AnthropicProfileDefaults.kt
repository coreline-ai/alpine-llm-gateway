package dev.alpine.chat.provider.android.model

/**
 * Legacy binary compatibility surface. Product UI and chat selection never read this catalog.
 * An application owner must enter a Provider-approved model in its profile instead.
 */
@Deprecated("No Anthropic model catalog is bundled without Provider approval")
object AnthropicProfileDefaults {
    const val DEFAULT_MODEL = ""
    val MODELS: List<String> = emptyList()
}
