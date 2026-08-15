package dev.alpine.codex.appserver.protocol

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class BoundedJsonLineReader(
    private val input: InputStream,
    private val maxLineBytes: Int,
) {
    init {
        require(maxLineBytes in 1..MAX_ALLOWED_LINE_BYTES)
    }

    fun readLine(): String? {
        val bytes = ByteArrayOutputStream(minOf(maxLineBytes, 8 * 1024))
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            }
            if (value == '\n'.code) break
            if (value == 0) {
                throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
            }
            if (bytes.size() >= maxLineBytes) {
                throw CodexAppServerException(CodexAppServerErrorCode.RESPONSE_TOO_LARGE)
            }
            bytes.write(value)
        }
        val raw = bytes.toByteArray().let { value ->
            if (value.lastOrNull() == '\r'.code.toByte()) value.copyOf(value.size - 1) else value
        }
        if (raw.isEmpty()) throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (failure: Exception) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID, failure)
        }
    }

    companion object {
        const val MAX_ALLOWED_LINE_BYTES = 4 * 1024 * 1024
    }
}

internal class BoundedJsonLineWriter(
    private val output: OutputStream,
    private val maxLineBytes: Int,
) {
    private val lock = Any()

    fun write(line: String) {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > maxLineBytes || bytes.any { it == 0.toByte() }) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROTOCOL_INVALID)
        }
        synchronized(lock) {
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
        }
    }
}
