package dev.alpine.codex.appserver.protocol

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BoundedJsonLinesTest {
    @Test
    fun `reads utf8 line and clean eof`() {
        val reader = BoundedJsonLineReader(
            ByteArrayInputStream("{\"text\":\"삼성\"}\n".toByteArray()),
            maxLineBytes = 64,
        )
        assertEquals("{\"text\":\"삼성\"}", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `reads multiple frames with crlf and unicode byte boundaries`() {
        val input = "{\"first\":1}\r\n{\"text\":\"한글\"}\n".toByteArray()
        val reader = BoundedJsonLineReader(ByteArrayInputStream(input), maxLineBytes = 64)
        assertEquals("{\"first\":1}", reader.readLine())
        assertEquals("{\"text\":\"한글\"}", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `rejects oversized and partial eof`() {
        assertCode(CodexAppServerErrorCode.RESPONSE_TOO_LARGE) {
            BoundedJsonLineReader(ByteArrayInputStream("12345\n".toByteArray()), 4).readLine()
        }
        assertCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
            BoundedJsonLineReader(ByteArrayInputStream("{}".toByteArray()), 4).readLine()
        }
    }

    @Test
    fun `writer appends exactly one newline and enforces cap`() {
        val output = ByteArrayOutputStream()
        BoundedJsonLineWriter(output, 8).write("{}")
        assertEquals("{}\n", output.toString(Charsets.UTF_8.name()))
        assertCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
            BoundedJsonLineWriter(output, 2).write("123")
        }
    }

    @Test
    fun `rejects malformed utf8 nul and empty frame`() {
        assertCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
            BoundedJsonLineReader(
                ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte())),
                8,
            ).readLine()
        }
        assertCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
            BoundedJsonLineReader(ByteArrayInputStream(byteArrayOf(0, '\n'.code.toByte())), 8)
                .readLine()
        }
        assertCode(CodexAppServerErrorCode.PROTOCOL_INVALID) {
            BoundedJsonLineReader(ByteArrayInputStream("\n".toByteArray()), 8).readLine()
        }
    }

    private fun assertCode(code: CodexAppServerErrorCode, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (failure: CodexAppServerException) {
            assertEquals(code, failure.code)
        }
    }
}
