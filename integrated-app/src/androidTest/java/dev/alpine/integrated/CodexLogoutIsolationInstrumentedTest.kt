package dev.alpine.integrated

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.provider.android.data.ProviderProfileStore
import dev.alpine.codex.appserver.CodexAccountState
import dev.alpine.codex.appserver.CodexAuthState
import dev.alpine.llm.OAuthTokenStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Approval-gated live logout test. It compares only in-memory equality and digests and never emits
 * Provider values, conversation contents, workspace names, Codex account identity, or credentials.
 */
@RunWith(AndroidJUnit4::class)
class CodexLogoutIsolationInstrumentedTest {
    @Test
    fun officialLogoutPreservesNonCodexApplicationData() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        assumeTrue(
            "live Codex logout requires the explicit instrumentation approval argument",
            InstrumentationRegistry.getArguments().getString(APPROVAL_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val application = context as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val connection = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connectWithAuth() }
        assertEquals(CodexAccountState.CHATGPT, connection.client.accountState())

        val profilesBefore = ProviderProfileStore(context).load()
        val providerCredentialStatesBefore = providerCredentialStates(context, profilesBefore.map { it.id })
        val conversationsBefore = directoryFingerprint(
            File(context.noBackupFilesDir, ConversationStore.DIRECTORY_NAME),
        )
        val workspaceBefore = directoryFingerprint(
            File(context.filesDir, INTEGRATED_WORKSPACE_DIRECTORY),
        )
        val alpineStateBefore = application.runtimeManager.currentState()

        withTimeout(REQUEST_TIMEOUT_MS) { connection.auth.logout() }
        assertEquals(CodexAccountState.SIGNED_OUT, connection.client.accountState())
        val restarted = withTimeout(REQUEST_TIMEOUT_MS) { runtime.restart() }
        assertEquals(CodexAccountState.SIGNED_OUT, restarted.accountState())

        assertTrue(
            "Provider profiles changed during Codex logout",
            profilesBefore == ProviderProfileStore(context).load(),
        )
        assertTrue(
            "Provider credential availability changed during Codex logout",
            providerCredentialStatesBefore ==
                providerCredentialStates(context, profilesBefore.map { it.id }),
        )
        assertTrue(
            "conversation storage changed during Codex logout",
            conversationsBefore == directoryFingerprint(
                File(context.noBackupFilesDir, ConversationStore.DIRECTORY_NAME),
            ),
        )
        assertTrue(
            "workspace storage changed during Codex logout",
            workspaceBefore == directoryFingerprint(
                File(context.filesDir, INTEGRATED_WORKSPACE_DIRECTORY),
            ),
        )
        assertEquals(alpineStateBefore, application.runtimeManager.currentState())
        assertEquals(CodexAuthState.SignedOut, runtime.connectWithAuth().auth.state.value)
    }

    private fun providerCredentialStates(context: Context, profileIds: List<String>): Map<String, String> {
        val store = OAuthTokenStore(context)
        return profileIds.associateWith { profileId ->
            when (val state = store.read(profileId)) {
                is OAuthTokenStore.ReadResult.Available -> "AVAILABLE"
                OAuthTokenStore.ReadResult.Missing -> "MISSING"
                is OAuthTokenStore.ReadResult.ReauthenticationRequired ->
                    "REAUTHENTICATION_REQUIRED_${state.reason.name}"
            }
        }
    }

    private fun directoryFingerprint(root: File): DirectoryFingerprint {
        if (!root.exists()) return DirectoryFingerprint(exists = false, entries = emptyList())
        return try {
            val canonicalRoot = root.canonicalFile
            var totalBytes = 0L
            val files = canonicalRoot.walkTopDown().toList()
            check(files.size <= MAX_SNAPSHOT_ENTRIES) { "SNAPSHOT_ENTRY_LIMIT" }
            val entries = files.map { file ->
                val canonical = file.canonicalFile
                check(canonical.path == canonicalRoot.path ||
                    canonical.path.startsWith(canonicalRoot.path + File.separator)
                ) { "SNAPSHOT_PATH_ESCAPE" }
                if (canonical.isDirectory) {
                    FingerprintEntry(canonical.relativeTo(canonicalRoot).path, true, 0L, null)
                } else {
                    val length = canonical.length()
                    totalBytes += length
                    check(totalBytes <= MAX_SNAPSHOT_BYTES) { "SNAPSHOT_BYTE_LIMIT" }
                    FingerprintEntry(
                        relativePath = canonical.relativeTo(canonicalRoot).path,
                        directory = false,
                        length = length,
                        sha256 = sha256(canonical),
                    )
                }
            }.sortedWith(compareBy(FingerprintEntry::relativePath, FingerprintEntry::directory))
            DirectoryFingerprint(exists = true, entries = entries)
        } catch (_: Exception) {
            throw AssertionError("APP_DATA_SNAPSHOT_FAILED")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class DirectoryFingerprint(
        val exists: Boolean,
        val entries: List<FingerprintEntry>,
    )

    private data class FingerprintEntry(
        val relativePath: String,
        val directory: Boolean,
        val length: Long,
        val sha256: String?,
    )

    private companion object {
        const val APPROVAL_ARGUMENT = "approveCodexLogout"
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val INTEGRATED_WORKSPACE_DIRECTORY = "alpine-integrated-runtime/workspace"
        const val MAX_SNAPSHOT_ENTRIES = 2_048
        const val MAX_SNAPSHOT_BYTES = 256L * 1024L * 1024L
    }
}
