package dev.alpine.runtime.artifact.play

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

internal enum class PlayAssetFetchState {
    FETCHING,
    COMPLETED,
    FAILED,
    CANCELED,
    WAITING_FOR_USER,
}

internal fun interface PlayAssetFetchSubscription : AutoCloseable {
    override fun close()
}

internal interface PlayAssetPackClient {
    fun installedAssetsDirectory(packName: String): File?
    fun fetch(packName: String, listener: (PlayAssetFetchState) -> Unit): PlayAssetFetchSubscription
    fun cancel(packName: String)
}

internal class GooglePlayAssetPackClient(context: Context) : PlayAssetPackClient {
    private val manager = AssetPackManagerFactory.getInstance(context.applicationContext)

    override fun installedAssetsDirectory(packName: String): File? =
        manager.getPackLocation(packName)?.assetsPath()?.let(::File)?.takeIf(File::isDirectory)

    override fun fetch(
        packName: String,
        listener: (PlayAssetFetchState) -> Unit,
    ): PlayAssetFetchSubscription {
        val stateListener = com.google.android.play.core.assetpacks.AssetPackStateUpdateListener { state ->
            if (state.name() != packName) return@AssetPackStateUpdateListener
            listener(
                when (state.status()) {
                    AssetPackStatus.COMPLETED -> PlayAssetFetchState.COMPLETED
                    AssetPackStatus.FAILED -> PlayAssetFetchState.FAILED
                    AssetPackStatus.CANCELED -> PlayAssetFetchState.CANCELED
                    AssetPackStatus.WAITING_FOR_WIFI,
                    AssetPackStatus.REQUIRES_USER_CONFIRMATION,
                    -> PlayAssetFetchState.WAITING_FOR_USER
                    else -> PlayAssetFetchState.FETCHING
                },
            )
        }
        manager.registerListener(stateListener)
        manager.fetch(listOf(packName)).addOnFailureListener {
            listener(PlayAssetFetchState.FAILED)
        }
        return PlayAssetFetchSubscription { manager.unregisterListener(stateListener) }
    }

    override fun cancel(packName: String) {
        manager.cancel(listOf(packName))
    }
}
