package dev.alpine.llm

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Small Android-side lifecycle wrapper for an Alpine user space.
 *
 * Required assets:
 *   - alpine-rootfs.tar.gz
 *   - proot-aarch64
 *
 * The rootfs is extracted into app-private storage. This class does not
 * install a Linux kernel; it runs Alpine user space through the supplied
 * PRoot-compatible executable and the Android kernel.
 */
class AlpineRuntime(
    context: Context,
    private val config: Config = Config(),
) {
    data class Config(
        val rootfsAsset: String = "alpine-rootfs.tar.gz",
        val prootAsset: String = "proot-aarch64",
        val rootfsVersion: String = "v1",
        val rootfsSha256: String? = null,
        val prootSha256: String? = null,
        val runtimeAssets: List<AlpineRuntimeAssetSet> = emptyList(),
        val gatewayCommand: List<String> = listOf(
            "/usr/bin/python3",
            "-m",
            "alpine_llm.cli",
            "serve",
            "--config",
            "/etc/alpine-llm/config.json",
        ),
        val maxOutputBytes: Int = 2 * 1024 * 1024,
        val maxRootfsArchiveBytes: Long = 256L * 1024 * 1024,
        val maxRootfsBytes: Long = 512L * 1024 * 1024,
        val maxRootfsEntries: Int = 100_000,
        val maxProotBytes: Long = 64L * 1024 * 1024,
    ) {
        init {
            require(rootfsAsset.isNotBlank()) { "rootfsAsset must not be blank" }
            require(prootAsset.isNotBlank()) { "prootAsset must not be blank" }
            require(rootfsVersion.isNotBlank()) { "rootfsVersion must not be blank" }
            rootfsSha256?.let { RuntimeAssetIntegrity.requireSha256(it, "rootfsSha256") }
            prootSha256?.let { RuntimeAssetIntegrity.requireSha256(it, "prootSha256") }
            require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
            require(maxRootfsArchiveBytes > 0) {
                "maxRootfsArchiveBytes must be positive"
            }
            require(maxRootfsBytes > 0) { "maxRootfsBytes must be positive" }
            require(maxRootfsEntries > 0) { "maxRootfsEntries must be positive" }
            require(maxProotBytes > 0) { "maxProotBytes must be positive" }
        }
    }

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val timedOut: Boolean = false,
        val outputTruncated: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val runtimeDir = File(appContext.filesDir, "alpine-runtime")
    private val rootfsDir = File(runtimeDir, "rootfs")
    private val binDir = File(runtimeDir, "bin")
    private val workspaceDir = File(runtimeDir, "workspace")
    private val markerFile = File(runtimeDir, "rootfs.version")
    private var gatewayProcess: Process? = null
    private var gatewayOutputThread: Thread? = null
    @Volatile private var hostBridgeEndpoint: HostBridgeServer.Endpoint? = null

    fun attachHostBridge(endpoint: HostBridgeServer.Endpoint) {
        hostBridgeEndpoint = endpoint
    }

    fun detachHostBridge() {
        hostBridgeEndpoint = null
    }

    @Synchronized
    fun installIfNeeded(): File {
        runtimeDir.mkdirs()
        workspaceDir.mkdirs()
        binDir.mkdirs()
        val assets = resolvedAssets()
        if (markerFile.readTextOrNull() != assets.marker ||
            !File(rootfsDir, "bin/sh").exists()
        ) {
            val staging = File(runtimeDir, "rootfs.installing")
            val rootfsArchive = File(runtimeDir, "rootfs.installing.tar.gz")
            staging.deleteRecursively()
            rootfsArchive.delete()
            staging.mkdirs()
            try {
                appContext.assets.open(assets.rootfsAsset).use { input ->
                    RuntimeAssetIntegrity.copyVerified(
                        input,
                        rootfsArchive,
                        assets.rootfsSha256,
                        config.maxRootfsArchiveBytes,
                    )
                }
                FileInputStream(rootfsArchive).use { input ->
                    TarGzExtractor.extract(
                        input = input,
                        destination = staging,
                        maxExtractedBytes = config.maxRootfsBytes,
                        maxEntries = config.maxRootfsEntries,
                    )
                }
            } finally {
                rootfsArchive.delete()
            }
            val backup = File(runtimeDir, "rootfs.previous")
            backup.deleteRecursively()
            if (rootfsDir.exists() && !rootfsDir.renameTo(backup)) {
                staging.deleteRecursively()
                throw IllegalStateException("failed to preserve the previous Alpine rootfs")
            }
            if (!staging.renameTo(rootfsDir)) {
                if (backup.exists()) backup.renameTo(rootfsDir)
                throw IllegalStateException("failed to activate Alpine rootfs")
            }
            backup.deleteRecursively()
            markerFile.writeText(assets.marker)
        }
        copyExecutableAssetIfNeeded(assets.prootAsset, assets.prootSha256)
        return rootfsDir
    }

    fun installationStatus(): AlpineRuntimeInstallStatus {
        val assets = resolvedAssets()
        val rootfsPresent = File(rootfsDir, "bin/sh").isFile
        val prootFile = File(binDir, assets.prootAsset.substringAfterLast('/'))
        val prootPresent = RuntimeAssetIntegrity.verify(prootFile, assets.prootSha256)
        return AlpineRuntimeInstallStatus(
            installed = markerFile.readTextOrNull() == assets.marker &&
                rootfsPresent &&
                prootPresent,
            version = markerFile.readTextOrNull()?.substringBefore('|'),
            abi = assets.abi,
            rootfsPresent = rootfsPresent,
            prootPresent = prootPresent,
        )
    }

    @Synchronized
    fun startGateway(): Process {
        installIfNeeded()
        gatewayProcess?.let { if (it.isAlive) return it }
        val process = startInRoot(
            config.gatewayCommand,
            extraEnvironment = mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "PYTHONUNBUFFERED" to "1",
            ),
        )
        gatewayOutputThread = drainInBackground(process.inputStream, "alpine-gateway-output")
        gatewayProcess = process
        return process
    }

    fun exec(command: String, timeoutMs: Long = 60_000L): ExecResult {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        installIfNeeded()
        val process = startInRoot(
            listOf("/bin/sh", "-lc", command),
            extraEnvironment = mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "PWD" to "/workspace",
            ),
        )
        val collector = LimitedOutputCollector(process.inputStream, config.maxOutputBytes)
        val outputThread = Thread(collector, "alpine-exec-output").apply {
            isDaemon = true
            start()
        }
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outputThread.join(2_000)
        return ExecResult(
            exitCode = if (completed) process.exitValue() else -1,
            stdout = collector.text(),
            timedOut = !completed,
            outputTruncated = collector.truncated,
        )
    }

    @Synchronized
    fun stop() {
        gatewayProcess?.let {
            it.destroy()
            if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        gatewayOutputThread?.interrupt()
        gatewayOutputThread = null
        gatewayProcess = null
    }

    fun isInstalled(): Boolean = installationStatus().installed

    private fun startInRoot(
        command: List<String>,
        extraEnvironment: Map<String, String>,
    ): Process {
        val proot = File(binDir, resolvedAssets().prootAsset.substringAfterLast('/'))
        val processCommand = mutableListOf(
            proot.absolutePath,
            "-0",
            "-r",
            rootfsDir.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "${workspaceDir.absolutePath}:/workspace",
        )
        processCommand.addAll(command)
        val process = ProcessBuilder(processCommand)
            .directory(workspaceDir)
            .redirectErrorStream(true)
            .apply {
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
                environment().putAll(extraEnvironment)
                hostBridgeEndpoint?.let { endpoint ->
                    environment()["ALPINE_LLM_URL"] = endpoint.url
                    environment()["ALPINE_LLM_SESSION_TOKEN"] = endpoint.sessionToken
                }
            }
            .start()
        return process
    }

    private fun copyExecutableAssetIfNeeded(
        assetName: String,
        expectedSha256: String?,
    ): File {
        val destination = File(binDir, assetName.substringAfterLast('/'))
        if (!RuntimeAssetIntegrity.verify(destination, expectedSha256)) {
            val staging = File(binDir, "${destination.name}.installing")
            staging.delete()
            appContext.assets.open(assetName).use { input ->
                RuntimeAssetIntegrity.copyVerified(
                    input,
                    staging,
                    expectedSha256,
                    config.maxProotBytes,
                )
            }
            if (destination.exists() && !destination.delete()) {
                staging.delete()
                throw IllegalStateException("failed to replace PRoot runtime asset")
            }
            if (!staging.renameTo(destination)) {
                staging.delete()
                throw IllegalStateException("failed to activate PRoot runtime asset")
            }
        }
        destination.setExecutable(true, false)
        return destination
    }

    private fun resolvedAssets(): ResolvedAssets {
        if (config.runtimeAssets.isEmpty()) {
            return ResolvedAssets(
                abi = null,
                rootfsAsset = config.rootfsAsset,
                rootfsVersion = config.rootfsVersion,
                rootfsSha256 = config.rootfsSha256,
                prootAsset = config.prootAsset,
                prootSha256 = config.prootSha256,
            )
        }
        val selected = AlpineRuntimeAssetSelector.select(
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            assets = config.runtimeAssets,
        )
        return ResolvedAssets(
            abi = selected.abi,
            rootfsAsset = selected.rootfsAsset,
            rootfsVersion = selected.rootfsVersion,
            rootfsSha256 = selected.rootfsSha256,
            prootAsset = selected.prootAsset,
            prootSha256 = selected.prootSha256,
        )
    }

    private data class ResolvedAssets(
        val abi: String?,
        val rootfsAsset: String,
        val rootfsVersion: String,
        val rootfsSha256: String?,
        val prootAsset: String,
        val prootSha256: String?,
    ) {
        val marker: String
            get() = if (abi == null && rootfsSha256 == null) {
                rootfsVersion
            } else {
                "$rootfsVersion|${abi ?: "legacy"}|${rootfsSha256 ?: "unverified"}"
            }
    }

    private fun drainInBackground(input: InputStream, threadName: String): Thread =
        Thread({
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (!Thread.currentThread().isInterrupted && stream.read(buffer) >= 0) {
                        // Drain to keep the long-running process from blocking on stdout.
                    }
                }
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }

    private class LimitedOutputCollector(
        private val input: InputStream,
        private val maxBytes: Int,
    ) : Runnable {
        private val output = ByteArrayOutputStream()
        @Volatile var truncated: Boolean = false
            private set

        override fun run() {
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = maxBytes - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(remaining, count))
                            if (count > remaining) truncated = true
                        }
                    }
                }
            }
        }

        fun text(): String = synchronized(output) {
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText() }.getOrNull() else null
}

/** Minimal tar.gz extractor for app-private rootfs installation. */
internal object TarGzExtractor {
    private const val BLOCK_SIZE = 512

    fun extract(
        input: InputStream,
        destination: File,
        maxExtractedBytes: Long,
        maxEntries: Int,
    ) {
        GZIPInputStream(BufferedInputStream(input)).use { gzip ->
            extractTar(gzip, destination, maxExtractedBytes, maxEntries)
        }
    }

    private fun extractTar(
        input: InputStream,
        destination: File,
        maxExtractedBytes: Long,
        maxEntries: Int,
    ) {
        val root = destination.canonicalFile
        val header = ByteArray(BLOCK_SIZE)
        var zeroBlocks = 0
        var extractedBytes = 0L
        var entries = 0
        var extendedPath: String? = null
        var extendedLinkPath: String? = null
        while (true) {
            readFully(input, header)
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
                val metadata = readBytesExactly(input, size)
                skipPadding(input, size)
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
                    FileOutputStream(target).use { output -> copyExactly(input, output, size) }
                    applyMode(target, mode)
                }
                '5' -> {
                    require(target.mkdirs() || target.isDirectory) {
                        "cannot create rootfs directory $name"
                    }
                    applyMode(target, mode)
                    skipExactly(input, size)
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    runCatching {
                        Files.deleteIfExists(target.toPath())
                        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                    }.getOrElse { throw IllegalStateException("cannot create rootfs symlink $name", it) }
                    skipExactly(input, size)
                }
                '1' -> {
                    val linkTarget = safeTarget(root, linkName)
                    target.parentFile?.mkdirs()
                    runCatching {
                        Files.deleteIfExists(target.toPath())
                        Files.createLink(target.toPath(), linkTarget.toPath())
                    }.getOrElse { throw IllegalStateException("cannot create rootfs hard link $name", it) }
                    skipExactly(input, size)
                }
                '3', '4', '6' -> {
                    // /dev is bind-mounted by PRoot; special files cannot be
                    // safely recreated by an unprivileged Android process.
                    skipExactly(input, size)
                }
                else -> {
                    skipExactly(input, size)
                    throw IllegalArgumentException("unsupported rootfs tar entry type '$type': $name")
                }
            }
            skipPadding(input, size)
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

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            require(count >= 0) { "unexpected end of rootfs tar" }
            offset += count
        }
    }

    private fun readBytesExactly(input: InputStream, size: Long): ByteArray {
        require(size <= Int.MAX_VALUE) { "rootfs tar metadata entry is too large" }
        return ByteArray(size.toInt()).also { readFully(input, it) }
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

    private fun copyExactly(input: InputStream, output: FileOutputStream, size: Long) {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "unexpected end of rootfs file" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "unexpected end of rootfs tar entry" }
            remaining -= count
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        var remaining = padding
        while (remaining > 0) {
            val count = input.skip(remaining)
            require(count > 0) { "unexpected end of rootfs padding" }
            remaining -= count
        }
    }
}
