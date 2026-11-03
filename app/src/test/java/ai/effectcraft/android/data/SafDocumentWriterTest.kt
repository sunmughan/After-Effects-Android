package ai.effectcraft.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafDocumentWriterTest {
    @Test
    fun copiesLargeArtifactInBoundedChunksAndReportsBytes() {
        val source = ByteArray(180_123) { (it * 31).toByte() }
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        var activeChecks = 0
        var rollbackCalled = false

        val copied = SafDocumentWriter.copyTo(
            source = ByteArrayInputStream(source),
            openOutput = { destination },
            rollbackDestination = { rollbackCalled = true },
            bufferSize = 16 * 1024,
            ensureActive = { activeChecks++ },
            onBytesCopied = progress::add
        )

        assertEquals(source.size.toLong(), copied)
        assertArrayEquals(source, destination.toByteArray())
        assertTrue("Cancellation is checked per chunk", activeChecks > 10)
        assertEquals(source.size.toLong(), progress.last())
        assertFalse("Successful copy must not roll back", rollbackCalled)
    }

    @Test
    fun cancellationDuringCopyRollsBackPartialDestination() {
        val source = ByteArray(100_000) { it.toByte() }
        val destination = ByteArrayOutputStream()
        var checks = 0
        var rollbackCalled = false

        try {
            SafDocumentWriter.copyTo(
                source = ByteArrayInputStream(source),
                openOutput = { destination },
                rollbackDestination = { rollbackCalled = true },
                bufferSize = 8_192,
                ensureActive = {
                    checks++
                    if (checks == 3) throw CancellationException("cancel test")
                }
            )
            throw AssertionError("Expected cancellation")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel test", cancelled.message)
        }

        assertTrue("Partial output must be deleted by the rollback callback", rollbackCalled)
        assertTrue(destination.size() in 1 until source.size)
    }

    @Test
    fun outputFailureKeepsOriginalExceptionAndAttemptsRollback() {
        var rollbackCalled = false
        val brokenOutput = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("provider write failed")
            }
        }

        try {
            SafDocumentWriter.copyTo(
                source = ByteArrayInputStream(ByteArray(32)),
                openOutput = { brokenOutput },
                rollbackDestination = { rollbackCalled = true; throw IOException("delete unavailable") },
                bufferSize = 8
            )
            throw AssertionError("Expected provider failure")
        } catch (failure: IOException) {
            assertEquals("provider write failed", failure.message)
            assertEquals(1, failure.suppressed.size)
            assertEquals("delete unavailable", failure.suppressed.single().message)
        }
        assertTrue(rollbackCalled)
    }

    @Test
    fun rejectsUnboundedOrZeroCopyBuffer() {
        assertInvalidBuffer(0)
        assertInvalidBuffer(1024 * 1024 + 1)
    }

    private fun assertInvalidBuffer(size: Int) {
        try {
            SafDocumentWriter.copyTo(
                source = ByteArrayInputStream(byteArrayOf()),
                openOutput = { ByteArrayOutputStream() },
                rollbackDestination = {},
                bufferSize = size
            )
            throw AssertionError("Expected invalid buffer rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: the allocation is bounded before any stream is opened.
        }
    }
}
