package dev.alpine.runtime.background.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class RuntimeForegroundService : Service() {
    private lateinit var store: RuntimeBackgroundLeaseStore

    override fun onCreate() {
        super.onCreate()
        store = RuntimeBackgroundLeaseStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Alpine 작업",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "사용자가 시작한 Alpine 터미널 및 작업공간 실행 상태"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_STOP_STALE -> {
                if (intent.action == ACTION_STOP) RuntimeBackgroundHostRegistry.requestStop()
                store.set(RuntimeBackgroundState.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        store.set(RuntimeBackgroundState.ACTIVE)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The controller may stop the service directly instead of delivering ACTION_STOP.
        // Explicitly remove the foreground association here as well, rather than relying on
        // the legacy implicit cleanup that happens when a started service is destroyed.
        stopForeground(STOP_FOREGROUND_REMOVE)
        store.set(RuntimeBackgroundState.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Alpine 작업 실행 중")
            .setContentText("터미널 또는 작업이 실행 중입니다.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "작업 중지", stopPendingIntent).build())
            .build()
    }

    companion object {
        internal const val ACTION_START = "dev.alpine.runtime.background.START"
        internal const val ACTION_STOP = "dev.alpine.runtime.background.STOP"
        internal const val ACTION_STOP_STALE = "dev.alpine.runtime.background.STOP_STALE"
        private const val CHANNEL_ID = "alpine-runtime-work"
        internal const val NOTIFICATION_ID = 0xA11E
        private const val STOP_REQUEST_CODE = 0xA11E
    }
}
