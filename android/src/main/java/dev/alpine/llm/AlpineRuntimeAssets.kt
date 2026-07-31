package dev.alpine.llm

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class AlpineRuntimeAssetSet(
    val abi: String,
    val rootfsAsset: String,
    val rootfsVersion: String,
    val prootAsset: String,
    val rootfsSha256: String,
    val prootSha256: String,
) {
    init {
        require(abi.isNotBlank()) { "abi must not be blank" }
        require(rootfsAsset.isNotBlank()) { "rootfsAsset must not be blank" }
        require(rootfsVersion.isNotBlank()) { "rootfsVersion must not be blank" }
        require(prootAsset.isNotBlank()) { "prootAsset must not be blank" }
        RuntimeAssetIntegrity.requireSha256(rootfsSha256, "rootfsSha256")
        RuntimeAssetIntegrity.requireSha256(prootSha256, "prootSha256")
    }
}

object AlpineRuntimeAssetSelector {
    fun select(
        supportedAbis: List<String>,
        assets: List<AlpineRuntimeAssetSet>,
    ): AlpineRuntimeAssetSet {
        require(assets.isNotEmpty()) { "runtime asset list must not be empty" }
        val duplicateAbi = assets.groupingBy { it.abi }.eachCount()
            .entries.firstOrNull { it.value > 1 }?.key
        require(duplicateAbi == null) { "duplicate runtime asset ABI: $duplicateAbi" }
        supportedAbis.forEach { supported ->
            assets.firstOrNull { it.abi == supported }?.let { return it }
        }
        throw UnsupportedOperationException(
            "No Alpine runtime assets support this device ABI",
        )
    }
}

data class AlpineRuntimeInstallStatus(
    val installed: Boolean,
    val version: String?,
    val abi: String?,
    val rootfsPresent: Boolean,
    val prootPresent: Boolean,
)

internal object RuntimeAssetIntegrity {
    fun requireSha256(value: String, name: String) {
        require(SHA_256.matches(value)) { "$name must be 64 lowercase hexadecimal characters" }
    }

    fun copyVerified(
        input: InputStream,
        destination: File,
        expectedSha256: String?,
        maxBytes: Long = Long.MAX_VALUE,
    ) {
        expectedSha256?.let { requireSha256(it, "expectedSha256") }
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw SecurityException("Runtime asset exceeds size limit")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        val actual = digest.digest().toHex()
        if (expectedSha256 != null && !constantTimeEquals(actual, expectedSha256)) {
            destination.delete()
            throw SecurityException("Runtime asset checksum verification failed")
        }
    }

    fun verify(file: File, expectedSha256: String?): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (expectedSha256 == null) return true
        requireSha256(expectedSha256, "expectedSha256")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().toHex()
        return constantTimeEquals(actual, expectedSha256)
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean =
        MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII),
        )

    private fun ByteArray.toHex(): String =
        joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private val SHA_256 = Regex("[0-9a-f]{64}")
}
