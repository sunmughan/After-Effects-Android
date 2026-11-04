package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectAssetManifestTest {
    private val firstHash = "a".repeat(64)
    private val secondHash = "b".repeat(64)
    private val thirdHash = "c".repeat(64)

    @Test
    fun linksReplacesAndPersistsEntriesAcrossReload() {
        val root = Files.createTempDirectory("effectcraft-manifest").toFile()
        try {
            val store = ProjectAssetManifestStore(root)
            val manifest = store.load("project-one")
            manifest.link(42, firstHash, "/orig/clip.mov")
            manifest.linkSequence(
                7,
                listOf(
                    ProjectAssetManifest.Frame("frame_001.png", secondHash),
                    ProjectAssetManifest.Frame("frame_002.png", thirdHash)
                )
            )
            store.save(manifest)

            val reopened = store.load("project-one")
            assertEquals(firstHash, reopened.entryFor(42)?.sha256)
            assertEquals("/orig/clip.mov", reopened.entryFor(42)?.lastKnownPath)
            assertFalse(reopened.entryFor(42)?.missing ?: true)
            assertEquals(
                listOf("frame_001.png", "frame_002.png"),
                reopened.entryFor(7)?.frames?.map { it.name }
            )
            assertEquals(setOf(firstHash, secondHash, thirdHash), reopened.allHashes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingFlagsAndReplaceRewriteSingleAndSequenceEntries() {
        val manifest = ProjectAssetManifest("project-two")
        manifest.link(1, firstHash)
        manifest.linkSequence(2, listOf(ProjectAssetManifest.Frame("f1.png", firstHash)))
        assertTrue(manifest.markMissing(1))
        assertFalse(manifest.markMissing(99))
        assertEquals(listOf(1L), manifest.missingEntries().map { it.itemId })

        assertEquals(2, manifest.replaceAsset(firstHash, secondHash))
        assertEquals(secondHash, manifest.entryFor(1)?.sha256)
        assertEquals(secondHash, manifest.entryFor(2)?.frames?.single()?.sha256)
        assertTrue(manifest.markPresent(1))
        assertTrue(manifest.missingEntries().isEmpty())
        assertEquals(0, manifest.replaceAsset(thirdHash, firstHash))
    }

    @Test
    fun storeReplacesAcrossProjectsAndReportsUnion() {
        val root = Files.createTempDirectory("effectcraft-manifest-multi").toFile()
        try {
            val store = ProjectAssetManifestStore(root)
            val first = store.load("project-a").also { it.link(1, firstHash) }
            val second = store.load("project-b").also { it.link(2, firstHash) }
            store.save(first)
            store.save(second)
            assertEquals(setOf("project-a", "project-b"), store.projectIds().toSet())
            assertEquals(setOf(firstHash), store.allReferencedHashes())

            assertEquals(2, store.replaceAssetEverywhere(firstHash, secondHash))
            assertEquals(setOf(secondHash), store.allReferencedHashes())
            assertTrue(store.delete("project-a"))
            assertFalse(store.delete("project-a"))
            assertEquals(listOf("project-b"), store.projectIds())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedManifestsAndInputs() {
        val manifest = ProjectAssetManifest("project-bad")
        assertTrue(runCatching { manifest.link(0, firstHash) }.isFailure)
        assertTrue(runCatching { manifest.link(1, "not-a-hash") }.isFailure)
        assertTrue(runCatching { manifest.link(1, firstHash.uppercase()) }.isFailure)
        assertTrue(runCatching { manifest.linkSequence(2, emptyList()) }.isFailure)
        assertTrue(runCatching {
            manifest.linkSequence(
                2,
                listOf(
                    ProjectAssetManifest.Frame("dup.png", firstHash),
                    ProjectAssetManifest.Frame("dup.png", secondHash)
                )
            )
        }.isFailure)
        assertTrue(runCatching {
            ProjectAssetManifest.fromJson("""{"format":"wrong","version":1,"projectId":"x","entries":[]}""")
        }.isFailure)
        assertTrue(runCatching {
            ProjectAssetManifest.fromJson("""{"format":"effectcraft-android-asset-manifest","version":99,"projectId":"x","entries":[]}""")
        }.isFailure)
        assertTrue(runCatching {
            ProjectAssetManifest.fromJson("""{"format":"effectcraft-android-asset-manifest","version":1,"projectId":"x","entries":[{"itemId":1,"kind":"single","sha256":"bad","missing":false}]}""")
        }.isFailure)
        assertTrue(runCatching { ProjectAssetManifest("") }.isFailure)
        assertTrue(runCatching { ProjectAssetManifestStore(File("/nonexistent-parent-xyz")).load("x") }.isFailure.not())
    }

    @Test
    fun manifestRoundTripPreservesEntriesRegardlessOfLinkOrder() {
        val manifest = ProjectAssetManifest("project-det")
        manifest.link(9, secondHash)
        manifest.link(3, firstHash)
        val again = ProjectAssetManifest("project-det")
        again.link(3, firstHash)
        again.link(9, secondHash)
        // Entries are sorted by item id, so link order must not matter; JSON
        // key order is not asserted because org.json does not contract it.
        assertEquals(manifest.entries(), again.entries())
        assertEquals(manifest.entries(), ProjectAssetManifest.fromJson(manifest.toJson()).entries())
        assertEquals(manifest.projectId, ProjectAssetManifest.fromJson(manifest.toJson()).projectId)
    }
}
