package dev.alpine.integrated

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.codex.appserver.CodexAccountState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-destructive account probe for a same-package upgrade. It reads account state only through
 * the official App Server API and never reads credential files or emits account/model details.
 */
@RunWith(AndroidJUnit4::class)
class CodexAccountProbeInstrumentedTest {
    @Test
    fun persistedChatGptAccountAndModelListAreAvailable() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val client = withTimeout(PROBE_TIMEOUT_MS) { runtime.connect() }
        val accountState = withTimeout(PROBE_TIMEOUT_MS) { client.accountState() }
        assertEquals(CodexAccountState.CHATGPT, accountState)
        val models = withTimeout(PROBE_TIMEOUT_MS) { client.models() }
        assertTrue("official App Server model list must not be empty", models.isNotEmpty())
    }

    @Test
    fun officialCredentialRefreshPreservesRedactedAccountAndModels() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IntegratedApplication
        val client = withTimeout(PROBE_TIMEOUT_MS) {
            requireNotNull(application.codexAppServerRuntime).connect()
        }

        assertEquals(
            CodexAccountState.CHATGPT,
            withTimeout(PROBE_TIMEOUT_MS) { client.refreshAccountState() },
        )
        assertTrue(withTimeout(PROBE_TIMEOUT_MS) { client.models() }.isNotEmpty())
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 60_000L
    }
}
