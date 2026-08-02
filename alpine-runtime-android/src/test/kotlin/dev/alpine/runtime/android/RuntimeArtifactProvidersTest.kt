package dev.alpine.runtime.android

import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactManifest
import dev.alpine.runtime.api.RuntimeArtifactManifestCanonicalizer
import dev.alpine.runtime.api.RuntimeArtifactRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class RuntimeArtifactProvidersTest {
    @Test
    fun `host provider preserves supplied bundle`() {
        val bundle = emptyBundle()
        val provider = HostProvidedRuntimeArtifactProvider { CompletableFuture.completedFuture(bundle) }

        assertSame(bundle, provider.resolve(RuntimeArtifactRequest()).toCompletableFuture().join())
    }

    @Test
    fun `signed provider accepts valid Ed25519 manifest`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val bundle = emptyBundle()
        val manifest = RuntimeArtifactManifestCanonicalizer.canonicalBytes(bundle.manifest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(manifest)
            sign()
        }
        val provider = SignedDownloadRuntimeArtifactProvider(
            source = {
                CompletableFuture.completedFuture(
                    SignedRuntimeArtifactEnvelope(bundle, manifest, signature, "release"),
                )
            },
            verifier = Ed25519RuntimeManifestSignatureVerifier(mapOf("release" to keys.public)),
        )

        assertSame(bundle, provider.resolve(RuntimeArtifactRequest()).toCompletableFuture().join())
    }

    @Test
    fun `signed provider rejects a valid signature for a different bundle`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signedBundle = emptyBundle(version = "signed")
        val deliveredBundle = emptyBundle(version = "substituted")
        val manifest = RuntimeArtifactManifestCanonicalizer.canonicalBytes(signedBundle.manifest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(manifest)
            sign()
        }
        val provider = SignedDownloadRuntimeArtifactProvider(
            source = {
                CompletableFuture.completedFuture(
                    SignedRuntimeArtifactEnvelope(deliveredBundle, manifest, signature, "release"),
                )
            },
            verifier = Ed25519RuntimeManifestSignatureVerifier(mapOf("release" to keys.public)),
        )

        val error = assertThrows(CompletionException::class.java) {
            provider.resolve(RuntimeArtifactRequest()).toCompletableFuture().join()
        }
        assertEquals(
            RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
            (error.cause as RuntimeOperationException).errorCode,
        )
    }

    @Test
    fun `signed provider rejects tampered manifest`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update("original".toByteArray())
            sign()
        }
        val provider = SignedDownloadRuntimeArtifactProvider(
            source = {
                CompletableFuture.completedFuture(
                    SignedRuntimeArtifactEnvelope(
                        emptyBundle(),
                        "tampered".toByteArray(),
                        signature,
                        "release",
                    ),
                )
            },
            verifier = Ed25519RuntimeManifestSignatureVerifier(mapOf("release" to keys.public)),
        )

        val error = assertThrows(CompletionException::class.java) {
            provider.resolve(RuntimeArtifactRequest()).toCompletableFuture().join()
        }
        assertEquals(
            RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
            (error.cause as RuntimeOperationException).errorCode,
        )
    }

    @Test
    fun `signed provider maps synchronous source failure to a stable error`() {
        val provider = SignedDownloadRuntimeArtifactProvider(
            source = SignedRuntimeArtifactSource { throw IllegalStateException("transport secret") },
            verifier = RuntimeManifestSignatureVerifier { _, _, _ -> true },
        )

        val error = assertThrows(CompletionException::class.java) {
            provider.resolve(RuntimeArtifactRequest()).toCompletableFuture().join()
        }
        assertEquals(
            RuntimeErrorCode.ARTIFACT_NOT_FOUND,
            (error.cause as RuntimeOperationException).errorCode,
        )
        assertEquals(RuntimeErrorCode.ARTIFACT_NOT_FOUND.name, error.cause?.message)
    }

    private fun emptyBundle(version: String = "test") = RuntimeArtifactBundle(
        RuntimeArtifactManifest("alpine", version, emptyList()),
        emptyList(),
    )
}
