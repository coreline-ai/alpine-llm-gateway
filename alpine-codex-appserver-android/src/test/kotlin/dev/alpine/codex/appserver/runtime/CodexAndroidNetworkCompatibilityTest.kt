package dev.alpine.codex.appserver.runtime

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAndroidNetworkCompatibilityTest {
    @Test
    fun `process environment supplies Android CA and authenticated DNS bridge`() {
        val root = File("/private/test")
        val layout = CodexAppServerLayout(
            binary = File(root, "codex"),
            home = File(root, "home"),
            workspace = File(root, "workspace"),
            temporary = File(root, "tmp"),
            caBundle = File(root, "system-ca.pem"),
            httpsProxy = "http://codex:opaque@127.0.0.1:41000",
        )

        val environment = CodexProcessEnvironment.values(layout)

        assertEquals(layout.caBundle.absolutePath, environment["CODEX_CA_CERTIFICATE"])
        assertEquals(layout.caBundle.absolutePath, environment["SSL_CERT_FILE"])
        assertEquals(layout.httpsProxy, environment["HTTPS_PROXY"])
        assertEquals(layout.httpsProxy, environment["ALL_PROXY"])
        assertEquals("localhost,127.0.0.1,[::1]", environment["NO_PROXY"])
        assertFalse(environment.containsKey("HTTP_PROXY"))
    }

    @Test
    fun `CONNECT parser requires credentials allowlisted host and TLS port`() {
        val expected = basic("codex", "secret")
        val valid = request("auth.openai.com:443", String(expected, StandardCharsets.US_ASCII))

        assertEquals(
            ProxyTarget("auth.openai.com", 443),
            CodexLoopbackHttpsProxy.parseConnectRequest(valid, expected),
        )
        assertNull(
            CodexLoopbackHttpsProxy.parseConnectRequest(
                request("auth.openai.com:443", String(basic("codex", "wrong"))),
                expected,
            ),
        )
        assertNull(
            CodexLoopbackHttpsProxy.parseConnectRequest(
                request("openai.com.evil.test:443", String(expected)),
                expected,
            ),
        )
        assertNull(
            CodexLoopbackHttpsProxy.parseConnectRequest(
                request("chatgpt.com:80", String(expected)),
                expected,
            ),
        )
    }

    @Test
    fun `loopback proxy URI carries ephemeral credentials and closes cleanly`() {
        val proxy = CodexLoopbackHttpsProxy.start()
        try {
            val uri = URI(proxy.proxyUrl)
            assertEquals("http", uri.scheme)
            assertEquals("127.0.0.1", uri.host)
            assertTrue(uri.port > 0)
            assertTrue(uri.userInfo.startsWith("codex:"))
            assertTrue(uri.userInfo.length > "codex:".length + 20)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `CA PEM encoder emits only bounded certificate blocks`() {
        val encoded = CodexSystemTrustStore.encodePem(
            listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
        ).toString(StandardCharsets.US_ASCII)

        assertEquals(2, "-----BEGIN CERTIFICATE-----".toRegex().findAll(encoded).count())
        assertEquals(2, "-----END CERTIFICATE-----".toRegex().findAll(encoded).count())
        assertTrue(encoded.contains("AQID"))
        assertTrue(encoded.contains("BAUG"))
    }

    private fun basic(user: String, secret: String): ByteArray =
        "Basic ${Base64.getEncoder().encodeToString("$user:$secret".toByteArray())}"
            .toByteArray(StandardCharsets.US_ASCII)

    private fun request(target: String, authorization: String): ByteArray =
        "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Authorization: $authorization\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
}
