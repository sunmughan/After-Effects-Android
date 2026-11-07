package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class OfflineAssetReferencesTest {
    private val firstHash = "a".repeat(64)
    private val secondHash = "b".repeat(64)

    @Test
    fun referencesPersistAndReplacingFootageReleasesOldAsset() {
        val root = Files.createTempDirectory("effectcraft-asset-refs").toFile()
        try {
            val file = File(root, "references.properties")
            val refs = OfflineAssetReferences(file)
            refs.setReference(42, firstHash)
            assertTrue(refs.isReferenced(firstHash))

            val reopened = OfflineAssetReferences(file)
            assertEquals(firstHash, reopened.assetFor(42))
            reopened.setReference(42, secondHash)
            assertFalse(reopened.isReferenced(firstHash))
            assertTrue(reopened.isReferenced(secondHash))
            assertEquals(setOf(secondHash), reopened.referencedAssetIds())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun itemIdsForReportsEveryItemMappedToAnAsset() {
        val root = Files.createTempDirectory("effectcraft-asset-refs-items").toFile()
        try {
            val refs = OfflineAssetReferences(File(root, "references.properties"))
            refs.setReference(3, firstHash)
            refs.setReference(9, firstHash)
            refs.setReference(4, secondHash)
            assertEquals(setOf(3L, 9L), refs.itemIdsFor(firstHash))
            assertEquals(setOf(4L), refs.itemIdsFor(secondHash))
            assertTrue(refs.itemIdsFor("c".repeat(64)).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun referencedAssetIdsFailClosedOnCorruptMetadata() {
        val root = Files.createTempDirectory("effectcraft-asset-refs-corrupt").toFile()
        try {
            val file = File(root, "references.properties")
            file.writeText("42=invalid-hash\n")
            val refs = OfflineAssetReferences(file)
            assertTrue("Invalid referenced hashes must not be silently ignored", runCatching {
                refs.referencedAssetIds()
            }.isFailure)

            file.writeText("not-an-item-id=$firstHash\n")
            assertTrue("Invalid item keys must not be silently ignored", runCatching {
                refs.referencedAssetIds()
            }.isFailure)

            file.writeText("42=$firstHash\n")
            assertEquals(setOf(firstHash), refs.referencedAssetIds())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesReferenceAndRejectsInvalidIdsOrHashes() {
        val root = Files.createTempDirectory("effectcraft-asset-refs-remove").toFile()
        try {
            val refs = OfflineAssetReferences(File(root, "references.properties"))
            assertFalse(refs.removeReference(7))
            refs.setReference(7, firstHash)
            assertTrue(refs.removeReference(7))
            assertNull(refs.assetFor(7))
            assertFalse(refs.isReferenced(firstHash))
            assertTrue(runCatching { refs.setReference(0, firstHash) }.isFailure)
            assertTrue(runCatching { refs.setReference(8, "bad") }.isFailure)
            assertTrue(runCatching { refs.assetFor(-1) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun independentInstancesSerializeConcurrentReferenceUpdatesWithoutLostItems() {
        val root = Files.createTempDirectory("effectcraft-asset-refs-concurrent").toFile()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val file = File(root, "references.properties")
            val workers = 8
            val writesPerWorker = 20
            val start = CountDownLatch(1)
            val futures = (0 until workers).map { worker ->
                executor.submit {
                    val refs = OfflineAssetReferences(file)
                    start.await()
                    repeat(writesPerWorker) { offset ->
                        val itemId = worker * writesPerWorker + offset + 1L
                        refs.setReference(itemId, if (offset % 2 == 0) firstHash else secondHash)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get() }

            val reopened = OfflineAssetReferences(file)
            // referencedAssetIds() is a distinct-hash set, not a footage-item
            // count: this workload intentionally maps 160 items onto two hashes.
            assertEquals(setOf(firstHash, secondHash), reopened.referencedAssetIds())
            assertEquals(workers * writesPerWorker / 2, reopened.itemIdsFor(firstHash).size)
            assertEquals(workers * writesPerWorker / 2, reopened.itemIdsFor(secondHash).size)
            repeat(workers * writesPerWorker) { offset ->
                val itemId = offset + 1L
                assertEquals(if ((offset % writesPerWorker) % 2 == 0) firstHash else secondHash, reopened.assetFor(itemId))
            }
            assertTrue("Durable writes must clean unique staging files", root.listFiles().orEmpty().none {
                it.name.startsWith("references.properties.tmp-")
            })
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

}
