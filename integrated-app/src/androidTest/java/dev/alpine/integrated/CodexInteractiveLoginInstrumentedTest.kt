package dev.alpine.integrated

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.codex.appserver.CodexAccountState
import dev.alpine.codex.appserver.CodexAuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Interactive Samsung login driver. It opens only the official URI returned by App Server and
 * polls redacted account state; it never prints the URI, account identity, or credential data.
 */
@RunWith(AndroidJUnit4::class)
class CodexInteractiveLoginInstrumentedTest {
    @Test
    fun openOfficialBrowserLoginAndWaitForPersistedAccount() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val connection = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connectWithAuth() }
        val client = connection.client
        val auth = connection.auth
        withTimeout(REQUEST_TIMEOUT_MS) { auth.refresh() }

        if (auth.state.value != CodexAuthState.SignedIn) {
            val login = withTimeout(REQUEST_TIMEOUT_MS) { auth.startBrowserLogin() }
            instrumentation.targetContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(login.authorizationUri.toASCIIString()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val terminal = withTimeout(LOGIN_TIMEOUT_MS) {
                auth.state.first { state ->
                    state is CodexAuthState.SignedIn || state is CodexAuthState.Failed
                }
            }
            if (terminal is CodexAuthState.Failed) {
                throw AssertionError("official login failed: ${terminal.code.name}")
            }
        }

        assertEquals(CodexAccountState.CHATGPT, client.accountState())
        val models = withTimeout(REQUEST_TIMEOUT_MS) { client.models() }
        assertTrue("official App Server model list must not be empty", models.isNotEmpty())
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val LOGIN_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}
