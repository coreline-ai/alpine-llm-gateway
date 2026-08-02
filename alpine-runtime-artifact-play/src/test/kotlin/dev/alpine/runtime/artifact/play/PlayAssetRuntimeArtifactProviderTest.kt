package dev.alpine.runtime.artifact.play

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactManifest
import dev.alpine.runtime.api.RuntimeArtifactRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class PlayAssetRuntimeArtifactProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `installed asset pack returns reopenable payload`() {
        val assets = temporaryFolder.newFolder("assets")
        File(assets, "runtime/rootfs.asset").apply {
            parentFile!!.mkdirs()
            writeText("rootfs")
        }
        val client = FakeClient(assets)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val bundle = provider(client, scheduler).resolve(RuntimeArtifactRequest())
                .toCompletableFuture().join()
            assertEquals("rootfs", bundle.artifacts.single().openStream().bufferedReader().use { it.readText() })
            assertEquals("rootfs", bundle.artifacts.single().openStream().bufferedReader().use { it.readText() })
            assertEquals(0, client.fetchCount)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `completed fetch resolves installed location and unregisters listener`() {
        val assets = temporaryFolder.newFolder("fetched-assets")
        File(assets, "runtime/rootfs.asset").apply {
            parentFile!!.mkdirs()
            writeText("fetched")
        }
        val client = FakeClient(null).apply {
            onFetch = { listener ->
                installed = assets
                listener(PlayAssetFetchState.COMPLETED)
            }
        }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val result = provider(client, scheduler).resolve(RuntimeArtifactRequest())
                .toCompletableFuture().join()
            assertEquals("fetched", result.artifacts.single().openStream().bufferedReader().use { it.readText() })
            assertTrue(client.closed)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `canceled fetch returns stable artifact error`() {
        val client = FakeClient(null).apply {
            onFetch = { it(PlayAssetFetchState.CANCELED) }
        }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val error = runCatching {
                provider(client, scheduler).resolve(RuntimeArtifactRequest()).toCompletableFuture().join()
            }.exceptionOrNull()!!
            val safe = generateSequence(error) { it.cause }
                .filterIsInstance<RuntimeOperationException>()
                .first()
            assertEquals(RuntimeErrorCode.ARTIFACT_NOT_FOUND, safe.errorCode)
            assertTrue(client.closed)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `caller cancellation cancels Play fetch and unregisters listener`() {
        val client = FakeClient(null)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val result = provider(client, scheduler).resolve(RuntimeArtifactRequest())
                .toCompletableFuture()

            assertTrue(result.cancel(true))
            assertEquals(1, client.cancelCount)
            assertTrue(client.closed)
        } finally {
            scheduler.shutdownNow()
        }
    }

    private fun provider(
        client: FakeClient,
        scheduler: java.util.concurrent.ScheduledExecutorService,
    ) = PlayAssetRuntimeArtifactProvider(
        pack = PlayAssetRuntimePack(
            assetPackName = "alpine_runtime",
            manifest = RuntimeArtifactManifest(
                runtimeId = "alpine",
                runtimeVersion = "test",
                artifacts = listOf(
                    RuntimeArtifactDescriptor(
                        id = "rootfs",
                        kind = RuntimeArtifactKind.ROOTFS,
                        version = "test",
                        sha256 = "0".repeat(64),
                        sizeBytes = 6,
                    ),
                ),
            ),
            payloadPaths = mapOf("rootfs" to "runtime/rootfs.asset"),
        ),
        client = client,
        nativeDirectory = temporaryFolder.root,
        fetchTimeoutMillis = 1_000,
        scheduler = scheduler,
    )

    private class FakeClient(var installed: File?) : PlayAssetPackClient {
        var fetchCount = 0
        var cancelCount = 0
        var closed = false
        var onFetch: ((PlayAssetFetchState) -> Unit) -> Unit = { }

        override fun installedAssetsDirectory(packName: String): File? = installed

        override fun fetch(
            packName: String,
            listener: (PlayAssetFetchState) -> Unit,
        ): PlayAssetFetchSubscription {
            fetchCount++
            onFetch(listener)
            return PlayAssetFetchSubscription { closed = true }
        }

        override fun cancel(packName: String) {
            cancelCount++
        }
    }
}
