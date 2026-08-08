package dev.alpine.integrated

import android.content.Context

/** Stores only the version of the first-run mode guide explicitly completed by the user. */
internal class IntegratedModeGuideStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShowGuide(): Boolean =
        preferences.getInt(KEY_COMPLETED_VERSION, 0) < CURRENT_GUIDE_VERSION

    fun markCompleted(): Boolean = preferences.edit()
        .putInt(KEY_COMPLETED_VERSION, CURRENT_GUIDE_VERSION)
        .commit()

    fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        internal const val FILE_NAME = "integrated_mode_guide"
        internal const val CURRENT_GUIDE_VERSION = 1
        internal const val KEY_COMPLETED_VERSION = "completed_version"
    }
}
