package dev.alpine.workspace.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceOperationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceSafTransferInstrumentedTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val source = Uri.parse("content://dev.alpine.workspace.android.test.documents/source")
    private val export = Uri.parse("content://dev.alpine.workspace.android.test.documents/export")
    private val missing = Uri.parse("content://dev.alpine.workspace.android.test.documents/missing")
    private lateinit var transfer: WorkspaceSafTransfer

    @Before
    fun setUp() {
        transfer = WorkspaceSafTransfer(resolver)
    }

    @Test
    fun importSanitizesDisplayNameAndPreservesBoundedContentThroughContentResolver() {
        val expected = "Alpine workspace\n안전한 가져오기".encodeToByteArray()
        resolver.openOutputStream(source, "w")!!.use { it.write(expected) }

        val imported = transfer.readImport(source, maxBytes = expected.size.toLong())

        assertEquals("folder_unsafe_name.txt", imported.name)
        assertArrayEquals(expected, imported.bytes)
    }

    @Test
    fun exportWritesOnlyExplicitDestinationUri() {
        val expected = byteArrayOf(1, 2, 3, 4)

        transfer.writeExport(expected, export, maxBytes = expected.size.toLong())

        assertArrayEquals(expected, resolver.openInputStream(export)!!.use { it.readBytes() })
    }

    @Test
    fun exportOverLimitIsRejectedBeforeTheDestinationIsOpenedOrChanged() {
        val original = byteArrayOf(7, 8)
        resolver.openOutputStream(export, "w")!!.use { it.write(original) }

        val error = assertThrows(WorkspaceOperationException::class.java) {
            transfer.writeExport(byteArrayOf(1, 2, 3), export, maxBytes = 2)
        }

        assertEquals(WorkspaceErrorCode.LIMIT_EXCEEDED, error.errorCode)
        assertArrayEquals(original, resolver.openInputStream(export)!!.use { it.readBytes() })
    }

    @Test
    fun importOverLimitAndUnavailableProviderBecomeStableWorkspaceErrors() {
        resolver.openOutputStream(source, "w")!!.use { it.write(byteArrayOf(1, 2, 3)) }

        val limitError = assertThrows(WorkspaceOperationException::class.java) {
            transfer.readImport(source, maxBytes = 2)
        }
        assertEquals(WorkspaceErrorCode.LIMIT_EXCEEDED, limitError.errorCode)

        val unavailableError = assertThrows(WorkspaceOperationException::class.java) {
            transfer.readImport(missing, maxBytes = 16)
        }
        assertEquals(WorkspaceErrorCode.IO_FAILED, unavailableError.errorCode)
    }
}
