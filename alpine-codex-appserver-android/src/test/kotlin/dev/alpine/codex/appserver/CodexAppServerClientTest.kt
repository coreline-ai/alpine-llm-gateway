package dev.alpine.codex.appserver

import dev.alpine.codex.appserver.protocol.CodexRpcClient
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerClientTest {
    @Test
    fun `credential refresh remains owned by official account read`() = runBlocking {
        Harness().use { harness ->
            val state = harness.respondWhile(
                JSONObject()
                    .put("requiresOpenaiAuth", true)
                    .put("account", JSONObject().put("type", "chatgpt")),
                requestAssertion = { request ->
                    assertEquals("account/read", request.getString("method"))
                    assertEquals(true, request.getJSONObject("params").getBoolean("refreshToken"))
                },
            ) { harness.app.refreshAccountState() }
            assertEquals(CodexAccountState.CHATGPT, state)
        }
    }

    @Test
    fun `browser login accepts pinned issuer and rejects lookalike host`() = runBlocking {
        Harness().use { harness ->
            val login = harness.respondWhile(
                JSONObject()
                    .put("type", "chatgpt")
                    .put("loginId", "login-1")
                    .put("authUrl", "https://auth.openai.com/oauth/authorize?state=opaque"),
            ) { harness.app.startBrowserLogin() }
            assertEquals("auth.openai.com", login.authorizationUri.host)

            val rejected = harness.respondWhile(
                JSONObject()
                    .put("type", "chatgpt")
                    .put("loginId", "login-2")
                    .put("authUrl", "https://auth.openai.com.evil.test/auth"),
            ) { runCatching { harness.app.startBrowserLogin() } }
            val failure = rejected.exceptionOrNull()
                ?: fail("Expected rejected auth URL")
            assertEquals(
                CodexAppServerErrorCode.AUTH_URL_REJECTED,
                (failure as CodexAppServerException).code,
            )
        }
    }

    @Test
    fun `browser login rejects unsafe uri components`() = runBlocking {
        val rejectedUrls = listOf(
            "http://auth.openai.com/oauth/authorize",
            "https://user@auth.openai.com/oauth/authorize",
            "https://auth.openai.com:444/oauth/authorize",
            "https://auth.openai.com/oauth/authorize#fragment",
        )
        Harness().use { harness ->
            rejectedUrls.forEachIndexed { index, url ->
                val rejected = harness.respondWhile(
                    JSONObject()
                        .put("type", "chatgpt")
                        .put("loginId", "login-$index")
                        .put("authUrl", url),
                ) { runCatching { harness.app.startBrowserLogin() } }
                val failure = rejected.exceptionOrNull()
                    ?: fail("Expected rejected auth URL")
                assertEquals(
                    CodexAppServerErrorCode.AUTH_URL_REJECTED,
                    (failure as CodexAppServerException).code,
                )
            }
        }
    }

    @Test
    fun `model list is bounded deduplicated and excludes hidden`() = runBlocking {
        Harness().use { harness ->
            val models = harness.respondWhile(
                JSONObject()
                    .put(
                        "data",
                        JSONArray()
                            .put(model("gpt-5.6", false, true))
                            .put(model("hidden", true, false))
                            .put(model("gpt-5.6", false, false)),
                    )
                    .put("nextCursor", JSONObject.NULL),
            ) { harness.app.models() }
            assertEquals(listOf("gpt-5.6"), models.map(CodexModel::id))
        }
    }

    @Test
    fun `used response fields never accept JSONObject coercion`() = runBlocking {
        Harness().use { harness ->
            val accountFailure = harness.respondWhile(JSONObject()) {
                runCatching { harness.app.accountState() }
            }.exceptionOrNull() as CodexAppServerException
            assertEquals(CodexAppServerErrorCode.PROTOCOL_INVALID, accountFailure.code)

            val loginFailure = harness.respondWhile(
                JSONObject()
                    .put("type", "chatgpt")
                    .put("loginId", 123)
                    .put("authUrl", "https://auth.openai.com/oauth/authorize"),
            ) { runCatching { harness.app.startBrowserLogin() } }
                .exceptionOrNull() as CodexAppServerException
            assertEquals(CodexAppServerErrorCode.PROTOCOL_INVALID, loginFailure.code)

            val modelFailure = harness.respondWhile(
                JSONObject().put(
                    "data",
                    JSONArray().put(
                        JSONObject()
                            .put("model", "gpt-5.6")
                            .put("displayName", "GPT")
                            .put("hidden", "false")
                            .put("isDefault", true),
                    ),
                ),
            ) { runCatching { harness.app.models() } }
                .exceptionOrNull() as CodexAppServerException
            assertEquals(CodexAppServerErrorCode.PROTOCOL_INVALID, modelFailure.code)
        }
    }

    @Test
    fun `login cancellation validates the stable response status`() = runBlocking {
        Harness().use { harness ->
            harness.respondWhile(JSONObject().put("status", "notFound")) {
                harness.app.cancelLogin("login-1")
            }
            val failure = harness.respondWhile(JSONObject().put("status", "unexpected")) {
                runCatching { harness.app.cancelLogin("login-2") }
            }.exceptionOrNull() as CodexAppServerException
            assertEquals(CodexAppServerErrorCode.PROTOCOL_INVALID, failure.code)
        }
    }

    private fun model(id: String, hidden: Boolean, isDefault: Boolean) = JSONObject()
        .put("model", id)
        .put("displayName", id)
        .put("hidden", hidden)
        .put("isDefault", isDefault)

    private class Harness : Closeable {
        private val serverToClient = PipedOutputStream()
        private val clientInput = PipedInputStream(serverToClient)
        private val clientToServer = PipedOutputStream()
        private val serverInput = PipedInputStream(clientToServer)
        private val reader = BufferedReader(InputStreamReader(serverInput))
        private val writer = PrintWriter(serverToClient, true)
        private val serverExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fake-codex-appserver").apply { isDaemon = true }
        }
        private val rpc = CodexRpcClient(clientInput, clientToServer, requestTimeoutMs = 2_000)
        val app = CodexAppServerClient(rpc)

        suspend fun <T> respondWhile(
            result: JSONObject,
            requestAssertion: (JSONObject) -> Unit = {},
            request: suspend () -> T,
        ): T {
            val requestFinished = CountDownLatch(1)
            val server = serverExecutor.submit {
                val message = JSONObject(reader.readLine())
                requestAssertion(message)
                writer.println(JSONObject().put("id", message.getLong("id")).put("result", result))
                requestFinished.await()
            }
            return try {
                request()
            } finally {
                requestFinished.countDown()
                server.get(2, TimeUnit.SECONDS)
            }
        }

        override fun close() {
            rpc.close()
            serverExecutor.shutdownNow()
            writer.close()
            clientToServer.close()
            serverToClient.close()
            reader.close()
        }
    }
}
