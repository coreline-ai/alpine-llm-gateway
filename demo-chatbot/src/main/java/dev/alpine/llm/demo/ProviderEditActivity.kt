package dev.alpine.llm.demo

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.llm.demo.data.ProviderProfileStore
import dev.alpine.llm.demo.databinding.ActivityProviderEditBinding
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType

class ProviderEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProviderEditBinding
    private lateinit var store: ProviderProfileStore
    private lateinit var initialProfile: ProviderProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProviderProfileStore(this)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val stored = profileId?.let(store::find)
        if (profileId != null && stored == null) {
            Toast.makeText(this, R.string.profile_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val type = stored?.type ?: runCatching {
            ProviderType.fromWireName(
                requireNotNull(intent.getStringExtra(EXTRA_PROVIDER_TYPE)),
            )
        }.getOrElse {
            Toast.makeText(this, R.string.profile_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        initialProfile = stored ?: ProviderProfile.draft(type, store.nextLabel(type))

        bindProfile(initialProfile)
        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { saveProfile() }
    }

    private fun bindProfile(profile: ProviderProfile) = with(binding) {
        titleText.setText(
            if (intent.hasExtra(EXTRA_PROFILE_ID)) {
                R.string.edit_provider
            } else {
                R.string.add_provider
            },
        )
        providerTypeText.text = profile.type.displayName
        providerDescriptionText.text = profile.type.description
        labelInput.setText(profile.label)
        authorizationEndpointInput.setText(profile.authorizationEndpoint)
        tokenEndpointInput.setText(profile.tokenEndpoint)
        inferenceEndpointInput.hint = profile.type.inferenceEndpointPlaceholder
        inferenceEndpointInput.setText(profile.inferenceEndpoint)
        clientIdInput.setText(profile.clientId)
        scopesInput.setText(profile.scopes.joinToString(" "))
        modelInput.setText(profile.model)
        callbackPortInput.setText(profile.callbackPort.toString())
        anthropicBetaInput.setText(profile.anthropicBeta.orEmpty())
        googleProjectInput.setText(profile.googleProjectId.orEmpty())
        anthropicBetaInput.visibility =
            if (profile.type == ProviderType.ANTHROPIC) View.VISIBLE else View.GONE
        googleProjectInput.visibility =
            if (profile.type == ProviderType.GEMINI) View.VISIBLE else View.GONE
    }

    private fun saveProfile() {
        clearErrors()
        val profile = initialProfile.copy(
            label = binding.labelInput.value(),
            authorizationEndpoint = binding.authorizationEndpointInput.value(),
            tokenEndpoint = binding.tokenEndpointInput.value(),
            inferenceEndpoint = binding.inferenceEndpointInput.value(),
            clientId = binding.clientIdInput.value(),
            scopes = binding.scopesInput.value()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank),
            model = binding.modelInput.value(),
            callbackPort = binding.callbackPortInput.value().toIntOrNull() ?: 0,
            anthropicBeta = binding.anthropicBetaInput.value().ifBlank { null },
            googleProjectId = binding.googleProjectInput.value().ifBlank { null },
        )
        val errors = profile.validationErrors()
        if (errors.isNotEmpty()) {
            errors.forEach { (field, message) -> inputFor(field).error = message }
            binding.validationText.setText(R.string.invalid_profile)
            binding.validationText.visibility = View.VISIBLE
            inputFor(errors.keys.first()).requestFocus()
            return
        }

        val mustReauthenticate =
            intent.hasExtra(EXTRA_PROFILE_ID) &&
                profile.requiresReauthenticationComparedTo(initialProfile)
        runCatching {
            store.upsert(profile)
            if (mustReauthenticate) {
                OAuthTokenStore(this).delete(profile.id)
            }
        }
            .onSuccess {
                Toast.makeText(this, R.string.provider_saved, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .onFailure {
                binding.validationText.text = getString(R.string.profile_load_failed)
                binding.validationText.visibility = View.VISIBLE
            }
    }

    private fun inputFor(field: ProviderProfile.Field): EditText = with(binding) {
        when (field) {
            ProviderProfile.Field.LABEL -> labelInput
            ProviderProfile.Field.AUTHORIZATION_ENDPOINT -> authorizationEndpointInput
            ProviderProfile.Field.TOKEN_ENDPOINT -> tokenEndpointInput
            ProviderProfile.Field.INFERENCE_ENDPOINT -> inferenceEndpointInput
            ProviderProfile.Field.CLIENT_ID -> clientIdInput
            ProviderProfile.Field.SCOPES -> scopesInput
            ProviderProfile.Field.MODEL -> modelInput
            ProviderProfile.Field.CALLBACK_PORT -> callbackPortInput
        }
    }

    private fun clearErrors() {
        ProviderProfile.Field.entries.forEach { inputFor(it).error = null }
        binding.validationText.visibility = View.GONE
    }

    private fun EditText.value(): String = text.toString().trim()

    companion object {
        const val EXTRA_PROFILE_ID = "provider_profile_id"
        const val EXTRA_PROVIDER_TYPE = "provider_type"
    }
}
