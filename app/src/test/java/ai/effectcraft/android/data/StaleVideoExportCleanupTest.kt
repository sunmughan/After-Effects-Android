package ai.effectcraft.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StaleVideoExportCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun removesOnlyOldVideoStagingFiles() {
        val now = 2_000_000_000L
        val stale = temp.newFile("effectcraft-video-abandoned.mp4")
        stale.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(stale.setLastModified(now - 86_400_001L))
        val recent = temp.newFile("effectcraft-video-active.mp4")
        recent.writeBytes(byteArrayOf(4))
        assertTrue(recent.setLastModified(now - 60_000L))
        val unrelated = temp.newFile("user-video.mp4")
        unrelated.writeBytes(byteArrayOf(5))
        assertTrue(unrelated.setLastModified(now - 900_000_000L))
        val directory = temp.newFolder("effectcraft-video-directory.mp4")

        val result = StaleVideoExportCleanup.cleanup(temp.root, nowMillis = now)

        assertEquals(1, result.removed)
        assertEquals(1, result.retained)
        assertTrue(result.failed.isEmpty())
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
        assertTrue(directory.exists())
    }

    @Test
    fun rejectsNonPositiveRetentionAndDoesNotCreateMissingCacheDirectory() {
        val missing = File(temp.root, "missing-cache")
        assertEquals(0, StaleVideoExportCleanup.cleanup(missing).removed)
        assertFalse(missing.exists())
        assertThrows(IllegalArgumentException::class.java) {
            StaleVideoExportCleanup.cleanup(temp.root, maxAgeMillis = 0L)
        }
    }
}
