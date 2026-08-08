package dev.alpine.workspace.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceOperationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceShareFilePublisherInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val publisher = WorkspaceShareFilePublisher(context)

    @Test
    fun publishUsesAppPrivateShareDirectoryAndAtomicReadableFile() {
        val expected = "share 안전성".encodeToByteArray()

        val target = publisher.publish("report.txt", expected, maxBytes = 1024)

        assertEquals("report.txt", target.name)
        assertEquals(
            context.cacheDir.resolve("workspace-shares").canonicalFile,
            target.parentFile?.canonicalFile,
        )
        assertArrayEquals(expected, target.readBytes())
    }

    @Test
    fun publishRejectsPathEscapeAndSizeOverflowWithStableErrors() {
        val pathError = assertThrows(WorkspaceOperationException::class.java) {
            publisher.publish("../outside.txt", byteArrayOf(1), maxBytes = 2)
        }
        assertEquals(WorkspaceErrorCode.INVALID_PATH, pathError.errorCode)

        val limitError = assertThrows(WorkspaceOperationException::class.java) {
            publisher.publish("large.txt", ByteArray(3), maxBytes = 2)
        }
        assertEquals(WorkspaceErrorCode.LIMIT_EXCEEDED, limitError.errorCode)
    }
}
