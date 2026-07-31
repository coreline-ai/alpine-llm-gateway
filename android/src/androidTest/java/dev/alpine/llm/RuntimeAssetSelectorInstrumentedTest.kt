package dev.alpine.llm

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeAssetSelectorInstrumentedTest {
    @Test
    fun selectsTheRuntimeAssetForThePhysicalDevicePrimaryAbi() {
        val supported = Build.SUPPORTED_ABIS.toList()
        assertTrue(supported.isNotEmpty())
        val productionAbis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        val expectedAbi = supported.firstOrNull(productionAbis::contains)
        assertTrue(expectedAbi != null)
        val assets = productionAbis.map { abi ->
            AlpineRuntimeAssetSet(
                abi = abi,
                rootfsAsset = "rootfs-$abi.tar.gz",
                rootfsVersion = "test",
                prootAsset = "proot-$abi",
                rootfsSha256 = "1".repeat(64),
                prootSha256 = "2".repeat(64),
            )
        }

        val selected = AlpineRuntimeAssetSelector.select(supported, assets)

        assertEquals(expectedAbi, selected.abi)
    }
}
