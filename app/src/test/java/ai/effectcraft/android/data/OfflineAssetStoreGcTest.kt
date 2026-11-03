package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class OfflineAssetStoreGcTest {
    @Test
    fun verifyDetectsMissingAndCorruptPayloads() {
        val root = Files.createTempDirectory("effectcraft-gc-verify").toFile()
        try {
            val store = OfflineAssetStore(root)
            val good = store.import(ByteArrayInputStream(byteArrayOf(1, 2)), "good.png")
            val corrupt = store.import(ByteArrayInputStream(byteArrayOf(3, 4)), "bad.png")
            assertTrue(store.verify(good.sha256))
            assertTrue(store.verifyAll().isEmpty())

            File(root, "${corrupt.sha256}.${corrupt.extension}").writeBytes(byteArrayOf(9, 9, 9))
            assertFalse(store.verify(corrupt.sha256))
            assertEquals(listOf(corrupt.sha256), store.verifyAll().map { it.sha256 })

            assertTrue(store.delete(corrupt.sha256))
            assertTrue(store.verifyAll().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun garbageCollectionRemovesOnlyUnreferencedAssetsAndOrphans() {
        val root = Files.createTempDirectory("effectcraft-gc").toFile()
        try {
            val store = OfflineAssetStore(root)
            val kept = store.import(ByteArrayInputStream(byteArrayOf(1)), "kept.png")
            val dropped = store.import(ByteArrayInputStream(byteArrayOf(2)), "dropped.png")
            // Orphan payload: a content-addressed file with no index entry.
            val orphanHash = "e".repeat(64)
            File(root, "$orphanHash.png").writeBytes(byteArrayOf(7))

            val result = store.collectGarbage(setOf(kept.sha256))
            assertEquals(listOf(dropped.sha256), result.deleted.map { it.sha256 })
            assertEquals(1, result.orphanPayloadsRemoved)
            assertTrue(result.failed.isEmpty())
            assertEquals(listOf(kept.sha256), store.list().map { it.sha256 })
            assertFalse(File(root, "$orphanHash.png").exists())

            // Second collection is a no-op: collection is idempotent.
            val again = store.collectGarbage(setOf(kept.sha256))
            assertTrue(again.deleted.isEmpty())
            assertEquals(0, again.orphanPayloadsRemoved)
        } finally {
            root.deleteRecursively()
        }
    }
}
