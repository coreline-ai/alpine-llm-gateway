package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class AlpineRuntimeAssetsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun selectsFirstSupportedAbiInDeviceOrder() {
        val arm64 = assets("arm64-v8a")
        val x86 = assets("x86_64")

        val selected = AlpineRuntimeAssetSelector.select(
            supportedAbis = listOf("x86_64", "arm64-v8a"),
            assets = listOf(arm64, x86),
        )

        assertEquals("x86_64", selected.abi)
    }

    @Test
    fun rejectsUnsupportedAndDuplicateAbi() {
        assertThrows(UnsupportedOperationException::class.java) {
            AlpineRuntimeAssetSelector.select(
                supportedAbis = listOf("armeabi-v7a"),
                assets = listOf(assets("arm64-v8a")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlpineRuntimeAssetSelector.select(
                supportedAbis = listOf("arm64-v8a"),
                assets = listOf(assets("arm64-v8a"), assets("arm64-v8a")),
            )
        }
    }

    @Test
    fun copiesVerifiedAssetAndDeletesChecksumMismatch() {
        val bytes = "runtime-asset".toByteArray()
        val expected = sha256(bytes)
        val valid = temporaryFolder.newFile("valid")

        RuntimeAssetIntegrity.copyVerified(
            ByteArrayInputStream(bytes),
            valid,
            expected,
        )

        assertTrue(RuntimeAssetIntegrity.verify(valid, expected))
        val invalid = temporaryFolder.newFile("invalid")
        val error = assertThrows(SecurityException::class.java) {
            RuntimeAssetIntegrity.copyVerified(
                ByteArrayInputStream(bytes),
                invalid,
                "0".repeat(64),
            )
        }
        assertEquals("Runtime asset checksum verification failed", error.message)
        assertFalse(invalid.exists())

        val oversized = temporaryFolder.newFile("oversized")
        assertThrows(SecurityException::class.java) {
            RuntimeAssetIntegrity.copyVerified(
                ByteArrayInputStream(bytes),
                oversized,
                expectedSha256 = null,
                maxBytes = 2,
            )
        }
        assertFalse(oversized.exists())
    }

    private fun assets(abi: String) = AlpineRuntimeAssetSet(
        abi = abi,
        rootfsAsset = "rootfs-$abi.tar.gz",
        rootfsVersion = "3.22",
        prootAsset = "proot-$abi",
        rootfsSha256 = "1".repeat(64),
        prootSha256 = "2".repeat(64),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
