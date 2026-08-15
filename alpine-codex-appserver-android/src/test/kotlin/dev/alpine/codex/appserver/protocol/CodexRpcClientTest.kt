package dev.alpine.codex.appserver.protocol

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodexRpcClientTest {
    @Test
    fun `correlates response and publishes notification`() = runBlocking {
        Harness().use { harness ->
            val notification = async(start = CoroutineStart.UNDISPATCHED) {
                harness.client.notifications.first()
            }
            val server = thread(isDaemon = true) {
                val sent = JSONObject(harness.serverReader.readLine())
                assertEquals("account/read", sent.getString("method"))
                harness.serverWriter.println(
                    JSONObject()
                        .put("method", "account/updated")
                        .put("params", JSONObject().put("authMode", "chatgpt")),
                )
                harness.serverWriter.println(
                    JSONObject().put("id", sent.getLong("id")).put(
                        "result",
                        JSONObject().put("requiresOpenaiAuth", true),
                    ),
                )
            }
            assertEquals(
                true,
                harness.client.request("account/read", JSONObject()).getBoolean("requiresOpenaiAuth"),
            )
            assertEquals("account/updated", notification.await().method)
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `server request is rejected without exposing method payload`() {
        Harness().use { harness ->
            val rejectionRef = AtomicReference<JSONObject>()
            val server = thread(isDaemon = true) {
                harness.serverWriter.println(
                    JSONObject()
                        .put("id", 99)
                        .put("method", "item/commandExecution/requestApproval")
                        .put("params", JSONObject().put("secret", "must-not-return")),
                )
                rejectionRef.set(JSONObject(harness.serverReader.readLine()))
            }
            server.join(2_000)
            assertEquals(false, server.isAlive)
            val rejection = rejectionRef.get()
            assertEquals(99, rejection.getInt("id"))
            assertEquals(-32601, rejection.getJSONObject("error").getInt("code"))
            assertEquals(false, rejection.toString().contains("must-not-return"))
        }
    }

    @Test
    fun `correlates concurrent responses received out of order`() = runBlocking {
        Harness().use { harness ->
            val server = thread(isDaemon = true) {
                val first = JSONObject(harness.serverReader.readLine())
                val second = JSONObject(harness.serverReader.readLine())
                listOf(second, first).forEach { request ->
                    harness.serverWriter.println(
                        JSONObject()
                            .put("id", request.getLong("id"))
                            .put(
                                "result",
                                JSONObject().put("method", request.getString("method")),
                            ),
                    )
                }
            }
            val first = async(Dispatchers.IO) { harness.client.request("first") }
            val second = async(Dispatchers.IO) { harness.client.request("second") }
            assertEquals("first", first.await().getString("method"))
            assertEquals("second", second.await().getString("method"))
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `unknown response id fails pending request closed`() = runBlocking {
        Harness().use { harness ->
            val server = thread(isDaemon = true) {
                harness.serverReader.readLine()
                harness.serverWriter.println(
                    JSONObject().put("id", 999_999).put("result", JSONObject()),
                )
            }
            assertFailureCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
                harness.client.request("account/read")
            }
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `malformed result fails current request without waiting for timeout`() = runBlocking {
        Harness(requestTimeoutMs = 10_000).use { harness ->
            val server = thread(isDaemon = true) {
                val request = JSONObject(harness.serverReader.readLine())
                harness.serverWriter.println(
                    JSONObject().put("id", request.getLong("id")).put("result", "not-an-object"),
                )
            }
            assertFailureCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
                harness.client.request("account/read")
            }
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `string and fractional response ids never correlate by coercion`() = runBlocking {
        listOf<Any>("1", 1.5).forEach { malformedId ->
            Harness(requestTimeoutMs = 10_000).use { harness ->
                val server = thread(isDaemon = true) {
                    harness.serverReader.readLine()
                    harness.serverWriter.println(
                        JSONObject().put("id", malformedId).put("result", JSONObject()),
                    )
                }
                assertFailureCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
                    harness.client.request("account/read")
                }
                server.join(2_000)
                assertEquals(false, server.isAlive)
                assertEquals(false, harness.client.isOpen())
            }
        }
    }

    @Test
    fun `stable overload error maps without retaining raw server payload`() = runBlocking {
        Harness().use { harness ->
            val server = thread(isDaemon = true) {
                val request = JSONObject(harness.serverReader.readLine())
                harness.serverWriter.println(
                    JSONObject()
                        .put("id", request.getLong("id"))
                        .put(
                            "error",
                            JSONObject()
                                .put("code", -32001)
                                .put("message", "raw upstream detail must not escape"),
                        ),
                )
            }
            val failure = runCatching { harness.client.request("model/list") }.exceptionOrNull()
                as CodexAppServerException
            assertEquals(CodexAppServerErrorCode.SERVER_OVERLOADED, failure.code)
            assertEquals(CodexAppServerErrorCode.SERVER_OVERLOADED.name, failure.message)
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `malformed error code and notification params close protocol`() = runBlocking {
        listOf<(JSONObject, Long) -> JSONObject>(
            { _, id ->
                JSONObject().put("id", id).put(
                    "error",
                    JSONObject().put("code", "-32001").put("message", "must-not-coerce"),
                )
            },
            { _, _ -> JSONObject().put("method", "account/updated") },
            { _, id ->
                JSONObject()
                    .put("id", id)
                    .put("result", JSONObject())
                    .put("error", JSONObject().put("code", -32001))
            },
        ).forEach { malformed ->
            Harness(requestTimeoutMs = 10_000).use { harness ->
                val server = thread(isDaemon = true) {
                    val request = JSONObject(harness.serverReader.readLine())
                    harness.serverWriter.println(malformed(request, request.getLong("id")))
                }
                assertFailureCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
                    harness.client.request("account/read")
                }
                server.join(2_000)
                assertEquals(false, server.isAlive)
                assertEquals(false, harness.client.isOpen())
            }
        }
    }

    @Test
    fun `malformed server request id or method is never echoed`() = runBlocking {
        listOf(
            JSONObject().put("id", JSONObject()).put("method", "item/tool/request"),
            JSONObject().put("id", 9).put("method", 7),
        ).forEach { malformed ->
            Harness().use { harness ->
                val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.client.notifications.first { it.clientFailureCode != null }
                }
                harness.serverWriter.println(malformed)
                assertEquals(
                    CodexAppServerErrorCode.PROTOCOL_INVALID,
                    terminal.await().clientFailureCode,
                )
                assertEquals(false, harness.client.isOpen())
                assertFailureCode(CodexAppServerErrorCode.PROCESS_EXITED) {
                    harness.client.request("must-not-run")
                }
            }
        }
    }

    @Test
    fun `request write failure is stable and terminal`() = runBlocking {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) = throw IOException("closed pipe detail")
        }
        val client = CodexRpcClient(clientInput, failingOutput, requestTimeoutMs = 10_000)
        try {
            assertFailureCode(CodexAppServerErrorCode.PROCESS_EXITED) {
                client.request("account/read")
            }
            assertEquals(false, client.isOpen())
        } finally {
            client.close()
            serverToClient.close()
        }
    }

    @Test
    fun `request timeout has stable closed error and late reply closes session`() = runBlocking {
        Harness(requestTimeoutMs = 50).use { harness ->
            val server = thread(isDaemon = true) {
                val request = JSONObject(harness.serverReader.readLine())
                Thread.sleep(100)
                harness.serverWriter.println(
                    JSONObject().put("id", request.getLong("id")).put("result", JSONObject()),
                )
            }
            assertFailureCode(CodexAppServerErrorCode.REQUEST_TIMEOUT) {
                harness.client.request("slow")
            }
            assertEquals(false, harness.client.isOpen())
            assertFailureCode(CodexAppServerErrorCode.PROCESS_EXITED) {
                harness.client.request("must-not-replay")
            }
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    @Test
    fun `eof fails pending request and publishes closed client failure`() = runBlocking {
        Harness().use { harness ->
            val failureNotification = async(start = CoroutineStart.UNDISPATCHED) {
                harness.client.notifications.first { it.clientFailureCode != null }
            }
            val server = thread(isDaemon = true) {
                harness.serverReader.readLine()
                harness.serverWriter.close()
            }
            assertFailureCode(CodexAppServerErrorCode.PROCESS_EXITED) {
                harness.client.request("account/read")
            }
            assertEquals(
                CodexAppServerErrorCode.PROCESS_EXITED,
                failureNotification.await().clientFailureCode,
            )
            server.join(2_000)
            assertEquals(false, server.isAlive)
        }
    }

    private suspend fun assertFailureCode(
        code: CodexAppServerErrorCode,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected $code")
        } catch (failure: CodexAppServerException) {
            assertEquals(code, failure.code)
        }
    }

    private class Harness(requestTimeoutMs: Long = 2_000) : Closeable {
        private val serverToClient = PipedOutputStream()
        private val clientInput = PipedInputStream(serverToClient)
        private val clientToServer = PipedOutputStream()
        private val serverInput = PipedInputStream(clientToServer)
        val serverReader = BufferedReader(InputStreamReader(serverInput))
        val serverWriter = PrintWriter(serverToClient, true)
        val client = CodexRpcClient(clientInput, clientToServer, requestTimeoutMs = requestTimeoutMs)

        override fun close() {
            client.close()
            serverWriter.close()
            clientToServer.close()
            serverToClient.close()
            serverReader.close()
        }
    }
}
