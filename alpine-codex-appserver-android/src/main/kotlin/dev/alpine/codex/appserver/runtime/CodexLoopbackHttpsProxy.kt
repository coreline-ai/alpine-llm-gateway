package dev.alpine.codex.appserver.runtime

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.Closeable
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Authenticated loopback CONNECT bridge. The static Linux binary cannot use Android netd DNS, so
 * Java resolves a strict OpenAI host allowlist and tunnels TLS without terminating or inspecting it.
 */
internal class CodexLoopbackHttpsProxy private constructor(
    private val server: ServerSocket,
    secret: String,
    private val expectedAuthorization: ByteArray,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val permits = Semaphore(MAX_CONNECTIONS)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    val proxyUrl: String = "http://$PROXY_USER:$secret@$LOOPBACK:${server.localPort}"
    private val acceptThread: Thread

    init {
        acceptThread = thread(start = true, isDaemon = true, name = "codex-proxy-accept") {
            acceptLoop()
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                if (closed.get()) return
                continue
            }
            if (!permits.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            clients += client
            thread(start = true, isDaemon = true, name = "codex-proxy-client") {
                try {
                    handle(client)
                } catch (_: Exception) {
                    // Either tunnel half may close first during a normal TLS/HTTP shutdown.
                } finally {
                    clients -= client
                    runCatching { client.close() }
                    permits.release()
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val request = readHeaders(client.getInputStream()) ?: return
        val target = parseConnectRequest(request, expectedAuthorization) ?: return
        val upstream = connectPublicTarget(target.host, target.port) ?: return
        clients += upstream
        try {
            client.getOutputStream().write(CONNECTED_RESPONSE)
            client.getOutputStream().flush()
            client.soTimeout = 0
            upstream.soTimeout = 0
            val clientToUpstream = thread(
                start = true,
                isDaemon = true,
                name = "codex-proxy-upload",
            ) {
                try {
                    client.getInputStream().copyBounded(upstream.getOutputStream())
                } catch (_: Exception) {
                    // Normal when the remote half closes first.
                } finally {
                    runCatching { upstream.close() }
                    runCatching { client.close() }
                }
            }
            try {
                upstream.getInputStream().copyBounded(client.getOutputStream())
            } catch (_: Exception) {
                // Normal when the client half closes first.
            } finally {
                runCatching { upstream.close() }
                runCatching { client.close() }
                clientToUpstream.join(THREAD_JOIN_TIMEOUT_MS)
            }
        } finally {
            clients -= upstream
            runCatching { upstream.close() }
        }
    }

    private fun connectPublicTarget(host: String, port: Int): Socket? {
        val addresses = try {
            InetAddress.getAllByName(host).filter(::isPublicAddress)
        } catch (_: Exception) {
            emptyList()
        }
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        synchronized(clients) { clients.toList() }.forEach { socket ->
            runCatching { socket.close() }
        }
        acceptThread.interrupt()
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val PROXY_USER = "codex"
        private const val AUTHORIZATION_PREFIX = "Basic "
        private const val MAX_CONNECTIONS = 12
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val THREAD_JOIN_TIMEOUT_MS = 1_000L
        private val CONNECTED_RESPONSE =
            "HTTP/1.1 200 Connection Established\r\nConnection: keep-alive\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)

        fun start(): CodexLoopbackHttpsProxy = try {
            val secretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
            val credentials = Base64.getEncoder().encodeToString(
                "$PROXY_USER:$secret".toByteArray(StandardCharsets.US_ASCII),
            )
            val expected = "$AUTHORIZATION_PREFIX$credentials"
                .toByteArray(StandardCharsets.US_ASCII)
            val server = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName(LOOPBACK))
            CodexLoopbackHttpsProxy(server, secret, expected)
        } catch (failure: Exception) {
            throw CodexAppServerException(CodexAppServerErrorCode.NETWORK_BRIDGE_FAILED, failure)
        }

        internal fun parseConnectRequest(
            headers: ByteArray,
            expectedAuthorization: ByteArray,
        ): ProxyTarget? {
            val text = headers.toString(StandardCharsets.US_ASCII)
            if (!text.endsWith("\r\n\r\n")) return null
            val lines = text.dropLast(4).split("\r\n")
            val request = lines.firstOrNull()?.split(' ') ?: return null
            if (request.size != 3 || request[0] != "CONNECT" || request[2] != "HTTP/1.1") return null
            val separator = request[1].lastIndexOf(':')
            if (separator <= 0) return null
            val host = request[1].substring(0, separator).lowercase()
            val port = request[1].substring(separator + 1).toIntOrNull() ?: return null
            if (port != 443 || !isAllowedHost(host)) return null
            val authorization = lines.drop(1).firstNotNullOfOrNull { line ->
                val index = line.indexOf(':')
                if (index <= 0 || !line.substring(0, index).equals("Proxy-Authorization", true)) {
                    null
                } else {
                    line.substring(index + 1).trim().toByteArray(StandardCharsets.US_ASCII)
                }
            } ?: return null
            if (!MessageDigest.isEqual(expectedAuthorization, authorization)) return null
            return ProxyTarget(host, port)
        }

        private fun readHeaders(input: InputStream): ByteArray? {
            val bytes = ArrayList<Byte>(512)
            var matched = 0
            val delimiter = byteArrayOf(13, 10, 13, 10)
            while (bytes.size < MAX_HEADER_BYTES) {
                val value = input.read()
                if (value < 0) return null
                val byte = value.toByte()
                bytes += byte
                matched = if (byte == delimiter[matched]) matched + 1 else if (byte == delimiter[0]) 1 else 0
                if (matched == delimiter.size) return bytes.toByteArray()
            }
            return null
        }

        private fun isAllowedHost(host: String): Boolean {
            if (host.isBlank() || host.length > 253 || host.startsWith('.') || host.endsWith('.')) return false
            if (!host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return false
            return host == "openai.com" || host.endsWith(".openai.com") ||
                host == "chatgpt.com" || host.endsWith(".chatgpt.com")
        }

        private fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            if (address is Inet6Address) {
                val first = address.address.first().toInt() and 0xff
                if (first and 0xfe == 0xfc) return false
            }
            return true
        }
    }

    private fun InputStream.copyBounded(output: java.io.OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (!closed.get()) {
            val count = read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }
}

internal data class ProxyTarget(val host: String, val port: Int)
