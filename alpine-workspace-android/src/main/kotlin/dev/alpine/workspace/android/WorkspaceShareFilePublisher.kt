package dev.alpine.workspace.android

import android.content.Context
import android.util.AtomicFile
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceOperationException
import dev.alpine.workspace.api.WorkspacePath
import java.io.File
import java.io.FileOutputStream

/**
 * Publishes one bounded workspace file into the app-private FileProvider cache directory.
 *
 * The returned [File] is not itself shareable: the host must convert it to a content URI with its
 * own non-exported FileProvider and grant read access only in the explicit share Intent.
 */
class WorkspaceShareFilePublisher(context: Context) {
    private val shareDirectory = File(context.applicationContext.cacheDir, SHARE_DIRECTORY_NAME)

    fun publish(displayName: String, bytes: ByteArray, maxBytes: Long): File {
        if (maxBytes <= 0) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        if (bytes.size.toLong() > maxBytes) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        val safeName = runCatching { WorkspacePath.ROOT.resolve(displayName).name }
            .getOrElse { fail(WorkspaceErrorCode.INVALID_PATH) }
        if (safeName.isBlank()) fail(WorkspaceErrorCode.INVALID_PATH)
        val directory = ensureShareDirectory()
        val target = File(directory, safeName).canonicalFile
        if (target.parentFile != directory.canonicalFile) fail(WorkspaceErrorCode.INVALID_PATH)

        val atomic = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
            return target
        } catch (_: Throwable) {
            output?.let(atomic::failWrite)
            fail(WorkspaceErrorCode.IO_FAILED)
        }
    }

    private fun ensureShareDirectory(): File {
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) fail(WorkspaceErrorCode.IO_FAILED)
        if (!shareDirectory.isDirectory) fail(WorkspaceErrorCode.IO_FAILED)
        return shareDirectory.canonicalFile
    }

    private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceOperationException(code)

    private companion object {
        const val SHARE_DIRECTORY_NAME = "workspace-shares"
    }
}
