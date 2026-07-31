package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarGzExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsExecutableFileWithTarMode() {
        val destination = temporaryFolder.newFolder("rootfs")
        val archive = tarGz("bin/tool", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101)

        TarGzExtractor.extract(
            ByteArrayInputStream(archive),
            destination,
            maxExtractedBytes = 1024,
            maxEntries = 10,
        )

        val tool = destination.resolve("bin/tool")
        assertEquals("#!/bin/sh\n", tool.readText())
        assertTrue(Files.isExecutable(tool.toPath()))
    }

    @Test
    fun rejectsTraversalEntry() {
        val destination = temporaryFolder.newFolder("safe-root")
        val archive = tarGz("../escape", "bad".toByteArray(), mode = 0b110_100_100)

        assertThrows(IllegalArgumentException::class.java) {
            TarGzExtractor.extract(
                ByteArrayInputStream(archive),
                destination,
                maxExtractedBytes = 1024,
                maxEntries = 10,
            )
        }
        assertTrue(!requireNotNull(destination.parentFile).resolve("escape").exists())
    }

    @Test
    fun enforcesExtractedSizeLimit() {
        val destination = temporaryFolder.newFolder("limited-root")
        val archive = tarGz("large", ByteArray(32), mode = 0b110_100_100)

        assertThrows(IllegalArgumentException::class.java) {
            TarGzExtractor.extract(
                ByteArrayInputStream(archive),
                destination,
                maxExtractedBytes = 16,
                maxEntries = 10,
            )
        }
    }

    private fun tarGz(path: String, contents: ByteArray, mode: Int): ByteArray {
        val tar = ByteArrayOutputStream()
        val header = ByteArray(512)
        putString(header, 0, 100, path)
        putOctal(header, 100, 8, mode.toLong())
        putOctal(header, 108, 8, 0)
        putOctal(header, 116, 8, 0)
        putOctal(header, 124, 12, contents.size.toLong())
        putOctal(header, 136, 12, 0)
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        putString(header, 257, 6, "ustar")
        putString(header, 263, 2, "00")
        val checksum = header.sumOf { it.toInt() and 0xff }
        val checksumText = checksum.toString(8).padStart(6, '0')
        putString(header, 148, 6, checksumText)
        header[154] = 0
        header[155] = ' '.code.toByte()
        tar.write(header)
        tar.write(contents)
        val padding = (512 - contents.size % 512) % 512
        tar.write(ByteArray(padding))
        tar.write(ByteArray(1024))

        return ByteArrayOutputStream().also { compressed ->
            GZIPOutputStream(compressed).use { it.write(tar.toByteArray()) }
        }.toByteArray()
    }

    private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun putOctal(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: Long,
    ) {
        val text = value.toString(8).padStart(length - 1, '0')
        putString(target, offset, length - 1, text)
        target[offset + length - 1] = 0
    }
}
