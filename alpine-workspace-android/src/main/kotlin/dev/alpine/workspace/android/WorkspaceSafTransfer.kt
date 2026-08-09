package dev.alpine.workspace.android

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceOperationException
import dev.alpine.workspace.api.WorkspacePath
import java.io.ByteArrayOutputStream

/**
 * Bounded Storage Access Framework transfer adapter. A URI is read only for the explicit
 * user-selected operation and no persisted external-document capability is retained.
 */
class WorkspaceSafTransfer(private val resolver: ContentResolver) {
    data class ImportedDocument(val name: String, val bytes: ByteArray)

    companion object {
        /**
         * Conservative API-level ceiling for callers that do not have a narrower workspace
         * policy.  Consumers should still pass their own [WorkspaceLimits] value explicitly.
         */
        const val DEFAULT_MAX_EXPORT_BYTES: Long = 16L * 1024L * 1024L
    }

    fun readImport(uri: Uri, maxBytes: Long): ImportedDocument {
        if (maxBytes <= 0) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        val bytes = externalIo {
            resolver.openInputStream(uri)?.use { input ->
                readBounded(input, maxBytes)
            } ?: fail(WorkspaceErrorCode.IO_FAILED)
        }
        return ImportedDocument(safeDisplayName(uri), bytes)
    }

    /**
     * Writes only to the explicitly chosen SAF destination after enforcing a bounded payload.
     *
     * The size check deliberately runs before [ContentResolver.openOutputStream] so an oversized
     * request cannot create or truncate a provider document.  SAF providers do not offer a
     * portable atomic-replace primitive; app-private atomic staging is handled separately by
     * [WorkspaceShareFilePublisher].
     */
    @JvmOverloads
    fun writeExport(
        bytes: ByteArray,
        uri: Uri,
        maxBytes: Long = DEFAULT_MAX_EXPORT_BYTES,
    ) {
        if (maxBytes <= 0 || bytes.size.toLong() > maxBytes) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        externalIo {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: fail(WorkspaceErrorCode.IO_FAILED)
        }
    }

    private fun safeDisplayName(uri: Uri): String {
        val raw = queryDisplayName(uri) ?: "imported.txt"
        val sanitized = raw
            .replace(Regex("[\\\\/\\u0000]"), "_")
            .trim()
            .take(120)
            .ifBlank { "imported.txt" }
        return runCatching { WorkspacePath.ROOT.resolve(sanitized).name }
            .getOrElse { "imported.txt" }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Collapses picker/provider I/O causes to the public workspace error contract. */
    private inline fun <T> externalIo(action: () -> T): T = try {
        action()
    } catch (error: WorkspaceOperationException) {
        throw error
    } catch (_: Throwable) {
        fail(WorkspaceErrorCode.IO_FAILED)
    }

    private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceOperationException(code)
}
