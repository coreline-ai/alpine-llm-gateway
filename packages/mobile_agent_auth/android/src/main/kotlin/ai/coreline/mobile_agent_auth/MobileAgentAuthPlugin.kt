package ai.coreline.mobile_agent_auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.PluginRegistry
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject

class MobileAgentAuthPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var transportChannel: MethodChannel
    private lateinit var transportEventChannel: io.flutter.plugin.common.EventChannel
    private lateinit var authEventChannel: EventChannel
    private lateinit var conversationChannel: MethodChannel
    private lateinit var applicationContext: Context
    private lateinit var stateStore: SecureAuthStateStore
    private lateinit var conversationStore: SecureConversationStore
    private var activity: Activity? = null
    private var authorizationService: AuthorizationService? = null
    private var transport: NativeLlmTransportController? = null
    private var pendingResult: MethodChannel.Result? = null
    private var authEventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val conversationExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        stateStore = SecureAuthStateStore(applicationContext)
        conversationStore = SecureConversationStore(applicationContext)
        authorizationService = AuthorizationService(applicationContext)
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        authEventChannel = EventChannel(binding.binaryMessenger, AUTH_EVENT_CHANNEL)
        authEventChannel.setStreamHandler(this)
        conversationChannel = MethodChannel(binding.binaryMessenger, CONVERSATION_CHANNEL)
        conversationChannel.setMethodCallHandler(::onConversationMethodCall)
        transport = NativeLlmTransportController(
            stateStore,
            requireNotNull(authorizationService),
            onReauthenticationRequired = {
                emitAuthEvent("reauthentication_required", reauthenticationRequired())
            },
        )
        transportChannel = MethodChannel(binding.binaryMessenger, TRANSPORT_CHANNEL)
        transportChannel.setMethodCallHandler(transport)
        transportEventChannel = io.flutter.plugin.common.EventChannel(
            binding.binaryMessenger,
            TRANSPORT_EVENT_CHANNEL,
        )
        transportEventChannel.setStreamHandler(transport)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getCapabilities" -> result.success(capabilities())
            "signIn" -> signIn(call.arguments as? Map<*, *>, result)
            "restoreSession" -> restoreSession(result)
            "cancelSignIn" -> cancelSignIn(result)
            "signOut" -> signOut(call.arguments as? Map<*, *>, result)
            else -> result.notImplemented()
        }
    }

    private fun onConversationMethodCall(call: MethodCall, result: MethodChannel.Result) {
        conversationExecutor.execute {
            when (call.method) {
                "loadConversationSnapshot" -> completeConversation(result) {
                    conversationStore.read()?.let { payload ->
                        mapOf(
                            "schemaVersion" to CONVERSATION_SCHEMA_VERSION,
                            "payload" to payload,
                        )
                    }
                }
                "saveConversationSnapshot" -> completeConversation(result) {
                    val arguments = call.arguments as? Map<*, *> ?: error("arguments missing")
                    require(arguments.keys.all { it == "schemaVersion" || it == "payload" })
                    require(arguments["schemaVersion"] == CONVERSATION_SCHEMA_VERSION)
                    val payload = arguments["payload"] as? String ?: error("payload missing")
                    conversationStore.write(payload)
                    null
                }
                "clearConversationSnapshot" -> completeConversation(result) {
                    require(call.arguments == null)
                    conversationStore.clear()
                    null
                }
                else -> mainHandler.post(result::notImplemented)
            }
        }
    }

    private fun completeConversation(
        result: MethodChannel.Result,
        operation: () -> Any?,
    ) {
        try {
            val value = operation()
            mainHandler.post { result.success(value) }
        } catch (_: ConversationStoreException) {
            mainHandler.post {
                result.error(
                    "conversation_storage_failure",
                    "Encrypted conversation storage failed.",
                    null,
                )
            }
        } catch (_: Exception) {
            mainHandler.post {
                result.error(
                    "conversation_invalid",
                    "Conversation payload is invalid.",
                    null,
                )
            }
        }
    }

    private fun signIn(arguments: Map<*, *>?, result: MethodChannel.Result) {
        if (pendingResult != null) {
            result.error("operation_in_progress", "An authorization operation is active.", null)
            return
        }
        val currentActivity = activity
        if (currentActivity == null) {
            result.error("activity_unavailable", "No foreground Activity is attached.", null)
            return
        }
        val configuration = try {
            OAuthConfiguration.from(arguments)
        } catch (_: Exception) {
            result.error("configuration_invalid", "OIDC configuration is invalid.", null)
            return
        }

        pendingResult = result
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(configuration.issuer)) {
                serviceConfiguration,
                error,
            ->
            if (pendingResult == null) return@fetchFromIssuer
            if (serviceConfiguration == null || error != null) {
                finishWithError(
                    "oauth_discovery_failed",
                    "OIDC discovery failed.",
                )
                return@fetchFromIssuer
            }
            val additional = buildMap {
                configuration.audience?.let { put("audience", it) }
            }
            val request = AuthorizationRequest.Builder(
                serviceConfiguration,
                configuration.clientId,
                ResponseTypeValues.CODE,
                Uri.parse(configuration.redirectUri),
            )
                .setScopes(configuration.scopes)
                .setAdditionalParameters(additional)
                .build()
            try {
                val intent = requireNotNull(authorizationService)
                    .getAuthorizationRequestIntent(request)
                currentActivity.startActivityForResult(intent, AUTH_REQUEST_CODE)
            } catch (_: Exception) {
                finishWithError("activity_unavailable", "Authorization UI could not start.")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != AUTH_REQUEST_CODE) return false
        val result = pendingResult ?: return true
        pendingResult = null
        if (data == null) {
            result.error(
                "oauth_authorization_cancelled",
                "Authorization was cancelled.",
                null,
            )
            return true
        }
        val response = AuthorizationResponse.fromIntent(data)
        val authorizationError = AuthorizationException.fromIntent(data)
        if (response == null) {
            val code = if (authorizationError?.error == "access_denied") {
                "oauth_authorization_failed"
            } else {
                "oauth_authorization_cancelled"
            }
            result.error(code, "Authorization did not complete.", null)
            return true
        }

        val authState = AuthState(response, authorizationError)
        requireNotNull(authorizationService).performTokenRequest(
            response.createTokenExchangeRequest(),
        ) { tokenResponse, tokenError ->
            authState.update(tokenResponse, tokenError)
            if (tokenResponse == null || tokenError != null || !authState.isAuthorized) {
                result.error(
                    "oauth_token_exchange_failed",
                    "Authorization code exchange failed.",
                    null,
                )
                return@performTokenRequest
            }
            try {
                stateStore.write(authState)
                transport?.updateAuthState(authState)
                val summary = sessionSummary(authState)
                result.success(summary)
                emitAuthEvent("signed_in", summary)
            } catch (_: SecureStoreException) {
                stateStore.clear()
                result.error("storage_failure", "Secure storage failed.", null)
            }
        }
        return true
    }

    private fun restoreSession(result: MethodChannel.Result) {
        val state = stateStore.read()
        val summary = when {
            state == null -> signedOut()
            state.isAuthorized -> sessionSummary(state)
            else -> reauthenticationRequired()
        }
        result.success(summary)
        emitAuthEvent("restored", summary)
    }

    private fun cancelSignIn(result: MethodChannel.Result) {
        val authorizationResult = pendingResult
        pendingResult = null
        activity?.finishActivity(AUTH_REQUEST_CODE)
        authorizationResult?.error(
            "oauth_authorization_cancelled",
            "Authorization was cancelled.",
            null,
        )
        result.success(null)
    }

    private fun signOut(arguments: Map<*, *>?, result: MethodChannel.Result) {
        if (arguments != null && arguments.keys.any { it != "bffBaseUrl" }) {
            result.error("configuration_invalid", "Sign-out configuration is invalid.", null)
            return
        }
        val bffBaseUrl = arguments?.get("bffBaseUrl") as? String
        if (bffBaseUrl != null && !NativeLlmTransportController.isValidBaseUrl(bffBaseUrl)) {
            result.error("configuration_invalid", "Sign-out configuration is invalid.", null)
            return
        }
        pendingResult?.error(
            "oauth_authorization_cancelled",
            "Authorization was cancelled.",
            null,
        )
        pendingResult = null
        val currentTransport = transport
        if (currentTransport == null) {
            stateStore.clear()
            result.success(null)
            emitAuthEvent("signed_out", signedOut())
            return
        }
        currentTransport.revokeSession(bffBaseUrl) {
            result.success(null)
            emitAuthEvent("signed_out", signedOut())
        }
    }

    private fun finishWithError(code: String, message: String) {
        val result = pendingResult ?: return
        pendingResult = null
        result.error(code, message, null)
    }

    private fun sessionSummary(state: AuthState): Map<String, Any> = buildMap {
        put("status", "authenticated")
        put("canRefresh", state.refreshToken != null)
        state.accessTokenExpirationTime?.let { put("expiresAtMillis", it) }
        accountLabel(state.idToken)?.let { put("accountLabel", it) }
    }

    private fun accountLabel(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val claims = JSONObject(String(decoded, StandardCharsets.UTF_8))
            listOf("name", "preferred_username", "email")
                .asSequence()
                .map(claims::optString)
                .firstOrNull(String::isNotBlank)
        }.getOrNull()
    }

    private fun signedOut(): Map<String, Any> = mapOf(
        "status" to "signed_out",
        "canRefresh" to false,
    )

    private fun reauthenticationRequired(): Map<String, Any> = mapOf(
        "status" to "reauthentication_required",
        "canRefresh" to false,
    )

    private fun capabilities(): Map<String, Any> = mapOf(
        "protocolVersion" to PROTOCOL_VERSION,
        "authStateEvents" to true,
        "secureSessionStorage" to true,
        "nativeAuthorizedTransport" to true,
    )

    private fun emitAuthEvent(reason: String, summary: Map<String, Any>) {
        val event = mapOf(
            "protocolVersion" to PROTOCOL_VERSION,
            "reason" to reason,
            "session" to summary,
        )
        mainHandler.post { authEventSink?.success(event) }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        authEventSink = events
        val state = stateStore.read()
        val summary = when {
            state == null -> signedOut()
            state.isAuthorized -> sessionSummary(state)
            else -> reauthenticationRequired()
        }
        emitAuthEvent("restored", summary)
    }

    override fun onCancel(arguments: Any?) {
        authEventSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
        finishWithError("activity_unavailable", "Foreground Activity was detached.")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        authEventChannel.setStreamHandler(null)
        conversationChannel.setMethodCallHandler(null)
        transportChannel.setMethodCallHandler(null)
        transportEventChannel.setStreamHandler(null)
        transport?.dispose()
        transport = null
        authorizationService?.dispose()
        authorizationService = null
        pendingResult = null
        authEventSink = null
        conversationExecutor.shutdownNow()
    }

    companion object {
        private const val CHANNEL = "mobile_agent_auth"
        private const val AUTH_EVENT_CHANNEL = "mobile_agent_auth/events"
        private const val CONVERSATION_CHANNEL = "mobile_agent_conversations"
        private const val TRANSPORT_CHANNEL = "mobile_agent_llm_transport"
        private const val TRANSPORT_EVENT_CHANNEL = "mobile_agent_llm_transport/events"
        private const val AUTH_REQUEST_CODE = 0x4D41
        private const val PROTOCOL_VERSION = 1
        private const val CONVERSATION_SCHEMA_VERSION = 1
    }
}
