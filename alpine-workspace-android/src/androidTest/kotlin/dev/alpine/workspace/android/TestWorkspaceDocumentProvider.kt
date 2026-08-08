package dev.alpine.workspace.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Test-only content provider used to exercise a real content:// resolver boundary. */
class TestWorkspaceDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
        addRow(arrayOf("folder/unsafe\\name.txt"))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = when (uri.lastPathSegment) {
            "source", "export" -> File(requireNotNull(context).cacheDir, "workspace-saf-${uri.lastPathSegment}.txt")
            else -> throw FileNotFoundException(uri.toString())
        }
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
