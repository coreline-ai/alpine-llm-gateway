package dev.alpine.runtime.sample

import android.app.Application
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.host.RuntimeHostController
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider

class RuntimeSampleApplication : Application() {
    lateinit var runtimeManager: AlpineRuntimeManager
        private set
    lateinit var runtimeController: RuntimeHostController
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeManager = DefaultAndroidAlpineRuntimeFactory().create(
            this,
            AndroidRuntimeConfiguration(
                artifactProvider = BundledRuntimeArtifactProvider(this, Alpine321Arm64Pack.create()),
                runtimeDirectoryName = "alpine-runtime-xml-sample",
            ),
        )
        runtimeController = RuntimeHostController(runtimeManager)
    }

    override fun onTerminate() {
        runtimeController.close()
        runtimeManager.close()
        super.onTerminate()
    }
}
