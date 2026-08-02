package dev.alpine.chat.provider.android.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.alpine.chat.provider.android.R
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.ui.ProviderEditScreen
import dev.alpine.chat.feature.ui.theme.AlpineChatTheme

/** Compose host for one OAuth-enabled LLM profile. */
class ProviderEditActivity : ComponentActivity() {
    private lateinit var store: ProviderProfileStore
    private lateinit var initialProfile: ProviderProfile
    private var isEditing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProviderProfileStore(this)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val stored = profileId?.let(store::find)
        if (profileId != null && stored == null) {
            failToLoadProfile()
            return
        }
        val type = stored?.type ?: runCatching {
            ProviderType.fromWireName(
                requireNotNull(intent.getStringExtra(EXTRA_PROVIDER_TYPE)),
            )
        }.getOrElse {
            failToLoadProfile()
            return
        }
        isEditing = stored != null
        initialProfile = stored ?: ProviderProfile.draft(type, store.nextLabel(type))

        setContent {
            AlpineChatTheme {
                ProviderEditScreen(
                    initialProfile = initialProfile,
                    isEditing = isEditing,
                    onBack = ::finish,
                    onSave = ::saveProfile,
                )
            }
        }
    }

    private fun saveProfile(profile: ProviderProfile): Map<ProviderProfile.Field, String> {
        val errors = profile.validationErrors()
        if (errors.isNotEmpty()) return errors

        val mustReauthenticate =
            isEditing && profile.requiresReauthenticationComparedTo(initialProfile)
        return runCatching {
            store.upsert(profile)
            if (mustReauthenticate) OAuthTokenStore(this).delete(profile.id)
        }.fold(
            onSuccess = {
                Toast.makeText(this, R.string.provider_saved, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
                emptyMap()
            },
            onFailure = {
                mapOf(ProviderProfile.Field.LABEL to getString(R.string.profile_load_failed))
            },
        )
    }

    private fun failToLoadProfile() {
        Toast.makeText(this, R.string.profile_load_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "provider_profile_id"
        const val EXTRA_PROVIDER_TYPE = "provider_type"
    }
}
