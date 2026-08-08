package dev.alpine.chat.provider.android.model

/** User intent returned by the profile editor after a successful local save. */
enum class ProviderSaveAction(val requestLogin: Boolean) {
    SAVE_AND_LOGIN(requestLogin = true),
    SAVE_FOR_LATER(requestLogin = false),
}
