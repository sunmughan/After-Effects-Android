package ai.effectcraft.android.data

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
}
