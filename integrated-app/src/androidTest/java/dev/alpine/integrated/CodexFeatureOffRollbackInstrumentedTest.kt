package dev.alpine.integrated

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same-package OFF rollback proof. The runner installs this variant only after approved logout. */
@RunWith(AndroidJUnit4::class)
class CodexFeatureOffRollbackInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun rollbackBuildHasNoCodexRuntimeBinaryOrUi() {
        assumeTrue(
            "live feature-OFF rollback requires the explicit instrumentation approval argument",
            InstrumentationRegistry.getArguments().getString(APPROVAL_ARGUMENT) == "true",
        )
        assertFalse(BuildConfig.CODEX_APP_SERVER_ENABLED)
        assertTrue(BuildConfig.CODEX_APP_SERVER_ROLLBACK_BUILD)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as IntegratedApplication
        assertNull(application.codexAppServerRuntime)
        assertFalse(
            File(context.applicationInfo.nativeLibraryDir, CODEX_BINARY_NAME).exists(),
        )

        IntegratedModeGuideStore(context).markCompleted()
        val activity = instrumentation.startActivitySync(
            Intent(context, IntegratedMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as IntegratedMainActivity
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("mode_fast_chat").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("codex_login_card").assertDoesNotExist()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private companion object {
        const val APPROVAL_ARGUMENT = "approveCodexRollback"
        const val CODEX_BINARY_NAME = "libcodex_app_server.so"
    }
}
