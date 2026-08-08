package dev.alpine.chat.provider.android.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.alpine.chat.provider.android.R
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.chat.provider.android.model.ProviderDraftRestoration
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderSaveAction
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.ui.ProviderEditScreen
import dev.alpine.chat.feature.ui.theme.AlpineProductTheme

/** Compose host for one OAuth-enabled LLM profile. */
class ProviderEditActivity : ComponentActivity() {
    private lateinit var store: ProviderProfileStore
    private lateinit var initialProfile: ProviderProfile
    private var isEditing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
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
        initialProfile = stored ?: ProviderDraftRestoration.restoreIdentity(
            freshDraft = ProviderProfile.draft(type, store.nextLabel(type)),
            savedId = savedInstanceState?.getString(STATE_DRAFT_PROFILE_ID),
            savedCreatedAtMs = savedInstanceState
                ?.takeIf { it.containsKey(STATE_DRAFT_CREATED_AT_MS) }
                ?.getLong(STATE_DRAFT_CREATED_AT_MS),
        )

        setContent {
            AlpineProductTheme {
                ProviderEditScreen(
                    initialProfile = initialProfile,
                    isEditing = isEditing,
                    onBack = ::finish,
                    onSave = ::saveProfile,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!isEditing) {
            outState.putString(STATE_DRAFT_PROFILE_ID, initialProfile.id)
            outState.putLong(STATE_DRAFT_CREATED_AT_MS, initialProfile.createdAtMs)
        }
        super.onSaveInstanceState(outState)
    }

    private fun saveProfile(
        profile: ProviderProfile,
        action: ProviderSaveAction,
    ): Map<ProviderProfile.Field, String> {
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
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_RESULT_PROFILE_ID, profile.id)
                        .putExtra(EXTRA_RESULT_REQUEST_LOGIN, action.requestLogin),
                )
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
        const val EXTRA_RESULT_PROFILE_ID = "saved_provider_profile_id"
        const val EXTRA_RESULT_REQUEST_LOGIN = "request_provider_login"
        private const val STATE_DRAFT_PROFILE_ID = "provider_draft_profile_id"
        private const val STATE_DRAFT_CREATED_AT_MS = "provider_draft_created_at_ms"
    }
}
