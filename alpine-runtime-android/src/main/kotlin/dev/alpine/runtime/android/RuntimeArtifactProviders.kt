package dev.alpine.runtime.android

import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactManifestCanonicalizer
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeArtifactRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import java.security.PublicKey
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Explicit adapter for runtime artifacts supplied and lifecycle-owned by a host app. */
class HostProvidedRuntimeArtifactProvider(
    private val resolver: RuntimeArtifactProvider,
) : RuntimeArtifactProvider {
    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> =
        resolver.resolve(request)
}

data class SignedRuntimeArtifactEnvelope(
    val bundle: RuntimeArtifactBundle,
    val canonicalManifest: ByteArray,
    val signature: ByteArray,
    val keyId: String,
)

fun interface SignedRuntimeArtifactSource {
    fun fetch(request: RuntimeArtifactRequest): CompletionStage<SignedRuntimeArtifactEnvelope>
}

fun interface RuntimeManifestSignatureVerifier {
    fun verify(keyId: String, canonicalManifest: ByteArray, signature: ByteArray): Boolean
}

class Ed25519RuntimeManifestSignatureVerifier(
    publicKeys: Map<String, PublicKey>,
) : RuntimeManifestSignatureVerifier {
    private val publicKeys = publicKeys.toMap()

    override fun verify(
        keyId: String,
        canonicalManifest: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val key = publicKeys[keyId] ?: return false
        return runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(canonicalManifest)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}

/**
 * Signed-download trust boundary. The source owns transport/cache; this provider rejects an
 * unsigned manifest before the installer opens any payload stream. Payload size/SHA checks are
 * still enforced independently by the runtime installer.
 */
class SignedDownloadRuntimeArtifactProvider(
    private val source: SignedRuntimeArtifactSource,
    private val verifier: RuntimeManifestSignatureVerifier,
) : RuntimeArtifactProvider {
    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> {
        val result = CompletableFuture<RuntimeArtifactBundle>()
        val fetched = try {
            source.fetch(request)
        } catch (_: Exception) {
            result.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
            return result
        }
        fetched.whenComplete { envelope, error ->
            val trusted = envelope != null && runCatching {
                verifier.verify(
                    envelope.keyId,
                    envelope.canonicalManifest,
                    envelope.signature,
                ) && envelope.canonicalManifest.contentEquals(
                    RuntimeArtifactManifestCanonicalizer.canonicalBytes(envelope.bundle.manifest),
                )
            }.getOrDefault(false)
            if (error != null) {
                result.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
            } else if (!trusted) {
                result.completeExceptionally(
                    RuntimeOperationException(RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED),
                )
            } else {
                result.complete(envelope!!.bundle)
            }
        }
        return result
    }
}
