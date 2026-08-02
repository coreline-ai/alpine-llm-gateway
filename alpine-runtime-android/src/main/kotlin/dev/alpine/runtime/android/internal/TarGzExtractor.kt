package dev.alpine.runtime.android.internal

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/** Minimal tar.gz extractor for app-private rootfs installation. */
internal object TarGzExtractor {
    private const val BLOCK_SIZE = 512

    fun extract(
        input: InputStream,
        destination: File,
        maxExtractedBytes: Long,
        maxEntries: Int,
        isCancelled: () -> Boolean = { false },
    ) {
        GZIPInputStream(BufferedInputStream(input)).use { gzip ->
            extractTar(gzip, destination, maxExtractedBytes, maxEntries, isCancelled)
        }
    }

    private fun extractTar(
        input: InputStream,
        destination: File,
        maxExtractedBytes: Long,
        maxEntries: Int,
        isCancelled: () -> Boolean,
    ) {
        val root = destination.canonicalFile
        val header = ByteArray(BLOCK_SIZE)
        var zeroBlocks = 0
        var extractedBytes = 0L
        var entries = 0
        var extendedPath: String? = null
        var extendedLinkPath: String? = null
        while (true) {
            throwIfCancelled(isCancelled)
            readFully(input, header, isCancelled)
            if (header.all { it.toInt() == 0 }) {
                zeroBlocks++
                if (zeroBlocks == 2) return
                continue
            }
            zeroBlocks = 0
            validateChecksum(header)
            entries++
            require(entries <= maxEntries) { "rootfs tar contains too many entries" }
            val shortName = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val size = parseOctal(field(header, 124, 12))
            val mode = parseOctal(field(header, 100, 8)).toInt()
            require(size >= 0 && size <= maxExtractedBytes - extractedBytes) {
                "rootfs tar exceeds the extracted size limit"
            }
            extractedBytes += size
            val type = header[156].toInt().toChar()
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                val metadata = readBytesExactly(input, size, isCancelled)
                skipPadding(input, size, isCancelled)
                when (type) {
                    'x' -> {
                        val values = parsePax(metadata)
                        extendedPath = values["path"] ?: extendedPath
                        extendedLinkPath = values["linkpath"] ?: extendedLinkPath
                    }
                    'L' -> extendedPath = metadata.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    'K' -> extendedLinkPath = metadata.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    // Global PAX headers contain ownership/time defaults that
                    // are intentionally not applied in an app-private rootfs.
                    'g' -> Unit
                }
                continue
            }
            val headerName = if (prefix.isBlank()) shortName else "$prefix/$shortName"
            val name = extendedPath ?: headerName
            val linkName = extendedLinkPath ?: field(header, 157, 100)
            extendedPath = null
            extendedLinkPath = null
            val target = safeTarget(root, name.trimEnd('/'))
            when (type) {
                '\u0000', '0' -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        copyExactly(input, output, size, isCancelled)
                    }
                    applyMode(target, mode)
                }
                '5' -> {
                    require(target.mkdirs() || target.isDirectory) {
                        "cannot create rootfs directory $name"
                    }
                    applyMode(target, mode)
                    skipExactly(input, size, isCancelled)
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    runCatching {
                        Files.deleteIfExists(target.toPath())
                        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                    }.getOrElse { throw IllegalStateException("cannot create rootfs symlink $name", it) }
                    skipExactly(input, size, isCancelled)
                }
                '1' -> {
                    val linkTarget = safeTarget(root, linkName)
                    target.parentFile?.mkdirs()
                    runCatching {
                        Files.deleteIfExists(target.toPath())
                        Files.createLink(target.toPath(), linkTarget.toPath())
                    }.getOrElse { throw IllegalStateException("cannot create rootfs hard link $name", it) }
                    skipExactly(input, size, isCancelled)
                }
                '3', '4', '6' -> {
                    // /dev is bind-mounted by PRoot; special files cannot be
                    // safely recreated by an unprivileged Android process.
                    skipExactly(input, size, isCancelled)
                }
                else -> {
                    skipExactly(input, size, isCancelled)
                    throw IllegalArgumentException("unsupported rootfs tar entry type '$type': $name")
                }
            }
            skipPadding(input, size, isCancelled)
        }
    }

    private fun safeTarget(root: File, name: String): File {
        require(name.isNotEmpty()) { "rootfs tar entry has an empty path" }
        val target = File(root, name).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "unsafe rootfs tar entry: $name"
        }
        return target
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        return header.copyOfRange(offset, offset + length)
            .toString(Charsets.US_ASCII)
            .trim('\u0000', ' ')
    }

    private fun parseOctal(value: String): Long = value.trim().ifEmpty { "0" }.toLong(8)

    private fun validateChecksum(header: ByteArray) {
        val expected = parseOctal(field(header, 148, 8))
        var actual = 0L
        for (index in header.indices) {
            actual += if (index in 148 until 156) {
                ' '.code
            } else {
                header[index].toInt() and 0xff
            }
        }
        require(expected == actual) { "invalid rootfs tar header checksum" }
    }

    private fun applyMode(file: File, mode: Int) {
        val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
        if (mode and 0b100_000_000 != 0) permissions += PosixFilePermission.OWNER_READ
        if (mode and 0b010_000_000 != 0) permissions += PosixFilePermission.OWNER_WRITE
        if (mode and 0b001_000_000 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0b000_100_000 != 0) permissions += PosixFilePermission.GROUP_READ
        if (mode and 0b000_010_000 != 0) permissions += PosixFilePermission.GROUP_WRITE
        if (mode and 0b000_001_000 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0b000_000_100 != 0) permissions += PosixFilePermission.OTHERS_READ
        if (mode and 0b000_000_010 != 0) permissions += PosixFilePermission.OTHERS_WRITE
        if (mode and 0b000_000_001 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, isCancelled: () -> Boolean) {
        var offset = 0
        while (offset < buffer.size) {
            throwIfCancelled(isCancelled)
            val count = input.read(buffer, offset, buffer.size - offset)
            require(count >= 0) { "unexpected end of rootfs tar" }
            offset += count
        }
    }

    private fun readBytesExactly(
        input: InputStream,
        size: Long,
        isCancelled: () -> Boolean,
    ): ByteArray {
        require(size <= Int.MAX_VALUE) { "rootfs tar metadata entry is too large" }
        return ByteArray(size.toInt()).also { readFully(input, it, isCancelled) }
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            var space = offset
            while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
            require(space in (offset + 1) until bytes.size) { "invalid PAX record length" }
            val length = bytes.copyOfRange(offset, space)
                .toString(Charsets.US_ASCII)
                .toInt()
            val end = offset + length
            require(length > 0 && end <= bytes.size) { "invalid PAX record boundary" }
            val record = bytes.copyOfRange(space + 1, end)
                .toString(Charsets.UTF_8)
                .trimEnd('\n')
            val separator = record.indexOf('=')
            if (separator > 0) {
                values[record.substring(0, separator)] = record.substring(separator + 1)
            }
            offset = end
        }
        return values
    }

    private fun copyExactly(
        input: InputStream,
        output: FileOutputStream,
        size: Long,
        isCancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            throwIfCancelled(isCancelled)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "unexpected end of rootfs file" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExactly(input: InputStream, size: Long, isCancelled: () -> Boolean) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            throwIfCancelled(isCancelled)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "unexpected end of rootfs tar entry" }
            remaining -= count
        }
    }

    private fun skipPadding(input: InputStream, size: Long, isCancelled: () -> Boolean) {
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        var remaining = padding
        while (remaining > 0) {
            throwIfCancelled(isCancelled)
            val count = input.skip(remaining)
            require(count > 0) { "unexpected end of rootfs padding" }
            remaining -= count
        }
    }

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("runtime installation cancelled")
    }
}
