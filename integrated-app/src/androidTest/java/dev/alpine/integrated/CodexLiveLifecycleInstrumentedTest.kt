package dev.alpine.integrated

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.alpine.codex.appserver.CodexAccountState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive Samsung lifecycle E2E using only redacted official account state. */
@RunWith(AndroidJUnit4::class)
class CodexLiveLifecycleInstrumentedTest {
    @Test
    fun backgroundForegroundAndActivityRecreationPreserveAccount() = runBlocking {
        assumeTrue(BuildConfig.CODEX_APP_SERVER_ENABLED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as IntegratedApplication
        val runtime = requireNotNull(application.codexAppServerRuntime)
        val alpineStateBefore = application.runtimeManager.currentState()
        val client = withTimeout(REQUEST_TIMEOUT_MS) { runtime.connect() }
        assertEquals(CodexAccountState.CHATGPT, client.accountState())

        var activity = instrumentation.startActivitySync(
            Intent(context, IntegratedMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as IntegratedMainActivity
        waitForStage(activity, Stage.RESUMED)

        instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
        waitForStage(activity, Stage.STOPPED)
        context.startActivity(
            Intent(context, IntegratedMainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        waitForStage(activity, Stage.RESUMED)
        assertEquals(CodexAccountState.CHATGPT, client.accountState())

        val previous = activity
        instrumentation.runOnMainSync { previous.recreate() }
        waitUntil { previous.isDestroyed }
        activity = resumedIntegratedActivity()
        assertEquals(CodexAccountState.CHATGPT, client.accountState())
        assertEquals(alpineStateBefore, application.runtimeManager.currentState())

        instrumentation.runOnMainSync { activity.finish() }
        waitUntil { activity.isDestroyed }
    }

    private fun resumedIntegratedActivity(): IntegratedMainActivity {
        var result: IntegratedMainActivity? = null
        waitUntil {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<IntegratedMainActivity>()
                    .singleOrNull()
            }
            result != null
        }
        return requireNotNull(result)
    }

    private fun waitForStage(activity: Activity, stage: Stage) = waitUntil {
        var current: Stage? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            current = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)
        }
        current == stage
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("lifecycle transition timed out", false)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val LIFECYCLE_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
