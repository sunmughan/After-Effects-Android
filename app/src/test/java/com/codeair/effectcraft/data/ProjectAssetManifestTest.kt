package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    fun concurrentStoreInstancesPublishOnlyCompleteManifestAndUniqueTemps() {
        val root = Files.createTempDirectory("effectcraft-manifest-concurrent").toFile()
        try {
            val hashes = setOf(firstHash, secondHash, thirdHash)
            val start = java.util.concurrent.CountDownLatch(1)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val workers = (0 until 8).map { worker ->
                Thread {
                    try {
                        start.await()
                        repeat(12) { iteration ->
                            val manifest = ProjectAssetManifest("shared-project")
                            manifest.link(1, listOf(firstHash, secondHash, thirdHash)[(worker + iteration) % 3])
                            ProjectAssetManifestStore(root).save(manifest)
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }.apply { start() }
            }
            start.countDown()
            workers.forEach { it.join() }

            assertTrue("Concurrent manifest saves must all complete: $failures", failures.isEmpty())
            val saved = ProjectAssetManifestStore(root).load("shared-project")
            assertTrue("The published JSON must be parseable and complete", saved.entryFor(1)?.sha256?.let(hashes::contains) == true)
            assertTrue(
                "Unique temporary files must be cleaned after publication",
                root.listFiles().orEmpty().none { it.name.contains(".tmp-") }
            )
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
    fun replaceBatchAndIndependentSaveSerializeAcrossStoreInstances() {
        val root = Files.createTempDirectory("effectcraft-manifest-cross-instance").toFile()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val initial = ProjectAssetManifestStore(root)
            initial.load("project-a").also { it.link(1, firstHash) }.let(initial::save)
            initial.load("project-b").also { it.link(2, firstHash) }.let(initial::save)
            val targets = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }!!
                .sortedBy { it.name }
            val firstProject = ProjectAssetManifest.fromJson(targets.first().readText(Charsets.UTF_8)).projectId
            val failTarget = targets.last().name

            val publishReached = java.util.concurrent.CountDownLatch(1)
            val allowFailure = java.util.concurrent.CountDownLatch(1)
            val saveStarted = java.util.concurrent.CountDownLatch(1)
            val transactional = ProjectAssetManifestStore(root) { source, target ->
                if (target.name == failTarget && source.name.contains(".stage-")) {
                    publishReached.countDown()
                    check(allowFailure.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw java.io.IOException("Injected batch publish failure")
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // Use an explicit Callable so the captured failure is the Future's
            // value (rather than relying on Runnable/Callable SAM overload inference).
            val batch = executor.submit(java.util.concurrent.Callable<Exception?> {
                try {
                    transactional.replaceAssetEverywhere(firstHash, secondHash)
                    null
                } catch (failure: Exception) {
                    failure
                }
            })
            assertTrue("Batch must reach the injected second publish", publishReached.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val independent = ProjectAssetManifestStore(root)
            val save = executor.submit {
                saveStarted.countDown()
                independent.load(firstProject).also { it.link(1, thirdHash) }.let(independent::save)
            }
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            try {
                save.get(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                throw AssertionError("Independent save must wait while the shared-root batch is publishing/rolling back")
            } catch (_: java.util.concurrent.TimeoutException) {
                // Expected: the batch owns the root-wide transaction lock.
            }

            allowFailure.countDown()
            val batchFailure = batch.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(
                "Injected batch failure must be surfaced as IOException; actual=${batchFailure?.javaClass?.name ?: \"null\"}: ${batchFailure?.message}",
                batchFailure is java.io.IOException
            )
            save.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(
                "The independent save must run after rollback, not be overwritten by its backup",
                thirdHash,
                ProjectAssetManifestStore(root).load(firstProject).entryFor(1)?.sha256
            )
            assertEquals("Unaffected project rollback must restore its original asset", firstHash,
                ProjectAssetManifestStore(root).load(if (firstProject == "project-a") "project-b" else "project-a").entries().single().sha256)
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun replaceAssetEverywhereFailsBeforeWritesWhenAnyManifestIsCorrupt() {
        val root = Files.createTempDirectory("effectcraft-manifest-replace-safe").toFile()
        try {
            val store = ProjectAssetManifestStore(root)
            store.load("project-a").also { it.link(1, firstHash) }.let(store::save)
            File(root, "corrupt.json").writeText("{not-json")

            val failure = runCatching { store.replaceAssetEverywhere(firstHash, secondHash) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(firstHash, store.load("project-a").entryFor(1)?.sha256)

            assertTrue(File(root, "corrupt.json").delete())
            assertEquals(1, store.replaceAssetEverywhere(firstHash, secondHash))
            assertEquals(secondHash, store.load("project-a").entryFor(1)?.sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replaceAssetEverywhereRollsBackEarlierManifestWhenLaterPublishFails() {
        val root = Files.createTempDirectory("effectcraft-manifest-transaction").toFile()
        try {
            val initial = ProjectAssetManifestStore(root)
            initial.load("project-a").also { it.link(1, firstHash) }.let(initial::save)
            initial.load("project-b").also { it.link(2, firstHash) }.let(initial::save)
            val targets = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }!!
                .sortedBy { it.name }
            assertEquals(2, targets.size)
            val original = targets.associate { it.name to it.readText(Charsets.UTF_8) }
            val failTarget = targets.last().name
            val transactional = ProjectAssetManifestStore(root) { source, target ->
                if (target.name == failTarget && source.name.contains(".stage-")) {
                    throw java.io.IOException("Injected second manifest publish failure")
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val failure = runCatching { transactional.replaceAssetEverywhere(firstHash, secondHash) }.exceptionOrNull()
            assertTrue("The injected publish failure must be surfaced", failure is java.io.IOException)
            targets.forEach { target ->
                assertEquals("Every manifest must be restored after a partial publish", original[target.name], target.readText(Charsets.UTF_8))
            }
            assertTrue("Staged/backup files must be cleaned after rollback",
                root.listFiles().orEmpty().none { it.name.contains(".stage-") || it.name.contains(".backup-") || it.name.contains(".rollback-") })
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
