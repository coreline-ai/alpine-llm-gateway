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
import dev.alpine.runtime.bridge.LlmBridgeEndpointRegistry
import dev.alpine.runtime.bridge.LlmBridgeEnvironmentContributor
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider

class IntegratedApplication : Application() {
    lateinit var runtimeManager: AlpineRuntimeManager
        private set
    lateinit var runtimeController: RuntimeHostController
        private set
    lateinit var backgroundController: RuntimeForegroundServiceController
        private set
    lateinit var alpineLlmHost: IntegratedAlpineLlmHost
        private set
    private lateinit var bridgeEndpointRegistry: LlmBridgeEndpointRegistry
    private var backgroundBinding: RuntimeSubscription? = null

    override fun onCreate() {
        super.onCreate()
        backgroundController = RuntimeForegroundServiceController(this)
        backgroundController.normalizeAfterProcessStart()
        bridgeEndpointRegistry = LlmBridgeEndpointRegistry()
        val runtimeDirectoryName = "alpine-integrated-runtime"
        runtimeManager = DefaultAndroidAlpineRuntimeFactory().create(
            this,
            AndroidRuntimeConfiguration(
                artifactProvider = BundledRuntimeArtifactProvider(this, Alpine321Arm64Pack.create()),
                environmentContributors = listOf(
                    LlmBridgeEnvironmentContributor(bridgeEndpointRegistry),
                ),
                processListener = RuntimeForegroundProcessListener(
                    controller = backgroundController,
                    onStartRejected = Runnable {
                        if (::alpineLlmHost.isInitialized) {
                            alpineLlmHost.stop()
                        } else if (::runtimeManager.isInitialized) {
                            runtimeManager.stop(RuntimeStopReason.HOST_BACKGROUND_POLICY)
                        }
                    },
                ),
                runtimeDirectoryName = runtimeDirectoryName,
            ),
        )
        RuntimeBackgroundMaintenance(this).enqueueRecoveryAudit(15L * 60L * 1000L)
        runtimeController = RuntimeHostController(runtimeManager)
        alpineLlmHost = IntegratedAlpineLlmHost(
            context = this,
            runtimeManager = runtimeManager,
            runtimeHostController = runtimeController,
            endpointRegistry = bridgeEndpointRegistry,
            workspaceDirectory = java.io.File(filesDir, "$runtimeDirectoryName/workspace"),
        )
        backgroundBinding = RuntimeBackgroundHostRegistry.bind {
            alpineLlmHost.stop()
        }
    }

    override fun onTerminate() {
        backgroundBinding?.close()
        backgroundController.stop()
        alpineLlmHost.close()
        runtimeController.close()
        runtimeManager.close()
        super.onTerminate()
    }
}
