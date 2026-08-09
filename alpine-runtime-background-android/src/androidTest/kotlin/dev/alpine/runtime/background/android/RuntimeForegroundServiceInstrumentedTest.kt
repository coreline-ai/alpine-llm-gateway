package dev.alpine.runtime.background.android

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessEventKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeForegroundServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        // A library instrumentation APK hosts the merged service and its runtime permission;
        // targetContext is the library namespace, not an installed application package.
        get() = instrumentation.context

    @Before
    fun setUp() {
        stopServiceAndWait()
    }

    @After
    fun tearDown() {
        stopServiceAndWait()
    }

    @Test
    fun lastRuntimeProcessClearsForegroundServiceAndNotification() {
        val controller = RuntimeForegroundServiceController(context)
        val listener = RuntimeForegroundProcessListener(controller)

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "terminal", 1L))
        await("foreground service must become active") {
            controller.snapshot().state == RuntimeBackgroundState.ACTIVE &&
                isServiceRunning() &&
                (!notificationVisibilityRequired || hasForegroundNotification())
        }

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "command", 2L))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "terminal", 1L))
        Thread.sleep(STABLE_ACTIVE_WINDOW_MILLIS)
        assertEquals(RuntimeBackgroundState.ACTIVE, controller.snapshot().state)
        assertTrue(isServiceRunning())
        assertNotificationVisibleIfRequired()

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "command", 2L))
        await("last process must stop the foreground service and remove its notification") {
            controller.snapshot().state == RuntimeBackgroundState.STOPPED &&
                !isServiceRunning() &&
                (!notificationVisibilityRequired || !hasForegroundNotification())
        }

        // Duplicate terminal-close events must not recreate a service or a notification.
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "command", 2L))
        Thread.sleep(STABLE_STOPPED_WINDOW_MILLIS)
        assertFalse(isServiceRunning())
        if (notificationVisibilityRequired) assertFalse(hasForegroundNotification())
    }

    private fun stopServiceAndWait() {
        RuntimeForegroundServiceController(context).stop()
        context.stopService(android.content.Intent(context, RuntimeForegroundService::class.java))
        await("foreground service cleanup") {
            !isServiceRunning() &&
                RuntimeBackgroundLeaseStore(context).snapshot().state == RuntimeBackgroundState.STOPPED
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == RuntimeForegroundService::class.java.name
        }
    }

    private fun hasForegroundNotification(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications.any { notification ->
            notification.packageName == context.packageName &&
                notification.id == RuntimeForegroundService.NOTIFICATION_ID &&
                notification.notification.category == Notification.CATEGORY_SERVICE
        }
    }

    private fun assertNotificationVisibleIfRequired() {
        if (notificationVisibilityRequired) {
            assertTrue("foreground notification must be posted", hasForegroundNotification())
        }
    }

    private val notificationVisibilityRequired: Boolean
        get() = InstrumentationRegistry.getArguments()
            .getString(ARG_REQUIRE_NOTIFICATION_VISIBILITY) == "true"

    private fun event(
        kind: RuntimeHostProcessEventKind,
        sessionId: String,
        processId: Long,
    ) = RuntimeHostProcessEvent(
        kind = kind,
        sessionId = sessionId,
        processId = processId,
        timestampEpochMillis = 1L,
    )

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue(description, condition())
    }

    private companion object {
        const val ARG_REQUIRE_NOTIFICATION_VISIBILITY = "requireNotificationVisibility"
        const val WAIT_TIMEOUT_MILLIS = 8_000L
        const val POLL_INTERVAL_MILLIS = 100L
        const val STABLE_ACTIVE_WINDOW_MILLIS = 250L
        const val STABLE_STOPPED_WINDOW_MILLIS = 250L
    }
}
