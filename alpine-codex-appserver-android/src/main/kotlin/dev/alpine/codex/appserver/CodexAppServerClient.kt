package dev.alpine.codex.appserver

import dev.alpine.codex.appserver.protocol.CodexRpcClient
import dev.alpine.codex.appserver.protocol.CodexRpcNotification
import java.net.URI
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

enum class CodexAccountState {
    SIGNED_OUT,
    CHATGPT,
    OTHER,
}

sealed interface CodexLoginStart {
    val loginId: String

    data class Browser(
        override val loginId: String,
        val authorizationUri: URI,
    ) : CodexLoginStart

    data class DeviceCode(
        override val loginId: String,
        val verificationUri: URI,
        val userCode: String,
    ) : CodexLoginStart
}

data class CodexModel(
    val id: String,
    val displayName: String,
    val isDefault: Boolean,
)

interface CodexAccountApi {
    val notifications: SharedFlow<CodexRpcNotification>

    suspend fun accountState(): CodexAccountState
    suspend fun startBrowserLogin(): CodexLoginStart.Browser
    suspend fun startDeviceCodeLogin(): CodexLoginStart.DeviceCode
    suspend fun cancelLogin(loginId: String)
    suspend fun logout()
}

interface CodexAgentApi {
    val notifications: SharedFlow<CodexRpcNotification>

    suspend fun startThread(model: String): String
    suspend fun resumeThread(threadId: String, model: String): String
    suspend fun startTurn(threadId: String, model: String, prompt: String, actionId: String): String
    suspend fun interruptTurn(threadId: String, turnId: String)
}

class CodexAppServerClient internal constructor(
    private val rpc: CodexRpcClient,
    private val workspacePath: String = "/private/codex-workspace",
) : CodexAccountApi, CodexAgentApi {
    override val notifications: SharedFlow<CodexRpcNotification> = rpc.notifications

    suspend fun initialize(version: String): JSONObject = rpc.initialize(
        clientName = "alpine_llm_gateway_android",
        title = "Alpine AI Workspace",
        version = version,
    )

    override suspend fun accountState(): CodexAccountState = readAccount(refreshToken = false)

    /**
     * Asks the official App Server to refresh its own credential before returning redacted state.
     * Android still receives only the account kind and never reads token or credential fields.
     */
    suspend fun refreshAccountState(): CodexAccountState = readAccount(refreshToken = true)

    private suspend fun readAccount(refreshToken: Boolean): CodexAccountState {
        val result = rpc.request("account/read", JSONObject().put("refreshToken", refreshToken))
        result.requiredBoolean("requiresOpenaiAuth")
        val rawAccount = result.opt("account")
        if (rawAccount == null || rawAccount == JSONObject.NULL) {
            return CodexAccountState.SIGNED_OUT
        }
        val account = rawAccount as? JSONObject ?: failProtocol()
        return if (account.requiredString("type") == "chatgpt") {
            CodexAccountState.CHATGPT
        } else {
            CodexAccountState.OTHER
        }
    }

    override suspend fun startBrowserLogin(): CodexLoginStart.Browser {
        val result = rpc.request(
            "account/login/start",
            JSONObject()
                .put("type", "chatgpt")
                .put("useHostedLoginSuccessPage", true)
                .put("appBrand", "codex"),
        )
        if (result.requiredString("type") != "chatgpt") failProtocol()
        return CodexLoginStart.Browser(
            loginId = boundedId(result.requiredString("loginId")),
            authorizationUri = validateAuthorizationUri(
                result.requiredString("authUrl"),
                BROWSER_LOGIN_HOSTS,
            ),
        )
    }

    override suspend fun startDeviceCodeLogin(): CodexLoginStart.DeviceCode {
        val result = rpc.request(
            "account/login/start",
            JSONObject().put("type", "chatgptDeviceCode"),
        )
        if (result.requiredString("type") != "chatgptDeviceCode") failProtocol()
        val code = result.requiredString("userCode")
        if (code.isBlank() || code.length > MAX_USER_CODE_LENGTH) failProtocol()
        return CodexLoginStart.DeviceCode(
            loginId = boundedId(result.requiredString("loginId")),
            verificationUri = validateAuthorizationUri(
                result.requiredString("verificationUrl"),
                DEVICE_LOGIN_HOSTS,
            ),
            userCode = code,
        )
    }

    override suspend fun cancelLogin(loginId: String) {
        val result = rpc.request(
            "account/login/cancel",
            JSONObject().put("loginId", boundedId(loginId)),
        )
        if (result.requiredString("status") !in CANCEL_LOGIN_STATUSES) failProtocol()
    }

    override suspend fun logout() {
        rpc.request("account/logout")
    }

    suspend fun models(): List<CodexModel> {
        val models = mutableListOf<CodexModel>()
        var cursor: String? = null
        var pages = 0
        do {
            if (++pages > MAX_MODEL_PAGES) failProtocol()
            val params = JSONObject().put("limit", MODEL_PAGE_SIZE).put("includeHidden", false)
            if (cursor != null) params.put("cursor", cursor)
            val result = rpc.request("model/list", params)
            val data = result.requiredArray("data")
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: failProtocol()
                if (item.requiredBoolean("hidden")) continue
                val id = boundedModel(item.requiredString("model"))
                val display = item.requiredString("displayName").takeIf(String::isNotBlank) ?: id
                if (display.length > MAX_DISPLAY_NAME_LENGTH) failProtocol()
                models += CodexModel(id, display, item.requiredBoolean("isDefault"))
                if (models.size > MAX_MODELS) failProtocol()
            }
            cursor = result.optionalString("nextCursor")?.takeIf(String::isNotBlank)
            if (cursor != null &&
                (cursor.length > MAX_CURSOR_LENGTH || cursor.any(Char::isISOControl))
            ) {
                failProtocol()
            }
        } while (cursor != null)
        return models.distinctBy(CodexModel::id)
    }

    override suspend fun startThread(model: String): String {
        val result = rpc.request(
            "thread/start",
            JSONObject()
                .put("cwd", boundedWorkspacePath())
                .put("model", boundedModel(model))
                .put("sandbox", "read-only")
                .put("approvalPolicy", "never")
                .put("ephemeral", false)
                .put("developerInstructions", AGENT_INSTRUCTIONS),
        )
        return threadId(result)
    }

    override suspend fun resumeThread(threadId: String, model: String): String {
        val expected = boundedId(threadId)
        val result = rpc.request(
            "thread/resume",
            JSONObject()
                .put("threadId", expected)
                .put("cwd", boundedWorkspacePath())
                .put("model", boundedModel(model))
                .put("sandbox", "read-only")
                .put("approvalPolicy", "never")
                .put("developerInstructions", AGENT_INSTRUCTIONS),
        )
        return threadId(result).takeIf { it == expected } ?: failProtocol()
    }

    override suspend fun startTurn(
        threadId: String,
        model: String,
        prompt: String,
        actionId: String,
    ): String {
        val normalizedPrompt = prompt.takeIf {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES
        } ?: failProtocol()
        val result = rpc.request(
            "turn/start",
            JSONObject()
                .put("threadId", boundedId(threadId))
                .put("model", boundedModel(model))
                .put("cwd", boundedWorkspacePath())
                .put("approvalPolicy", "never")
                .put("clientUserMessageId", boundedId(actionId))
                .put(
                    "input",
                    org.json.JSONArray().put(
                        JSONObject().put("type", "text").put("text", normalizedPrompt),
                    ),
                ),
        )
        val turn = result.requiredObject("turn")
        return boundedId(turn.requiredString("id"))
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        rpc.request(
            "turn/interrupt",
            JSONObject()
                .put("threadId", boundedId(threadId))
                .put("turnId", boundedId(turnId)),
        )
    }

    internal suspend fun request(method: String, params: JSONObject? = null): JSONObject =
        rpc.request(method, params)

    private fun boundedId(value: String): String = value.takeIf {
        it.isNotBlank() && it.length <= MAX_ID_LENGTH && it.all { character ->
            character.isLetterOrDigit() || character in "-_"
        }
    } ?: failProtocol()

    private fun boundedModel(value: String): String = value.takeIf {
        it.isNotBlank() && it.length <= MAX_MODEL_LENGTH && it.all { character ->
            !character.isISOControl() && !character.isWhitespace()
        }
    } ?: failProtocol()

    private fun boundedWorkspacePath(): String = workspacePath.takeIf {
        it.startsWith('/') && it.length <= MAX_WORKSPACE_PATH_LENGTH &&
            it.none(Char::isISOControl)
    } ?: failProtocol()

    private fun threadId(result: JSONObject): String = boundedId(
        result.requiredObject("thread").requiredString("id"),
    )

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String) ?: failProtocol()

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        (opt(name) as? Boolean) ?: failProtocol()

    private fun JSONObject.requiredObject(name: String): JSONObject =
        (opt(name) as? JSONObject) ?: failProtocol()

    private fun JSONObject.requiredArray(name: String): JSONArray =
        (opt(name) as? JSONArray) ?: failProtocol()

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return (opt(name) as? String) ?: failProtocol()
    }

    private fun validateAuthorizationUri(value: String, hosts: Set<String>): URI {
        if (value.isBlank() || value.length > MAX_AUTH_URL_LENGTH) {
            throw CodexAppServerException(CodexAppServerErrorCode.AUTH_URL_REJECTED)
        }
        val uri = try {
            URI(value)
        } catch (failure: Exception) {
            throw CodexAppServerException(CodexAppServerErrorCode.AUTH_URL_REJECTED, failure)
        }
        val host = uri.host?.lowercase()
        if (
            uri.scheme != "https" || host !in hosts || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.fragment != null
        ) {
            throw CodexAppServerException(CodexAppServerErrorCode.AUTH_URL_REJECTED)
        }
        return uri
    }

    private fun failProtocol(): Nothing =
        throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)

    companion object {
        // The pinned 0.147.0 login server builds its authorize URL from
        // DEFAULT_ISSUER (auth.openai.com). Keep chatgpt.com for compatibility
        // with the app-server protocol README example, while still requiring
        // an exact HTTPS host match.
        private val BROWSER_LOGIN_HOSTS = setOf("auth.openai.com", "chatgpt.com")
        private val DEVICE_LOGIN_HOSTS = setOf("auth.openai.com")
        private val CANCEL_LOGIN_STATUSES = setOf("canceled", "notFound")
        private const val MAX_AUTH_URL_LENGTH = 8 * 1024
        private const val MAX_ID_LENGTH = 128
        private const val MAX_USER_CODE_LENGTH = 64
        private const val MAX_MODEL_LENGTH = 128
        private const val MAX_DISPLAY_NAME_LENGTH = 256
        private const val MAX_CURSOR_LENGTH = 1024
        private const val MODEL_PAGE_SIZE = 100
        private const val MAX_MODEL_PAGES = 10
        private const val MAX_MODELS = 500
        private const val MAX_PROMPT_BYTES = 64 * 1024
        private const val MAX_WORKSPACE_PATH_LENGTH = 1024
        private const val AGENT_INSTRUCTIONS =
            "Answer as a text-only chat assistant. Do not invoke tools, commands, web search, " +
                "file operations, sub-agents, or request user input."
    }
}
