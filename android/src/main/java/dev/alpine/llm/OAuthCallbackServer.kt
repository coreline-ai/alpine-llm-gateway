package dev.alpine.llm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** Loopback callback server used by browser-based OAuth providers. */
class OAuthCallbackServer(
    private val requestedPort: Int,
    private val redirectPath: String,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onCallback: (Callback) -> Unit,
) {
    data class Callback(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String? = null,
    )

    @Volatile
    private var running = false
    private var socket: ServerSocket? = null
    var boundPort: Int = requestedPort
        private set

    fun start() {
        val ports = listOf(requestedPort) + fallbackPorts
        for (port in ports) {
            try {
                socket = ServerSocket(port, 1, InetAddress.getByName(LOOPBACK))
                boundPort = requireNotNull(socket).localPort
                break
            } catch (_: java.net.BindException) {
                // Try the next configured port.
            }
        }
        if (socket == null) {
            running = false
            error("OAuth callback ports are unavailable: $ports")
        }
        running = true
        Thread({ acceptLoop() }, "alpine-oauth-callback").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                socket?.accept() ?: return
            } catch (_: Exception) {
                return
            }
            runCatching { handle(client) }.also { runCatching { client.close() } }
        }
    }

    private fun handle(client: java.net.Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val target = requestLine.split(" ").getOrNull(1) ?: return
        val uri = URI("http://$LOOPBACK$target")
        if (uri.path != redirectPath) return

        val params = uri.rawQuery.orEmpty().split("&")
            .filter { it.isNotEmpty() }
            .associate {
                val pair = it.split("=", limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to
                    URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
        val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p></body></html>"
        val response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n$html"
        client.getOutputStream().use { it.write(response.toByteArray(StandardCharsets.UTF_8)) }
        onCallback(
            Callback(
                code = params["code"],
                state = params["state"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
        )
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
