package dev.alpine.codex.appserver

import android.content.Context
import dev.alpine.codex.appserver.pack.CodexAppServerArtifact
import dev.alpine.codex.appserver.runtime.CodexAppServerLayouts
import dev.alpine.codex.appserver.runtime.CodexLoopbackHttpsProxy
import dev.alpine.codex.appserver.runtime.CodexNativeProcess
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CodexAppServerConnection(
    val client: CodexAppServerClient,
    val auth: CodexAuthController,
)

/** App-owned singleton boundary. It never uses or stops the Alpine Runtime session. */
class CodexAppServerRuntime(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val connectionLock = Mutex()
    private val stateLock = Any()
    private val closeGeneration = AtomicLong(0)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nativeProcess: CodexNativeProcess? = null
    private var client: CodexAppServerClient? = null
    private var authController: CodexAuthController? = null
    private var networkBridge: CodexLoopbackHttpsProxy? = null

    suspend fun connect(): CodexAppServerClient = connectionLock.withLock { connectLocked() }

    /**
     * Returns a process-lifetime auth controller. Activity recreation only replaces observers;
     * it never discards a pending browser callback or starts a second login action.
     */
    suspend fun connectWithAuth(): CodexAppServerConnection = connectionLock.withLock {
        val connected = connectLocked()
        val auth = synchronized(stateLock) {
            check(client === connected && nativeProcess?.isAlive() == true)
            authController ?: CodexAuthController(connected, runtimeScope).also {
                authController = it
            }
        }
        CodexAppServerConnection(connected, auth)
    }

    private suspend fun connectLocked(): CodexAppServerClient {
        synchronized(stateLock) {
            client?.takeIf { nativeProcess?.isAlive() == true }
        }?.let { return it }
        detachProcess()?.close()
        val generation = closeGeneration.get()
        val layout = CodexAppServerLayouts.prepare(appContext, networkBridge().proxyUrl)
        val process = CodexNativeProcess.launch(layout)
        val next = CodexAppServerClient(process.rpc, layout.workspace.absolutePath)
        try {
            next.initialize(CodexAppServerArtifact.VERSION)
        } catch (failure: Throwable) {
            process.close()
            throw failure
        }
        val accepted = synchronized(stateLock) {
            if (closeGeneration.get() != generation) {
                false
            } else {
                nativeProcess = process
                client = next
                true
            }
        }
        if (!accepted) {
            process.close()
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED)
        }
        return next
    }

    suspend fun restart(): CodexAppServerClient = connectionLock.withLock {
        detachProcess()?.close()
        val generation = closeGeneration.get()
        val layout = CodexAppServerLayouts.prepare(appContext, networkBridge().proxyUrl)
        val process = CodexNativeProcess.launch(layout)
        val next = CodexAppServerClient(process.rpc, layout.workspace.absolutePath)
        try {
            next.initialize(CodexAppServerArtifact.VERSION)
        } catch (failure: Throwable) {
            process.close()
            throw failure
        }
        val accepted = synchronized(stateLock) {
            if (closeGeneration.get() != generation) {
                false
            } else {
                nativeProcess = process
                client = next
                true
            }
        }
        if (!accepted) {
            process.close()
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_EXITED)
        }
        return@withLock next
    }

    fun isConnected(): Boolean = synchronized(stateLock) {
        client != null && nativeProcess?.isAlive() == true
    }

    override fun close() {
        closeGeneration.incrementAndGet()
        detachProcess()?.close()
        synchronized(stateLock) {
            networkBridge?.close()
            networkBridge = null
        }
        runtimeScope.cancel()
    }

    private fun networkBridge(): CodexLoopbackHttpsProxy = synchronized(stateLock) {
        networkBridge ?: CodexLoopbackHttpsProxy.start().also { networkBridge = it }
    }

    private fun detachProcess(): CodexNativeProcess? = synchronized(stateLock) {
        authController?.close()
        authController = null
        val detached = nativeProcess
        nativeProcess = null
        client = null
        detached
    }
}
