package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
}
