package dev.alpine.runtime.bridge

import dev.alpine.runtime.api.RuntimeEnvironmentContext
import dev.alpine.runtime.api.RuntimeEnvironmentContributor
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

data class LlmBridgeEndpoint(
    val endpointUrl: String,
    val credentialFilePath: String,
) {
    init {
        val uri = runCatching { URI(endpointUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme == "http" &&
                uri.host in setOf("127.0.0.1", "::1") &&
                uri.port in 1..65535 &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/"),
        ) { "LLM bridge endpoint must be a loopback-only HTTP origin" }
        require(
            credentialFilePath.startsWith("/workspace/.alpine-runtime/") &&
                ".." !in credentialFilePath.split('/'),
        ) { "credentialFilePath must be inside the private workspace runtime directory" }
    }
}

fun interface LlmBridgeEndpointProvider {
    fun endpointFor(context: RuntimeEnvironmentContext): LlmBridgeEndpoint?
}

/** Mutable composition point created before the runtime factory and updated by its lifecycle owner. */
class LlmBridgeEndpointRegistry : LlmBridgeEndpointProvider {
    private val endpoint = AtomicReference<LlmBridgeEndpoint?>()

    override fun endpointFor(context: RuntimeEnvironmentContext): LlmBridgeEndpoint? = endpoint.get()

    fun update(value: LlmBridgeEndpoint) {
        endpoint.set(value)
    }

    fun clear() {
        endpoint.set(null)
    }
}

/** Adds only a loopback endpoint and credential-file reference; raw OAuth tokens are never exported. */
class LlmBridgeEnvironmentContributor(
    private val endpointProvider: LlmBridgeEndpointProvider,
) : RuntimeEnvironmentContributor {
    override fun contribute(context: RuntimeEnvironmentContext): Map<String, String> {
        val endpoint = endpointProvider.endpointFor(context) ?: return emptyMap()
        return mapOf(
            "ALPINE_LLM_BRIDGE_URL" to endpoint.endpointUrl,
            "ALPINE_LLM_CREDENTIAL_FILE" to endpoint.credentialFilePath,
        )
    }
}
