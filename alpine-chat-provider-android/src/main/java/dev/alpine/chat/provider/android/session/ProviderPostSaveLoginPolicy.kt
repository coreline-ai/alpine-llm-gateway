package dev.alpine.chat.provider.android.session

/** Selects a saved profile for authorization without toggling an already connected session. */
object ProviderPostSaveLoginPolicy {
    fun select(
        connections: List<ProviderConnection>,
        profileId: String,
        requestLogin: Boolean,
    ): ProviderConnection? {
        if (!requestLogin) return null
        return connections
            .firstOrNull { it.profile.id == profileId }
            ?.takeIf { it.state != ProviderConnectionState.AUTHENTICATED }
    }
}
