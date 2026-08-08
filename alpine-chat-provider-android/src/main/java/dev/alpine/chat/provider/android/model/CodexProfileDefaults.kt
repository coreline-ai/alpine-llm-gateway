package dev.alpine.chat.provider.android.model

/**
 * Legacy binary compatibility surface. Product UI and chat selection never read this catalog.
 * An application owner must enter an approved Responses model in its profile instead.
 */
@Deprecated("No OpenAI Responses model catalog is bundled without Provider approval")
object CodexProfileDefaults {
    const val DEFAULT_MODEL = ""
    val MODELS: List<String> = emptyList()
}
