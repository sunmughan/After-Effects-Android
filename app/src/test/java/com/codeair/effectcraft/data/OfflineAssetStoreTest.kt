package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            assertEquals("mov", asset.extension)
            assertTrue(store.fileFor(asset.sha256)?.name?.endsWith(".mov") == true)
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
            assertEquals(1, root.listFiles { file -> file.isFile && file.name.startsWith("${first.sha256}.") }!!.size)
            assertTrue(File(root, "${first.sha256}.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reimportRepairsSameSizeCorruptedContentAndKeepsFirstMetadata() {
        val root = Files.createTempDirectory("effectcraft-assets-repair-same-size").toFile()
        try {
            val store = OfflineAssetStore(root)
            val original = byteArrayOf(11, 22, 33, 44, 55)
            val asset = store.import(ByteArrayInputStream(original), "original.bin", "application/octet-stream")
            val payload = File(root, "${asset.sha256}.${asset.extension}")
            assertTrue(store.verify(asset.sha256))

            // Simulate same-length bit rot: a size-only check would accept it.
            payload.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            assertFalse(store.verify(asset.sha256))

            val repaired = store.import(ByteArrayInputStream(original), "renamed.mov", "video/quicktime")
            assertEquals("Duplicate imports preserve the first catalog metadata", asset, repaired)
            assertTrue("Reimport must restore the indexed SHA-256 payload", store.verify(asset.sha256))
            store.open(asset.sha256).use { assertTrue(original.contentEquals(it.readBytes())) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reimportRepairsTruncatedContentAddressedPayload() {
        val root = Files.createTempDirectory("effectcraft-assets-repair-truncated").toFile()
        try {
            val store = OfflineAssetStore(root)
            val original = ByteArray(128) { it.toByte() }
            val asset = store.import(ByteArrayInputStream(original), "original.dat")
            File(root, "${asset.sha256}.${asset.extension}").writeBytes(original.copyOfRange(0, 17))
            assertFalse(store.verify(asset.sha256))

            store.import(ByteArrayInputStream(original), "recovered.dat")
            assertTrue(store.verify(asset.sha256))
            store.open(asset.sha256).use { assertTrue(original.contentEquals(it.readBytes())) }
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".import-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun catalogSurvivesStoreRecreationAndSortsByDisplayName() {
        val root = Files.createTempDirectory("effectcraft-assets-reopen").toFile()
        try {
            val firstStore = OfflineAssetStore(root)
            firstStore.import(ByteArrayInputStream(byteArrayOf(1)), "zeta.png", "image/png")
            firstStore.import(ByteArrayInputStream(byteArrayOf(2)), "Alpha.mov", "video/quicktime")

            val reopened = OfflineAssetStore(root)
            assertEquals(listOf("Alpha.mov", "zeta.png"), reopened.list().map { it.displayName })
            reopened.open(reopened.list().first().sha256).use { assertEquals(2, it.read()) }
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
    fun cancellationDuringStreamingImportLeavesNoIndexPayloadOrTemporaryFile() {
        val root = Files.createTempDirectory("effectcraft-assets-cancel-stream").toFile()
        try {
            val store = OfflineAssetStore(root)
            var reads = 0
            val source = object : java.io.InputStream() {
                override fun read(): Int = if (reads++ < 100) 7 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads >= 100) return -1
                    buffer[offset] = 7
                    reads++
                    return 1
                }
            }
            var checks = 0
            val failure = runCatching {
                store.import(source, "cancelled.bin", cancelRequested = { ++checks >= 4 })
            }.exceptionOrNull()

            assertTrue(failure is java.util.concurrent.CancellationException)
            assertTrue(store.list().isEmpty())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".import-") })
            assertFalse(root.listFiles()!!.any { it.name.matches(Regex("[a-f0-9]{64}\\.bin")) })
            assertFalse(File(root, "assets.properties").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enforcesAggregateLibraryQuotaWithoutPublishingRejectedPayload() {
        val root = Files.createTempDirectory("effectcraft-assets-library-limit").toFile()
        try {
            val store = OfflineAssetStore(root, maxAssetBytes = 8, maxLibraryBytes = 5)
            val first = store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "first.bin")
            val failure = runCatching {
                store.import(ByteArrayInputStream(byteArrayOf(4, 5, 6)), "second.bin")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("total import limit"))
            assertEquals(listOf(first), store.list())
            assertEquals(3L, store.list().sumOf { it.sizeBytes })
            assertEquals(1, root.listFiles { file -> file.isFile && file.name.matches(Regex("[a-f0-9]{64}\\.bin")) }!!.size)
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicatePayloadDoesNotConsumeAggregateQuotaTwice() {
        val root = Files.createTempDirectory("effectcraft-assets-library-dedupe-limit").toFile()
        try {
            val store = OfflineAssetStore(root, maxAssetBytes = 8, maxLibraryBytes = 3)
            val bytes = byteArrayOf(7, 8, 9)
            val first = store.import(ByteArrayInputStream(bytes), "clip.bin")
            val duplicate = store.import(ByteArrayInputStream(bytes), "renamed.mov")

            assertEquals(first, duplicate)
            assertEquals(3L, store.list().sumOf { it.sizeBytes })
            assertEquals(1, store.list().size)
            assertTrue(File(root, "${first.sha256}.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackNewPayloadWhenIndexPublicationFails() {
        val root = Files.createTempDirectory("effectcraft-assets-index-failure").toFile()
        try {
            assertTrue(File(root, "assets.properties.tmp").mkdir())
            val store = OfflineAssetStore(root)
            val failure = runCatching {
                store.import(ByteArrayInputStream(byteArrayOf(9, 8, 7)), "rollback.bin")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(store.list().isEmpty())
            assertFalse(root.listFiles()!!.any { it.name.matches(Regex("[a-f0-9]{64}\\.bin")) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedAssetIdsAndRemovesIndexAndPayload() {
        val root = Files.createTempDirectory("effectcraft-assets-delete").toFile()
        try {
            val store = OfflineAssetStore(root)
            val asset = store.import(ByteArrayInputStream("x".toByteArray()), "x.txt")
            assertTrue(runCatching { store.get("not-a-hash") }.isFailure)
            assertTrue(store.delete(asset.sha256))
            assertNull(store.get(asset.sha256))
            assertFalse(File(root, "${asset.sha256}.${asset.extension}").exists())
            assertFalse(store.delete(asset.sha256))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifyAllDetectsSameSizePayloadCorruption() {
        val root = Files.createTempDirectory("effectcraft-assets-verify").toFile()
        try {
            val store = OfflineAssetStore(root)
            val asset = store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), "verified.bin")
            assertTrue(store.verify(asset.sha256))
            assertTrue(store.verifyAll().isEmpty())

            // Preserve the indexed size to prove verification checks SHA-256, not only length.
            File(root, "${asset.sha256}.${asset.extension}").writeBytes(byteArrayOf(4, 3, 2, 1))
            assertFalse(store.verify(asset.sha256))
            assertEquals(listOf(asset), store.verifyAll())
        } finally {
            root.deleteRecursively()
        }
    }
    
    @Test
    fun catalogCorruptionFailsClosedBeforeGarbageCollectionDeletesPayloads() {
        val root = Files.createTempDirectory("effectcraft-assets-corrupt-catalog").toFile()
        try {
            val store = OfflineAssetStore(root)
            val asset = store.import(ByteArrayInputStream(byteArrayOf(9, 8, 7)), "live.bin")
            val payload = File(root, "${asset.sha256}.${asset.extension}")
            assertTrue(payload.isFile)

            val index = File(root, "assets.properties")
            val properties = java.util.Properties().apply {
                index.inputStream().use(::load)
            }
            properties.setProperty("not-a-sha256", properties.getProperty(asset.sha256))
            index.outputStream().use { properties.store(it, "corruption injection") }

            assertTrue("Catalog corruption must not be silently omitted by list()", runCatching {
                store.list()
            }.isFailure)
            assertTrue("GC must abort before deleting any payload when the index is corrupt", runCatching {
                store.collectGarbage(emptySet())
            }.isFailure)
            assertTrue("The indexed payload must survive fail-closed GC", payload.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun independentStoresSerializeConcurrentIndexUpdatesWithoutLostAssets() {
        val root = Files.createTempDirectory("effectcraft-assets-concurrent-stores").toFile()
        val workers = 6
        val writesPerWorker = 20
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val stores = List(workers) { OfflineAssetStore(File(root, "shared")) }
            val futures = stores.mapIndexed { worker, store ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(writesPerWorker) { index ->
                        val bytes = "worker-$worker-asset-$index".toByteArray()
                        store.import(ByteArrayInputStream(bytes), "w$worker-$index.bin")
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            val reopened = OfflineAssetStore(File(root, "shared"))
            val assets = reopened.list()
            assertEquals(workers * writesPerWorker, assets.size)
            assertEquals(workers * writesPerWorker, assets.map { it.sha256 }.toSet().size)
            assertTrue("Every committed payload must verify", reopened.verifyAll().isEmpty())
            assertFalse(File(root, "shared/assets.properties.tmp").exists())
            assertFalse(File(root, "shared").listFiles()!!.any { it.name.startsWith(".import-") })
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

}
