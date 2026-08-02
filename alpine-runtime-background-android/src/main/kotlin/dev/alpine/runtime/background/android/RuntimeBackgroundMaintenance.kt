package dev.alpine.runtime.background.android

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Schedules a deferrable stale-transition audit; it never starts Alpine or replays user work. */
class RuntimeBackgroundMaintenance(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueueRecoveryAudit(initialDelayMillis: Long = 0L): UUID {
        require(initialDelayMillis >= 0) { "initialDelayMillis must not be negative" }
        val request = OneTimeWorkRequest.Builder(RuntimeBackgroundMaintenanceWorker::class.java)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "alpine-runtime-background-recovery-audit"
    }
}

class RuntimeBackgroundMaintenanceWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        RuntimeBackgroundLeaseStore(applicationContext).recoverStaleTransition()
        return Result.success()
    }
}
