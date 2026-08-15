package dev.alpine.codex.appserver.protocol

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class CodexRpcNotification(
    val method: String,
    val params: JSONObject,
    /** Non-null only for a locally detected transport/process terminal failure. */
    val clientFailureCode: CodexAppServerErrorCode? = null,
)

/** Minimal stable stdio JSONL client. JSON-RPC's `jsonrpc` member is omitted by App Server. */
class CodexRpcClient internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val reader = BoundedJsonLineReader(input, maxLineBytes)
    private val writer = BoundedJsonLineWriter(output, maxLineBytes)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ids = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val mutableNotifications = MutableSharedFlow<CodexRpcNotification>(
        extraBufferCapacity = NOTIFICATION_BUFFER,
    )
    val notifications: SharedFlow<CodexRpcNotification> = mutableNotifications.asSharedFlow()
    private val readerJob: Job = scope.launch { readLoop() }

    /** False after any terminal protocol/transport failure as well as an explicit close. */
    internal fun isOpen(): Boolean = !closed.get()

    suspend fun initialize(clientName: String, title: String, version: String): JSONObject {
        require(clientName.isNotBlank() && title.isNotBlank() && version.isNotBlank())
        if (!initialized.compareAndSet(false, true)) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        return try {
            request(
                "initialize",
                JSONObject()
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", clientName)
                            .put("title", title)
                            .put("version", version),
                    )
                    .put("capabilities", JSONObject().put("experimentalApi", false)),
            ).also { notify("initialized") }
        } catch (failure: Throwable) {
            initialized.set(false)
            throw failure
        }
    }

    suspend fun request(method: String, params: JSONObject? = null): JSONObject {
        if (closed.get() || method.isBlank()) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED)
        }
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<JSONObject>()
        check(pending.putIfAbsent(id, result) == null)
        try {
            val request = JSONObject().put("method", method).put("id", id)
            if (params != null) request.put("params", params)
            try {
                writer.write(request.toString())
            } catch (failure: CodexAppServerException) {
                failAll(failure.code)
                throw failure
            } catch (failure: Exception) {
                failAll(CodexAppServerErrorCode.PROCESS_EXITED)
                throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED, failure)
            }
            return try {
                withTimeout(requestTimeoutMs) { result.await() }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                // The server may already have applied the request. Make the transport terminal so
                // a late response or a new request cannot be correlated against uncertain state.
                failAll(CodexAppServerErrorCode.REQUEST_TIMEOUT)
                throw CodexAppServerException(CodexAppServerErrorCode.REQUEST_TIMEOUT, failure)
            }
        } finally {
            pending.remove(id, result)
        }
    }

    suspend fun notify(method: String, params: JSONObject? = null) {
        if (closed.get() || method.isBlank()) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED)
        }
        val message = JSONObject().put("method", method)
        if (params != null) message.put("params", params)
        try {
            writer.write(message.toString())
        } catch (failure: CodexAppServerException) {
            failAll(failure.code)
            throw failure
        } catch (failure: Exception) {
            failAll(CodexAppServerErrorCode.PROCESS_EXITED)
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED, failure)
        }
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get()) {
                val line = reader.readLine() ?: break
                val message = try {
                    JSONObject(line)
                } catch (failure: Exception) {
                    throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID, failure)
                }
                when {
                    message.has("id") && message.has("method") -> rejectServerRequest(message)
                    message.has("id") -> completeResponse(message)
                    message.has("method") -> emitNotification(message)
                    else -> throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
                }
            }
            if (!closed.get()) failAll(CodexAppServerErrorCode.PROCESS_EXITED)
        } catch (failure: CodexAppServerException) {
            failAll(failure.code)
        } catch (_: Throwable) {
            failAll(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
    }

    private fun completeResponse(message: JSONObject) {
        // App Server request IDs are integers. JSONObject.optLong would also coerce numeric
        // strings and truncate fractional numbers, which could correlate a malformed response to
        // an unrelated pending request.
        val id = when (val rawId = message.opt("id")) {
            is Int -> rawId.toLong()
            is Long -> rawId
            else -> throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        val deferred = pending[id]
            ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        val hasError = message.has("error")
        val hasResult = message.has("result")
        if (hasError == hasResult) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        if (hasError) {
            val error = message.opt("error") as? JSONObject
                ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            val code = when (val rawCode = error.opt("code")) {
                is Int -> rawCode.toLong()
                is Long -> rawCode
                else -> throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            }
            if (!pending.remove(id, deferred)) {
                throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            }
            deferred.completeExceptionally(
                CodexAppServerException(
                    if (code == -32001L) CodexAppServerErrorCode.SERVER_OVERLOADED
                    else CodexAppServerErrorCode.PROTOCOL_INVALID,
                ),
            )
            return
        }
        val result = message.optJSONObject("result")
            ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        if (!pending.remove(id, deferred)) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        deferred.complete(result)
    }

    private fun rejectServerRequest(message: JSONObject) {
        val id: Any = when (val rawId = message.opt("id")) {
            is Int, is Long -> rawId
            is String -> rawId.takeIf {
                it.isNotBlank() && it.length <= MAX_SERVER_REQUEST_ID_LENGTH &&
                    it.none(Char::isISOControl)
            } ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            else -> throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        val method = message.opt("method") as? String
            ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        if (method.isBlank() || method.length > MAX_METHOD_LENGTH || method.any(Char::isISOControl)) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        try {
            writer.write(
                JSONObject()
                    .put("id", id)
                    .put(
                        "error",
                        JSONObject()
                            .put("code", -32601)
                            .put("message", "Unsupported server request"),
                    )
                    .toString(),
            )
        } catch (failure: CodexAppServerException) {
            throw failure
        } catch (failure: Exception) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED, failure)
        }
    }

    private fun emitNotification(message: JSONObject) {
        val method = message.opt("method") as? String
            ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        if (method.isBlank()) throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        val params = message.opt("params") as? JSONObject
            ?: throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        if (!mutableNotifications.tryEmit(CodexRpcNotification(method, params))) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
    }

    private suspend fun failAll(code: CodexAppServerErrorCode) {
        if (!closed.compareAndSet(false, true)) return
        val failure = CodexAppServerException(code)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        mutableNotifications.emit(
            CodexRpcNotification(
                method = CLIENT_FAILURE_NOTIFICATION,
                params = JSONObject(),
                clientFailureCode = code,
            ),
        )
        // Closing stdin gives the native child EOF; closing stdout unblocks this reader. A
        // terminal channel must not leave a live but unusable App Server behind until reconnect.
        runCatching { output.close() }
        runCatching { input.close() }
        scope.cancel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failure = CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        runCatching { input.close() }
        runCatching { output.close() }
        readerJob.cancel()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES = 1024 * 1024
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        const val CLIENT_FAILURE_NOTIFICATION = "alpine/clientFailure"
        private const val NOTIFICATION_BUFFER = 256
        private const val MAX_SERVER_REQUEST_ID_LENGTH = 128
        private const val MAX_METHOD_LENGTH = 256
    }
}
