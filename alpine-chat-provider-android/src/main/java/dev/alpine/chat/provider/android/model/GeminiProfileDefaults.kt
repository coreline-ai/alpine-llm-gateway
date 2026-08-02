package dev.alpine.chat.provider.android.model

/** Current text/chat-capable Gemini API models verified against Google documentation. */
object GeminiProfileDefaults {
    const val DEFAULT_MODEL = "gemini-3.6-flash"

    val MODELS = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
    )
}
