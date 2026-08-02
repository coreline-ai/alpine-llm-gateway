package dev.alpine.runtime.testkit

import dev.alpine.runtime.api.RuntimeArtifact
import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactManifest
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeArtifactRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class VirtualRuntimeArtifact(
    override val descriptor: RuntimeArtifactDescriptor,
    content: ByteArray,
) : RuntimeArtifact {
    private val content = content.copyOf()

    init {
        require(descriptor.sizeBytes == this.content.size.toLong()) { "descriptor size does not match content" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(this.content)
            .joinToString("") { "%02x".format(it) }
        require(descriptor.sha256.equals(digest, ignoreCase = true)) {
            "descriptor checksum does not match content"
        }
    }

    override fun openStream(): InputStream = ByteArrayInputStream(content)
}

class VirtualRuntimeArtifactProvider(
    private val bundle: RuntimeArtifactBundle,
) : RuntimeArtifactProvider {
    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> =
        CompletableFuture.completedFuture(bundle)

    companion object {
        @JvmStatic
        fun of(runtimeId: String, runtimeVersion: String, artifacts: List<VirtualRuntimeArtifact>): VirtualRuntimeArtifactProvider {
            val manifest = RuntimeArtifactManifest(
                runtimeId = runtimeId,
                runtimeVersion = runtimeVersion,
                artifacts = artifacts.map { it.descriptor },
            )
            return VirtualRuntimeArtifactProvider(RuntimeArtifactBundle(manifest, artifacts))
        }
    }
}
