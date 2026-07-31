package dev.alpine.llm.sample

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.alpine.llm.AnthropicMessagesOAuthAdapter
import dev.alpine.llm.GeminiGenerateContentOAuthAdapter
import dev.alpine.llm.OAuthHttpLlmBridge
import dev.alpine.llm.OAuthLlmSession
import dev.alpine.llm.OAuthManager
import dev.alpine.llm.OAuthProviderConfig
import dev.alpine.llm.ProviderRetryPolicy
import dev.alpine.llm.ResilientOAuthHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Credential-free integration sample. All Provider registration values are
 * entered at runtime and are intentionally absent from source and BuildConfig.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var provider: Spinner
    private lateinit var authorizationEndpoint: EditText
    private lateinit var tokenEndpoint: EditText
    private lateinit var completionEndpoint: EditText
    private lateinit var clientId: EditText
    private lateinit var scopes: EditText
    private lateinit var model: EditText
    private lateinit var prompt: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private var oauth: OAuthManager? = null
    private var session: OAuthLlmSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply { addView(content()) })
    }

    private fun content(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)

        addView(TextView(context).apply {
            setText(R.string.screen_title)
            textSize = 22f
        })
        provider = Spinner(context).also {
            it.adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.providers).toList(),
            )
            addView(it)
        }
        authorizationEndpoint = field(
            R.string.authorization_endpoint_hint,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        tokenEndpoint = field(
            R.string.token_endpoint_hint,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        completionEndpoint = field(
            R.string.completion_endpoint_hint,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        clientId = field(R.string.client_id_hint)
        scopes = field(R.string.scopes_hint).apply {
            setText(R.string.default_scopes)
        }
        model = field(R.string.model_hint)
        prompt = field(
            R.string.prompt_hint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )

        addView(Button(context).apply {
            setText(R.string.oauth_login)
            setOnClickListener { login() }
        })
        addView(Button(context).apply {
            setText(R.string.stream_completion)
            setOnClickListener { streamCompletion() }
        })
        addView(Button(context).apply {
            setText(R.string.logout)
            setOnClickListener {
                oauth?.logout()
                session = null
                status.setText(R.string.logged_out)
            }
        })
        status = TextView(context).also { addView(it) }
        output = TextView(context).also {
            it.setTextIsSelectable(true)
            addView(it)
        }
    }

    private fun LinearLayout.field(
        hintText: Int,
        type: Int = InputType.TYPE_CLASS_TEXT,
    ): EditText = EditText(context).also {
        it.setHint(hintText)
        it.inputType = type
        addView(it)
    }

    private fun login() {
        scope.launch {
            status.setText(R.string.opening_authorization)
            runCatching {
                val manager = OAuthManager(
                    context = this@MainActivity,
                    config = OAuthProviderConfig(
                        providerId = if (provider.selectedItemPosition == 0) {
                            "sample-claude"
                        } else {
                            "sample-gemini"
                        },
                        authorizationEndpoint = required(authorizationEndpoint),
                        tokenEndpoint = required(tokenEndpoint),
                        clientId = required(clientId),
                        scopes = scopes.text.toString()
                            .split(Regex("\\s+"))
                            .filter(String::isNotBlank),
                        callbackPort = 54545,
                    ),
                )
                manager.authorize(this@MainActivity)
                oauth = manager
                session = OAuthLlmSession(manager, providerBridge())
            }.onSuccess {
                status.setText(R.string.login_complete)
            }.onFailure { error ->
                status.text = getString(
                    R.string.login_failed,
                    error::class.simpleName.orEmpty(),
                )
            }
        }
    }

    private fun streamCompletion() {
        val activeSession = session
        if (activeSession == null) {
            status.setText(R.string.login_first)
            return
        }
        output.text = ""
        scope.launch {
            status.setText(R.string.streaming)
            runCatching {
                val request = JSONObject()
                    .put("model", required(model))
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", required(prompt)),
                        ),
                    )
                    .put("max_tokens", 1024)
                    .put("stream", true)
                val result = activeSession.stream(request.toString())
                check(result.statusCode in 200..299)
                result.events.collect { event ->
                    val json = JSONObject(event.dataJson)
                    output.append(json.optString("text"))
                }
            }.onSuccess {
                status.setText(R.string.complete)
            }.onFailure { error ->
                status.text = getString(
                    R.string.request_failed,
                    error::class.simpleName.orEmpty(),
                )
            }
        }
    }

    private fun providerBridge(): OAuthHttpLlmBridge {
        val adapter = if (provider.selectedItemPosition == 0) {
            AnthropicMessagesOAuthAdapter(required(completionEndpoint))
        } else {
            GeminiGenerateContentOAuthAdapter(required(completionEndpoint))
        }
        val transport = ResilientOAuthHttpTransport(
            retryPolicy = ProviderRetryPolicy(maxAttempts = 3),
        )
        return OAuthHttpLlmBridge(
            adapter = adapter,
            streamingTransport = transport,
            transport = transport,
        )
    }

    private fun required(field: EditText): String =
        field.text.toString().trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Required sample field is empty")

    override fun onDestroy() {
        oauth?.cancelAuthorization()
        scope.cancel()
        super.onDestroy()
    }
}
