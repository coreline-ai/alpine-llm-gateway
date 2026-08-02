package dev.alpine.runtime.gateway.pack.bundled

import android.content.Context
import dev.alpine.runtime.bridge.PythonGatewayArtifact
import dev.alpine.runtime.bridge.PythonGatewayArtifactBundle
import dev.alpine.runtime.bridge.PythonGatewayArtifactDescriptor
import dev.alpine.runtime.bridge.PythonGatewayArtifactManifest
import dev.alpine.runtime.bridge.PythonGatewayArtifactProvider
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class BundledPythonGatewayArtifactProvider(context: Context) : PythonGatewayArtifactProvider {
    private val appContext = context.applicationContext

    override fun resolve(): CompletionStage<PythonGatewayArtifactBundle> =
        CompletableFuture.completedFuture(
            PythonGatewayArtifactBundle(
                manifest = AlpineLlmGateway030Pack.manifest(),
                artifact = AssetPythonGatewayArtifact(
                    descriptor = AlpineLlmGateway030Pack.descriptor(),
                    context = appContext,
                ),
            ),
        )
}

private class AssetPythonGatewayArtifact(
    override val descriptor: PythonGatewayArtifactDescriptor,
    private val context: Context,
) : PythonGatewayArtifact {
    override fun openStream(): InputStream = context.assets.open(AlpineLlmGateway030Pack.ASSET_NAME)
}

object AlpineLlmGateway030Pack {
    const val ASSET_NAME = "alpine-llm-gateway.tar.gz.asset"
    const val PACKAGE_VERSION = "0.3.0"
    const val PROTOCOL_VERSION = "1"

    @JvmStatic
    fun descriptor(): PythonGatewayArtifactDescriptor = PythonGatewayArtifactDescriptor(
        id = "alpine-llm-gateway",
        packageVersion = PACKAGE_VERSION,
        protocolVersion = PROTOCOL_VERSION,
        minimumPythonVersion = "3.11",
        sha256 = "c6e79f12c9902c728a2e2336b2b3bf9ce2bae7fe9ef37bbd3060de1cbbb22a96",
        sizeBytes = 13_019L,
        license = "NOASSERTION",
    )

    @JvmStatic
    fun manifest(): PythonGatewayArtifactManifest = PythonGatewayArtifactManifest(
        packageId = "alpine-llm-gateway",
        packageVersion = PACKAGE_VERSION,
        protocolVersion = PROTOCOL_VERSION,
        entrypoints = mapOf(
            "llmctl" to "/usr/local/bin/llmctl",
            "gatewayd" to "/usr/local/bin/llm-gatewayd",
        ),
        metadata = mapOf(
            "schema.version" to "1",
            "artifact.format" to "tar.gz",
            "source" to "repository:alpine_llm",
        ),
    )
}
