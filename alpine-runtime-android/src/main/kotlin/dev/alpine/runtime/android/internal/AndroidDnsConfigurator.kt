package dev.alpine.runtime.android.internal

import android.content.Context
import android.net.ConnectivityManager
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import java.io.File
import java.nio.file.Files

/** Writes Android's active DNS servers into the isolated Alpine rootfs without logging them. */
internal class AndroidDnsConfigurator(context: Context) {
    private val appContext = context.applicationContext

    fun refresh(rootfsDirectory: File) {
        val rootfs = rootfsDirectory.canonicalFile
        val etcDirectory = File(rootfs, "etc")
        if (!etcDirectory.exists() && !etcDirectory.mkdirs()) {
            throw RuntimeOperationException(RuntimeErrorCode.STORAGE_UNAVAILABLE)
        }
        if (Files.isSymbolicLink(etcDirectory.toPath()) ||
            !etcDirectory.canonicalFile.toPath().startsWith(rootfs.toPath())
        ) {
            throw RuntimeOperationException(RuntimeErrorCode.HEALTH_CHECK_FAILED)
        }
        val servers = runCatching {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            val network = manager?.activeNetwork
            if (network == null) emptyList() else {
                manager.getLinkProperties(network)?.dnsServers.orEmpty()
                    .mapNotNull { it.hostAddress }
                    .filter { it.isNotBlank() && '\n' !in it && '\r' !in it }
                    .distinct()
            }
        }.getOrDefault(emptyList())
        if (servers.isEmpty()) return

        val target = File(etcDirectory, "resolv.conf")
        if (Files.isSymbolicLink(target.toPath()) && !target.delete()) {
            throw RuntimeOperationException(RuntimeErrorCode.STORAGE_UNAVAILABLE)
        }
        val temporary = File(etcDirectory, "resolv.conf.tmp")
        try {
            temporary.writeText(
                servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" },
                Charsets.US_ASCII,
            )
            if (target.exists() && !target.delete()) {
                throw RuntimeOperationException(RuntimeErrorCode.STORAGE_UNAVAILABLE)
            }
            if (!temporary.renameTo(target)) {
                throw RuntimeOperationException(RuntimeErrorCode.STORAGE_UNAVAILABLE)
            }
        } finally {
            temporary.delete()
        }
    }
}
