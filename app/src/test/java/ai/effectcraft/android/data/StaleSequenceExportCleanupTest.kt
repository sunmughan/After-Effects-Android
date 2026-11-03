package ai.effectcraft.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StaleSequenceExportCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun removesOnlyOldSequenceArchivesAndRetainsRecentOrUnrelatedFiles() {
        val now = 2_000_000_000L
        val stale = temp.newFile("effectcraft-sequence-abandoned.zip")
        stale.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(stale.setLastModified(now - 86_400_001L))

        val recent = temp.newFile("effectcraft-sequence-active.zip")
        recent.writeBytes(byteArrayOf(4))
        assertTrue(recent.setLastModified(now - 60_000L))

        val unrelated = temp.newFile("user-export.zip")
        unrelated.writeBytes(byteArrayOf(5))
        assertTrue(unrelated.setLastModified(now - 900_000_000L))

        val result = StaleSequenceExportCleanup.cleanup(temp.root, nowMillis = now)

        assertEquals(1, result.removed)
        assertEquals(1, result.retained)
        assertTrue(result.failed.isEmpty())
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun retainsUnknownFutureAndFreshTimestampsAndIgnoresDirectories() {
        val now = 20_000_000L
        val unknown = temp.newFile("effectcraft-sequence-unknown.zip")
        assertTrue(unknown.setLastModified(0L))
        val future = temp.newFile("effectcraft-sequence-future.zip")
        assertTrue(future.setLastModified(now + 1_000L))
        val directory = temp.newFolder("effectcraft-sequence-directory.zip")

        val result = StaleSequenceExportCleanup.cleanup(temp.root, nowMillis = now)

        assertEquals(0, result.removed)
        assertEquals(2, result.retained)
        assertTrue(result.failed.isEmpty())
        assertTrue(unknown.exists())
        assertTrue(future.exists())
        assertTrue(directory.exists())
    }

    @Test
    fun rejectsNonPositiveRetentionAndDoesNotCreateMissingCacheDirectory() {
        val missing = File(temp.root, "missing-cache")
        assertEquals(0, StaleSequenceExportCleanup.cleanup(missing).removed)
        assertFalse(missing.exists())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StaleSequenceExportCleanup.cleanup(temp.root, maxAgeMillis = 0L)
        }
    }
}
