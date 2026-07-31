package dev.alpine.llm

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Generic OAuth 2.0 Authorization Code + PKCE manager for Android. */
class OAuthManager(
    context: Context,
    private val config: OAuthProviderConfig,
    private val store: OAuthTokenStore = OAuthTokenStore(context),
) {
    private val appContext = context.applicationContext
    private val authorizationMutex = Mutex()
    @Volatile private var activeCallback: CompletableDeferred<OAuthCallbackServer.Callback>? = null

    suspend fun authorize(
        browserContext: Context = appContext,
    ): OAuthTokenStore.Token = authorizationMutex.withLock {
        authorizeOnce(browserContext)
    }

    fun cancelAuthorization(): Boolean =
        activeCallback?.complete(
            OAuthCallbackServer.Callback(
                code = null,
                state = null,
                error = "access_denied",
                errorDescription = "cancelled by host application",
            ),
        ) ?: false

    private suspend fun authorizeOnce(
        browserContext: Context,
    ): OAuthTokenStore.Token = withContext(Dispatchers.IO) {
        val pkce = OAuthPkce.create()
        val state = OAuthPkce.state()
        try {
            store.saveTransaction(
                config.providerId,
                OAuthTokenStore.Transaction(
                    state = state,
                    verifier = pkce.verifier,
                    createdAtMs = System.currentTimeMillis(),
                    challenge = pkce.challenge,
                ),
            )
        } catch (error: Exception) {
            throw OAuthException(
                "failed to store the OAuth transaction",
                OAuthFailureKind.STORAGE_FAILURE,
                error,
            )
        }

        val callbackResult = CompletableDeferred<OAuthCallbackServer.Callback>()
        activeCallback = callbackResult
        val callback = OAuthCallbackServer(
            requestedPort = config.callbackPort,
            redirectPath = config.redirectPath,
            fallbackPorts = listOf(config.callbackPort + 1, config.callbackPort + 2),
        ) { result -> callbackResult.complete(result) }
        var callbackRegistered = false
        try {
            callback.start()
            OAuthCallbackRegistry.register(callback.boundPort, config.redirectPath, state)
            callbackRegistered = true
            val redirectUri = config.redirectUri(callback.boundPort)
            val authUrl = buildAuthorizationUrl(redirectUri, state, pkce.challenge)
            withContext(Dispatchers.Main) {
                val customTab = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                if (browserContext !is Activity) {
                    customTab.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                customTab.launchUrl(browserContext, Uri.parse(authUrl))
            }
            val result = OAuthCallbackAwaiter.await(callbackResult, config.callbackTimeoutMs)
            val transaction = store.loadTransaction(config.providerId)
            val code = OAuthCallbackValidator.validate(
                callback = result,
                transaction = transaction,
                nowMs = System.currentTimeMillis(),
                timeoutMs = config.callbackTimeoutMs,
            )
            exchangeCode(code, requireNotNull(transaction), redirectUri)
        } finally {
            if (callbackRegistered) {
                OAuthCallbackRegistry.unregister(callback.boundPort, state)
            }
            callback.stop()
            store.clearTransaction(config.providerId)
            if (activeCallback === callbackResult) activeCallback = null
        }
    }

    suspend fun validToken(): OAuthTokenStore.Token? = withContext(Dispatchers.IO) {
        val current = readStoredToken() ?: return@withContext null
        val now = System.currentTimeMillis()
        if (!current.isExpiringWithin(now, config.refreshSkewMs)) return@withContext current
        refreshSingleFlight()
    }

    suspend fun validAccessToken(): String? = validToken()?.accessToken

    suspend fun refreshAfterUnauthorized(
        rejectedAccessToken: String,
    ): OAuthTokenStore.Token? = withContext(Dispatchers.IO) {
        OAuthRefreshCoordinator.resolveRejected(
            providerId = config.providerId,
            rejectedAccessToken = rejectedAccessToken,
            loadLatest = ::readStoredToken,
            refresh = ::refreshToken,
            clearCredential = { store.delete(config.providerId) },
        )
    }

    fun invalidateIfCurrent(rejectedAccessToken: String) {
        val current = (store.read(config.providerId) as? OAuthTokenStore.ReadResult.Available)?.token
        if (current?.accessToken == rejectedAccessToken) store.delete(config.providerId)
    }

    fun authenticationState(): OAuthAuthenticationState =
        when (val result = store.read(config.providerId)) {
            is OAuthTokenStore.ReadResult.Available -> OAuthAuthenticationState.Authenticated(
                expiresAtMs = result.token.expiresAtMs,
                metadata = result.token.metadata,
            )
            OAuthTokenStore.ReadResult.Missing -> OAuthAuthenticationState.SignedOut
            is OAuthTokenStore.ReadResult.ReauthenticationRequired ->
                OAuthAuthenticationState.ReauthenticationRequired(result.reason)
        }

    private suspend fun refreshSingleFlight(): OAuthTokenStore.Token? =
        OAuthRefreshCoordinator.resolve(
            providerId = config.providerId,
            refreshSkewMs = config.refreshSkewMs,
            loadLatest = ::readStoredToken,
            refresh = ::refreshToken,
            clearCredential = { store.delete(config.providerId) },
        )

    fun logout() = store.delete(config.providerId)

    fun isAuthenticated(): Boolean =
        authenticationState() is OAuthAuthenticationState.Authenticated

    private fun buildAuthorizationUrl(redirectUri: String, state: String, challenge: String): String {
        val params = linkedMapOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to config.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        params.putAll(config.extraAuthorizationParams)
        val separator = if (config.authorizationEndpoint.contains("?")) "&" else "?"
        return config.authorizationEndpoint + separator + OAuthPkce.formEncode(params)
    }

    private fun exchangeCode(
        code: String,
        transaction: OAuthTokenStore.Transaction,
        redirectUri: String,
    ): OAuthTokenStore.Token {
        val standardParams = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to config.clientId,
            "code_verifier" to transaction.verifier,
        )
        standardParams.putAll(config.extraTokenParams)
        val params = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                parameters = standardParams,
                state = transaction.state,
                codeChallenge = transaction.challenge,
            ),
        )
        return tokenRequest(params).also(::saveToken)
    }

    private fun refreshToken(current: OAuthTokenStore.Token, refreshToken: String): OAuthTokenStore.Token {
        val standardParams = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to config.clientId,
        )
        standardParams.putAll(config.extraTokenParams)
        val params = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.REFRESH_TOKEN,
                parameters = standardParams,
            ),
        )
        val refreshed = tokenRequest(params)
        val preserved = refreshed.copy(
            refreshToken = refreshed.refreshToken ?: current.refreshToken,
            scope = refreshed.scope ?: current.scope,
            metadata = if (refreshed.metadata.isEmpty()) current.metadata else refreshed.metadata,
        )
        saveToken(preserved)
        return preserved
    }

    private fun tokenRequest(params: Map<String, String>): OAuthTokenStore.Token {
        val connection = URL(config.tokenEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val bodyParams = params.toMutableMap()
        if (config.clientAuthMethod == OAuthProviderConfig.ClientAuthMethod.BODY) {
            config.clientSecret?.let { bodyParams["client_secret"] = it }
        } else if (config.clientAuthMethod == OAuthProviderConfig.ClientAuthMethod.BASIC) {
            val secret = config.clientSecret ?: throw OAuthException(
                "client secret is required for basic client auth",
                OAuthFailureKind.CONFIGURATION,
            )
            val credentials = Base64.getEncoder().encodeToString(
                "${config.clientId}:$secret".toByteArray(StandardCharsets.UTF_8),
            )
            connection.setRequestProperty("Authorization", "Basic $credentials")
        }
        val encoded = OAuthTokenRequestEncoder.encode(bodyParams, config.tokenRequestEncoding)
        connection.setRequestProperty("Content-Type", encoded.contentType)
        val responseCode: Int
        val responseBody: String
        try {
            connection.outputStream.use {
                it.write(encoded.body.toByteArray(Charsets.UTF_8))
            }
            responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            responseBody = stream?.use { readLimited(it, MAX_TOKEN_RESPONSE_BYTES) }.orEmpty()
        } catch (error: OAuthException) {
            throw error
        } catch (error: Exception) {
            throw OAuthException(
                "token endpoint request failed",
                OAuthFailureKind.NETWORK,
                error,
            )
        } finally {
            connection.disconnect()
        }
        if (responseCode !in 200..299) {
            val errorCode = tokenErrorCode(responseBody)
            val invalidCodes = setOf("invalid_grant", "invalid_token", "refresh_token_reused")
            val kind = if (errorCode?.lowercase() in invalidCodes) {
                OAuthFailureKind.INVALID_GRANT
            } else {
                OAuthFailureKind.PROVIDER_ERROR
            }
            throw OAuthException("token endpoint returned HTTP $responseCode", kind)
        }
        val json = runCatching { JSONObject(responseBody) }.getOrElse {
            throw OAuthException(
                "token endpoint returned invalid JSON",
                OAuthFailureKind.PROTOCOL,
                it,
            )
        }
        return try {
            config.tokenResponseAdapter.parse(json, System.currentTimeMillis())
        } catch (error: OAuthException) {
            throw error
        } catch (error: Exception) {
            throw OAuthException(
                "provider token response adapter failed",
                OAuthFailureKind.PROTOCOL,
                error,
            )
        }
    }

    private fun tokenErrorCode(responseBody: String): String? = runCatching {
        when (val error = JSONObject(responseBody).opt("error")) {
            is String -> error
            is JSONObject -> error.optString("code").ifBlank {
                error.optString("type").ifBlank { null }
            }
            else -> null
        }
    }.getOrNull()

    private fun readLimited(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw OAuthException(
                    "token endpoint response exceeds limit",
                    OAuthFailureKind.PROTOCOL,
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun readStoredToken(): OAuthTokenStore.Token? =
        when (val result = store.read(config.providerId)) {
            is OAuthTokenStore.ReadResult.Available -> result.token
            OAuthTokenStore.ReadResult.Missing -> null
            is OAuthTokenStore.ReadResult.ReauthenticationRequired -> throw OAuthException(
                "stored OAuth credential can no longer be decrypted",
                OAuthFailureKind.STORAGE_INVALIDATED,
            )
        }

    private fun saveToken(token: OAuthTokenStore.Token) {
        try {
            store.save(config.providerId, token)
        } catch (error: Exception) {
            throw OAuthException(
                "failed to store OAuth credential",
                OAuthFailureKind.STORAGE_FAILURE,
                error,
            )
        }
    }

    private companion object {
        const val MAX_TOKEN_RESPONSE_BYTES = 1024 * 1024
    }
}
