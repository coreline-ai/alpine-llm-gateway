package ai.coreline.mobile_agent_auth

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthState
import org.json.JSONArray
import org.json.JSONObject

internal class NativeLlmTransportController(
    private val stateStore: SecureAuthStateStore,
    private val authorizationService: AuthorizationService,
    private val onReauthenticationRequired: () -> Unit,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val requests = ConcurrentHashMap<String, ActiveRequest>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val authStateLock = Any()

    @Volatile
    private var cachedAuthState: AuthState? = null

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startStream" -> startStream(call.arguments as? Map<*, *>, result)
            "cancelRequest" -> cancelRequest(call.arguments as? Map<*, *>, result)
            "requestState" -> requestState(call.arguments as? Map<*, *>, result)
            else -> result.notImplemented()
        }
    }

    private fun startStream(arguments: Map<*, *>?, result: MethodChannel.Result) {
        if (eventSink == null) {
            result.error("event_listener_unavailable", "Event listener is not active.", null)
            return
        }
        val parsed = runCatching { NativeStreamInput.from(arguments) }.getOrElse {
            result.error("request_invalid", "Streaming request is invalid.", null)
            return
        }
        val active = ActiveRequest(parsed.requestId, parsed.baseUrl)
        if (requests.putIfAbsent(parsed.requestId, active) != null) {
            result.error("request_in_progress", "Request is already active.", null)
            return
        }
        val state = synchronized(authStateLock) {
            cachedAuthState?.takeIf { it.isAuthorized }
                ?: stateStore.read()?.also { cachedAuthState = it }
        }
        if (state?.isAuthorized != true) {
            requests.remove(parsed.requestId)
            onReauthenticationRequired()
            result.error("reauthentication_required", "A valid login is required.", null)
            return
        }
        state.performActionWithFreshTokens(authorizationService) { accessToken, _, error ->
            if (error != null || accessToken.isNullOrBlank() || active.cancelled.get()) {
                requests.remove(parsed.requestId)
                if (active.cancelled.get()) {
                    result.error("request_cancelled", "Request was cancelled.", null)
                } else {
                    onReauthenticationRequired()
                    result.error("reauthentication_required", "Token refresh failed.", null)
                }
                return@performActionWithFreshTokens
            }
            try {
                stateStore.write(state)
            } catch (_: SecureStoreException) {
                requests.remove(parsed.requestId)
                result.error("storage_failure", "Secure storage failed.", null)
                return@performActionWithFreshTokens
            }
            active.accessToken = accessToken
            active.phase.set("streaming")
            active.future = executor.submit { executeStream(parsed, active) }
            result.success(null)
        }
    }

    private fun cancelRequest(arguments: Map<*, *>?, result: MethodChannel.Result) {
        val requestId = arguments?.get("requestId") as? String
        val baseUrl = arguments?.get("bffBaseUrl") as? String
        if (
            requestId == null ||
            !REQUEST_ID.matches(requestId) ||
            baseUrl == null ||
            !isValidBaseUrl(baseUrl)
        ) {
            result.error("request_invalid", "Request ID is invalid.", null)
            return
        }
        val active = requests.remove(requestId)
        if (active == null) {
            result.success(cancelResult(requestId, false, "not_required"))
            return
        }
        active.phase.set("cancelling")
        active.cancelled.set(true)
        active.connection.getAndSet(null)?.disconnect()
        active.future?.cancel(true)
        emitTerminal(active, "cancelled", emptyMap())
        val token = active.accessToken
        if (token.isNullOrBlank()) {
            result.success(cancelResult(requestId, true, "not_required"))
            return
        }
        runCatching {
            executor.submit {
                val acknowledgment = sendServerCancel(baseUrl, requestId, token)
                mainHandler.post {
                    result.success(cancelResult(requestId, true, acknowledgment))
                }
            }
        }.onFailure {
            result.success(cancelResult(requestId, true, "unavailable"))
        }
    }

    private fun requestState(arguments: Map<*, *>?, result: MethodChannel.Result) {
        val requestId = arguments?.get("requestId") as? String
        if (
            arguments == null ||
            arguments.keys.any { it != "requestId" } ||
            requestId == null ||
            !REQUEST_ID.matches(requestId)
        ) {
            result.error("request_invalid", "Request ID is invalid.", null)
            return
        }
        result.success(
            mapOf(
                "requestId" to requestId,
                "state" to (requests[requestId]?.phase?.get() ?: "not_found"),
            ),
        )
    }

    private fun executeStream(input: NativeStreamInput, active: ActiveRequest) {
        var terminalReceived = false
        try {
            val connection = openConnection(
                endpoint(input.baseUrl, "/v1/chat/stream"),
                active.accessToken ?: return,
            )
            active.connection.set(connection)
            connection.outputStream.use { stream ->
                stream.write(input.requestJson.encodeToByteArray())
            }
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 401) onReauthenticationRequired()
                emitTerminal(active, "error", mapOf("code" to statusError(connection.responseCode)))
                return
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var eventType: String? = null
                val data = StringBuilder()
                while (!active.cancelled.get()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                val type = eventType ?: "message"
                                val payload = jsonToMap(JSONObject(data.toString()))
                                if (type in TERMINAL_EVENTS) {
                                    terminalReceived = true
                                    emitTerminal(active, type, payload)
                                } else {
                                    emit(active.requestId, type, payload)
                                }
                                data.clear()
                                eventType = null
                            }
                        }
                        line.startsWith("event:") -> eventType = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).trimStart())
                        }
                    }
                }
            }
            if (!active.cancelled.get() && !terminalReceived) {
                emitTerminal(active, "error", mapOf("code" to "stream_ended_early"))
            }
        } catch (_: Exception) {
            if (!active.cancelled.get()) {
                emitTerminal(active, "error", mapOf("code" to "network_unavailable"))
            }
        } finally {
            active.connection.getAndSet(null)?.disconnect()
            requests.remove(active.requestId, active)
        }
    }

    private fun sendServerCancel(
        baseUrl: String,
        requestId: String,
        accessToken: String,
    ): String = runCatching {
            val connection = openConnection(
                endpoint(baseUrl, "/v1/requests/$requestId/cancel"),
                accessToken,
            )
            connection.readTimeout = 5_000
            connection.outputStream.use { it.write(byteArrayOf()) }
            val acknowledgment = when (connection.responseCode) {
                202 -> "accepted"
                404 -> "not_active"
                else -> "unavailable"
            }
            connection.disconnect()
            acknowledgment
        }.getOrDefault("unavailable")

    private fun cancelResult(
        requestId: String,
        localCancelled: Boolean,
        serverAcknowledgment: String,
    ): Map<String, Any> = mapOf(
        "requestId" to requestId,
        "localCancelled" to localCancelled,
        "serverAcknowledgment" to serverAcknowledgment,
    )

    private fun openConnection(url: String, accessToken: String): HttpsURLConnection =
        (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 130_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }

    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

    private fun emit(requestId: String, type: String, data: Map<String, Any?>) {
        val event = mapOf(
            "requestId" to requestId,
            "type" to type,
            "data" to data,
        )
        mainHandler.post { eventSink?.success(event) }
    }

    private fun emitTerminal(active: ActiveRequest, type: String, data: Map<String, Any?>) {
        if (active.terminalEmitted.compareAndSet(false, true)) {
            emit(active.requestId, type, data)
        }
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        eventSink = sink
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        cancelAll()
    }

    fun cancelAll() {
        requests.values.toList().forEach { active ->
            requests.remove(active.requestId, active)
            active.cancelled.set(true)
            active.phase.set("cancelling")
            active.connection.getAndSet(null)?.disconnect()
            active.future?.cancel(true)
        }
    }

    fun updateAuthState(state: AuthState) {
        synchronized(authStateLock) {
            cachedAuthState = state
        }
    }

    fun clearAuthState() {
        synchronized(authStateLock) {
            cachedAuthState = null
        }
    }

    fun revokeSession(bffBaseUrl: String?, onComplete: () -> Unit) {
        cancelAll()
        val state = synchronized(authStateLock) {
            (cachedAuthState ?: stateStore.read()).also { cachedAuthState = null }
        }
        stateStore.clear()
        if (state == null) {
            mainHandler.post(onComplete)
            return
        }
        executor.submit {
            state.accessToken?.takeIf(String::isNotBlank)?.let { accessToken ->
                bffBaseUrl?.let { revokeBffSession(it, accessToken) }
            }
            revokeIdentityProviderRefresh(state)
            mainHandler.post(onComplete)
        }
    }

    fun dispose() {
        cancelAll()
        executor.shutdownNow()
        eventSink = null
        clearAuthState()
    }

    private fun statusError(statusCode: Int): String = when (statusCode) {
        401 -> "reauthentication_required"
        403 -> "provider_access_denied"
        404 -> "provider_not_found"
        429 -> "provider_rate_limited"
        in 500..599 -> "provider_unavailable"
        else -> "request_rejected"
    }

    companion object {
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,80}$")
        private val TERMINAL_EVENTS = setOf("done", "cancelled", "error")

        fun isValidBaseUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null
        }.getOrDefault(false)
    }

    private fun revokeBffSession(baseUrl: String, accessToken: String) {
        runCatching {
            val connection = openConnection(
                endpoint(baseUrl, "/v1/session/revoke"),
                accessToken,
            )
            connection.readTimeout = 5_000
            connection.outputStream.use { it.write(byteArrayOf()) }
            connection.responseCode
            connection.disconnect()
        }
    }

    private fun revokeIdentityProviderRefresh(state: AuthState) {
        val refreshToken = state.refreshToken?.takeIf(String::isNotBlank) ?: return
        val configuration = state.authorizationServiceConfiguration ?: return
        val discovery = configuration.discoveryDoc?.docJson ?: return
        val revocationEndpoint = discovery.optString("revocation_endpoint")
        val issuer = discovery.optString("issuer")
        val clientId = state.lastAuthorizationResponse?.request?.clientId ?: return
        if (!sameHostHttps(issuer, revocationEndpoint)) return
        runCatching {
            val form = formBody(
                mapOf(
                    "token" to refreshToken,
                    "token_type_hint" to "refresh_token",
                    "client_id" to clientId,
                ),
            )
            val connection = URI(revocationEndpoint).toURL().openConnection()
                as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=utf-8",
            )
            connection.outputStream.use { it.write(form.encodeToByteArray()) }
            connection.responseCode
            connection.disconnect()
        }
    }

    private fun sameHostHttps(issuer: String, endpoint: String): Boolean = runCatching {
        val issuerUri = URI(issuer)
        val endpointUri = URI(endpoint)
        issuerUri.scheme == "https" &&
            endpointUri.scheme == "https" &&
            !issuerUri.host.isNullOrBlank() &&
            issuerUri.host.equals(endpointUri.host, ignoreCase = true) &&
            endpointUri.userInfo == null &&
            endpointUri.fragment == null
    }.getOrDefault(false)

    private fun formBody(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, Charsets.UTF_8.name())}=" +
            URLEncoder.encode(it.value, Charsets.UTF_8.name())
    }
}

private data class NativeStreamInput(
    val requestId: String,
    val baseUrl: String,
    val requestJson: String,
) {
    companion object {
        fun from(arguments: Map<*, *>?): NativeStreamInput {
            requireNotNull(arguments)
            require(arguments.keys.all { it in setOf("bffBaseUrl", "request") })
            val baseUrl = arguments["bffBaseUrl"] as? String ?: error("base URL missing")
            validateBaseUrl(baseUrl)
            val request = arguments["request"] as? Map<*, *> ?: error("request missing")
            val requestId = request["request_id"] as? String ?: error("request ID missing")
            require(Regex("^[A-Za-z0-9_-]{8,80}$").matches(requestId))
            val json = JSONObject(request).toString()
            require(json.length <= 500_000)
            return NativeStreamInput(requestId, baseUrl, json)
        }

        private fun validateBaseUrl(value: String) {
            require(NativeLlmTransportController.isValidBaseUrl(value))
        }
    }
}

private class ActiveRequest(
    val requestId: String,
    val baseUrl: String,
) {
    val cancelled = AtomicBoolean(false)
    val terminalEmitted = AtomicBoolean(false)
    val connection = AtomicReference<HttpsURLConnection?>()
    val phase = AtomicReference("preparing")

    @Volatile
    var accessToken: String? = null

    @Volatile
    var future: Future<*>? = null
}

private fun jsonToMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith {
    jsonValue(value.get(it))
}

private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonToMap(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}
