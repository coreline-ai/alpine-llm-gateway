package dev.alpine.runtime.background.android

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build

class RuntimeForegroundServiceController @JvmOverloads constructor(
    context: Context,
    private val eventListener: RuntimeBackgroundEventListener = RuntimeBackgroundEventListener { },
) {
    private val appContext = context.applicationContext
    private val store = RuntimeBackgroundLeaseStore(appContext)

    /** Call once from Application.onCreate before constructing a runtime manager. */
    fun normalizeAfterProcessStart(): Boolean {
        val recovered = store.normalizeAfterProcessStart()
        if (recovered) {
            appContext.stopService(serviceIntent(RuntimeForegroundService.ACTION_STOP_STALE))
            eventListener.onEvent(RuntimeBackgroundEvent.RECOVERED_AFTER_PROCESS_DEATH)
        }
        return recovered
    }

    fun startUserVisibleWork(): RuntimeBackgroundStartResult {
        val state = store.snapshot().state
        if (state == RuntimeBackgroundState.START_REQUESTED || state == RuntimeBackgroundState.ACTIVE) {
            return RuntimeBackgroundStartResult.ALREADY_REQUESTED_OR_ACTIVE
        }
        store.set(RuntimeBackgroundState.START_REQUESTED)
        eventListener.onEvent(RuntimeBackgroundEvent.START_REQUESTED)
        return try {
            appContext.startForegroundService(serviceIntent(RuntimeForegroundService.ACTION_START))
            RuntimeBackgroundStartResult.START_REQUESTED
        } catch (_: SecurityException) {
            store.set(RuntimeBackgroundState.STOPPED)
            eventListener.onEvent(RuntimeBackgroundEvent.START_FAILED)
            RuntimeBackgroundStartResult.START_FAILED
        } catch (error: RuntimeException) {
            store.set(RuntimeBackgroundState.STOPPED)
            if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException) {
                eventListener.onEvent(RuntimeBackgroundEvent.START_NOT_ALLOWED)
                RuntimeBackgroundStartResult.START_NOT_ALLOWED
            } else {
                eventListener.onEvent(RuntimeBackgroundEvent.START_FAILED)
                RuntimeBackgroundStartResult.START_FAILED
            }
        }
    }

    fun stop() {
        val state = store.snapshot().state
        if (state == RuntimeBackgroundState.STOPPED) return
        store.set(RuntimeBackgroundState.STOP_REQUESTED)
        eventListener.onEvent(RuntimeBackgroundEvent.STOP_REQUESTED)
        appContext.stopService(serviceIntent(RuntimeForegroundService.ACTION_STOP))
        store.set(RuntimeBackgroundState.STOPPED)
        eventListener.onEvent(RuntimeBackgroundEvent.STOPPED)
    }

    fun snapshot(): RuntimeBackgroundSnapshot = store.snapshot()

    private fun serviceIntent(action: String): Intent =
        Intent(appContext, RuntimeForegroundService::class.java).setAction(action)
}
