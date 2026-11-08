package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectKeyframeStoreTest {
    @Test
    fun tracksPersistPerProjectAndReplaceKeysAtSameFrame() {
        val root = Files.createTempDirectory("effectcraft-keyframes").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project-A", "layer-1", "opacity", 20, 0.4f)
            store.upsert("project-A", "layer-1", "opacity", 0, 1f)
            store.upsert("project-A", "layer-1", "opacity", 20, 0.6f)
            store.upsert("project-A", "layer-2", "position.x", 10, 42f)
            store.upsert("project-B", "layer-1", "opacity", 5, 0.2f)

            val reopened = ProjectKeyframeStore(root)
            val projectA = reopened.load("project-A")
            assertEquals(2, projectA.size)
            val opacity = projectA.single { it.layerId == "layer-1" }.track
            assertEquals(listOf(0, 20), opacity.keyframes.map { it.frame })
            assertEquals(0.6f, opacity.keyframes.last().value, 0f)
            assertEquals(1, reopened.load("project-B").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removingLastKeyDeletesTrackAndClearDeletesProjectDocument() {
        val root = Files.createTempDirectory("effectcraft-keyframes-remove").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "rotation", 3, 90f)
            assertTrue(store.removeKeyframe("project", "layer", "rotation", 3))
            assertTrue(store.load("project").isEmpty())
            store.upsert("project", "layer", "rotation", 3, 90f)
            assertTrue(store.clear("project"))
            assertTrue(store.load("project").isEmpty())
            assertFalse(store.clear("project"))
            assertFalse(store.removeKeyframe("project", "layer", "rotation", 3))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidIdentityFramesAndValues() {
        val root = Files.createTempDirectory("effectcraft-keyframes-invalid").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            assertTrue(runCatching { store.upsert("", "layer", "opacity", 0, 1f) }.isFailure)
            assertTrue(runCatching { store.upsert("p", "", "opacity", 0, 1f) }.isFailure)
            assertTrue(runCatching { store.upsert("p", "l", "not a property", 0, 1f) }.isFailure)
            assertTrue(runCatching { store.upsert("p", "l", "opacity", -1, 1f) }.isFailure)
            assertTrue(runCatching { store.upsert("p", "l", "opacity", 0, Float.NaN) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptOrMismatchedProjectDocuments() {
        val root = Files.createTempDirectory("effectcraft-keyframes-corrupt").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "opacity", 0, 1f)
            val file = root.listFiles()!!.single()
            file.writeText(file.readText().replace("\"projectId\":\"project\"", "\"projectId\":\"other\""))
            assertTrue(runCatching { store.load("project") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeNewerTempIsPromotedOnLoadAfterInterruptedRename() {
        val root = Files.createTempDirectory("effectcraft-keyframes-recovery").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "opacity", 0, 1f)
            val target = root.listFiles()!!.single()
            val committed = target.readBytes()

            store.upsert("project", "layer", "opacity", 30, 0.25f)
            val newer = target.readBytes()
            target.writeBytes(committed)
            val temporary = java.io.File(root, "${target.name}.tmp")
            temporary.writeBytes(newer)

            val recovered = ProjectKeyframeStore(root).load("project")
            assertEquals(listOf(0, 30), recovered.single().track.keyframes.map { it.frame })
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedOrWrongProjectTempIsDiscardedWithoutDamagingCommittedTrack() {
        val root = Files.createTempDirectory("effectcraft-keyframes-recovery-invalid").toFile()
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "opacity", 7, 0.7f)
            val target = root.listFiles()!!.single()
            val temporary = java.io.File(root, "${target.name}.tmp")

            temporary.writeText("{broken")
            assertEquals(listOf(7), ProjectKeyframeStore(root).load("project").single().track.keyframes.map { it.frame })
            assertFalse(temporary.exists())

            temporary.writeText(target.readText().replace("\"projectId\":\"project\"", "\"projectId\":\"other\""))
            assertEquals(listOf(7), ProjectKeyframeStore(root).load("project").single().track.keyframes.map { it.frame })
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun independentStoreInstancesSerializeConcurrentReadModifyWriteUpdates() {
        val root = Files.createTempDirectory("effectcraft-keyframes-concurrent").toFile()
        val workers = 6
        val perWorker = 20
        val ready = java.util.concurrent.CountDownLatch(workers)
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { worker ->
                executor.submit {
                    val store = ProjectKeyframeStore(root)
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    repeat(perWorker) { index ->
                        val frame = worker * perWorker + index
                        store.upsert("shared-project", "layer-$worker-$index", "opacity", frame, frame / 120f)
                    }
                }
            }
            assertTrue("All writers should be ready", ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }

            val reopened = ProjectKeyframeStore(root).load("shared-project")
            assertEquals(workers * perWorker, reopened.size)
            assertEquals(
                (0 until workers * perWorker).toSet(),
                reopened.map { it.track.keyframes.single().frame }.toSet()
            )
            assertEquals(workers * perWorker, reopened.map { it.layerId }.toSet().size)
        } finally {
            start.countDown()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

}
