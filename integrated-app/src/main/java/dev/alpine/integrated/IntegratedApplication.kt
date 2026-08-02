package dev.alpine.integrated

import android.app.Application
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.background.android.RuntimeBackgroundHostRegistry
import dev.alpine.runtime.background.android.RuntimeBackgroundMaintenance
import dev.alpine.runtime.background.android.RuntimeForegroundProcessListener
import dev.alpine.runtime.background.android.RuntimeForegroundServiceController
import dev.alpine.runtime.host.RuntimeHostController
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider

class IntegratedApplication : Application() {
    lateinit var runtimeManager: AlpineRuntimeManager
        private set
    lateinit var runtimeController: RuntimeHostController
        private set
    lateinit var backgroundController: RuntimeForegroundServiceController
        private set
    private var backgroundBinding: RuntimeSubscription? = null

    override fun onCreate() {
        super.onCreate()
        backgroundController = RuntimeForegroundServiceController(this)
        backgroundController.normalizeAfterProcessStart()
        runtimeManager = DefaultAndroidAlpineRuntimeFactory().create(
            this,
            AndroidRuntimeConfiguration(
                artifactProvider = BundledRuntimeArtifactProvider(this, Alpine321Arm64Pack.create()),
                processListener = RuntimeForegroundProcessListener(
                    controller = backgroundController,
                    onStartRejected = Runnable {
                        if (::runtimeManager.isInitialized) {
                            runtimeManager.stop(RuntimeStopReason.HOST_BACKGROUND_POLICY)
                        }
                    },
                ),
                runtimeDirectoryName = "alpine-integrated-runtime",
            ),
        )
        backgroundBinding = RuntimeBackgroundHostRegistry.bind {
            runtimeManager.stop(RuntimeStopReason.USER_REQUEST)
        }
        RuntimeBackgroundMaintenance(this).enqueueRecoveryAudit(15L * 60L * 1000L)
        runtimeController = RuntimeHostController(runtimeManager)
    }

    override fun onTerminate() {
        backgroundBinding?.close()
        backgroundController.stop()
        runtimeController.close()
        runtimeManager.close()
        super.onTerminate()
    }
}
