package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Process-death recovery for keyframe state: a killed mid-edit process must
 * recover to the last atomically saved state, never to a half-written or
 * cleared-then-resurrected one. A fresh store instance is the stand-in for a
 * new process: it shares only the on-disk directory, no in-memory state.
 */
class ProjectKeyframeStoreProcessDeathTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun killedMidEditProcessRecoversLastAtomicallySavedState() {
        val root = tempDir("effectcraft-keyframes-death")
        try {
            val editor = ProjectKeyframeStore(root)
            editor.upsert("project", "layer", "opacity", 0, 1f)
            editor.upsert("project", "layer", "opacity", 30, 0.25f)
            // An edit that was never saved: it exists only in the dead
            // process's memory and must never appear after restart.
            val unsaved = editor.load("project").single().track
            assertEquals(listOf(0, 30), unsaved.keyframes.map { it.frame })

            // Process death: drop every reference; a new process reopens.
            val restarted = ProjectKeyframeStore(root)
            val recovered = restarted.load("project")
            assertEquals(1, recovered.size)
            assertEquals(listOf(0, 30), recovered.single().track.keyframes.map { it.frame })
            assertEquals(0.25f, recovered.single().track.keyframes.last().value, 0f)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedWriteTempDoesNotResurrectClearedTracks() {
        val root = tempDir("effectcraft-keyframes-clear-death")
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "opacity", 7, 0.7f)
            val target = root.listFiles()!!.single()
            // A previous process died mid-write, leaving a fully valid temp.
            val temporary = File(root, "${target.name}.tmp")
            temporary.writeText(target.readText(Charsets.UTF_8))
            assertTrue(temporary.isFile)

            // The user clears the project; the restart must not bring the
            // cleared tracks back through the stale temp.
            assertTrue(store.clear("project"))
            assertTrue("Clear must remove the interrupted temp as well", !temporary.exists())

            val restarted = ProjectKeyframeStore(root)
            assertTrue(restarted.load("project").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearOnProjectWithoutTracksLeavesNoResurrectableTemp() {
        val root = tempDir("effectcraft-keyframes-clear-empty")
        try {
            val store = ProjectKeyframeStore(root)
            store.upsert("project", "layer", "opacity", 3, 0.5f)
            val target = root.listFiles()!!.single()
            File(root, "${target.name}.tmp").writeText("{broken")
            assertTrue(store.clear("project"))
            assertTrue(ProjectKeyframeStore(root).load("project").isEmpty())
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
