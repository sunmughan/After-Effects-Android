package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class OfflineAssetStoreTest {
    @Test
    fun importsStreamsAndReopensContentAddressedAsset() {
        val root = Files.createTempDirectory("effectcraft-assets").toFile()
        try {
            val store = OfflineAssetStore(root)
            val bytes = "offline-media".toByteArray()
            val asset = store.import(ByteArrayInputStream(bytes), "clip.mov", "video/quicktime")

            assertEquals("clip.mov", asset.displayName)
            assertEquals("video/quicktime", asset.mimeType)
            assertEquals(bytes.size.toLong(), asset.sizeBytes)
            assertEquals(64, asset.sha256.length)
            assertEquals(asset, store.get(asset.sha256))
            assertEquals(asset, store.list().single())
            store.open(asset.sha256).use { assertTrue(bytes.contentEquals(it.readBytes())) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deduplicatesIdenticalPayloadsAndKeepsFirstLibraryMetadata() {
        val root = Files.createTempDirectory("effectcraft-assets-dedupe").toFile()
        try {
            val store = OfflineAssetStore(root)
            val bytes = byteArrayOf(1, 2, 3, 4)
            val first = store.import(ByteArrayInputStream(bytes), "first.bin", "application/octet-stream")
            val second = store.import(ByteArrayInputStream(bytes), "renamed.bin", "application/x-other")

            assertEquals(first.sha256, second.sha256)
            assertEquals(first, second)
            assertEquals(1, store.list().size)
            assertEquals(1, root.listFiles { file -> file.isFile && file.name.matches(Regex("[a-f0-9]{64}")) }!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedStreamWithoutPublishingAnAsset() {
        val root = Files.createTempDirectory("effectcraft-assets-limit").toFile()
        try {
            val store = OfflineAssetStore(root, maxAssetBytes = 4)
            val failure = runCatching {
                store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), "large.bin")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("import limit"))
            assertTrue(store.list().isEmpty())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatesAssetIdsAndRemovesIndexAndPayload() {
        val root = Files.createTempDirectory("effectcraft-assets-delete").toFile()
        try {
            val store = OfflineAssetStore(root)
            val asset = store.import(ByteArrayInputStream("x".toByteArray()), "x.txt")
            assertNull(store.get("not-a-hash"))
            assertTrue(store.delete(asset.sha256))
            assertNull(store.get(asset.sha256))
            assertFalse(File(root, asset.sha256).exists())
            assertFalse(store.delete(asset.sha256))
        } finally {
            root.deleteRecursively()
        }
    }
}
