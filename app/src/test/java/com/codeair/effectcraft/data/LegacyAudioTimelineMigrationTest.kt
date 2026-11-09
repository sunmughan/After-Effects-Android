package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Batch 2 (P15): the one-shot legacy audio migration reads the retired
 * store's file format and deletes it afterwards.
 */
class LegacyAudioTimelineMigrationTest {

    private fun legacyFile(root: File, projectId: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(projectId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(root, "$hash.json")
    }

    private fun writeLegacy(root: File, projectId: String) {
        root.mkdirs()
        val document = JSONObject().apply {
            put("format", "effectcraft-audio-timeline")
            put("version", 1)
            put("projectId", projectId)
            put("clips", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "clip-1")
                    put("sourceUri", "content://media/audio/7")
                    put("sourceDurationUs", 2_000_000L)
                    put("timelineStartUs", 1_000_000L)
                    put("sourceStartUs", 0L)
                    put("durationUs", 2_000_000L)
                    put("volume", 0.5)
                    put("fadeInUs", 0L)
                    put("fadeOutUs", 0L)
                })
            })
        }
        legacyFile(root, projectId).writeText(document.toString(), Charsets.UTF_8)
    }

    @Test
    fun loadReadsLegacyFile() {
        val root = createTempDir("legacy-audio")
        writeLegacy(root, "project-1")
        val timeline = LegacyAudioTimelineMigration.load(root, "project-1")
        assertEquals(1, timeline.clips.size)
        assertEquals("clip-1", timeline.clips.single().id)
        assertEquals(1_000_000L, timeline.clips.single().timelineStartUs)
        assertEquals(0.5, timeline.clips.single().volume, 0.0)
    }

    @Test
    fun loadReturnsEmptyWhenNoFileExists() {
        val root = createTempDir("legacy-audio-empty")
        assertTrue(LegacyAudioTimelineMigration.load(root, "project-1").isEmpty)
    }

    @Test
    fun clearDeletesTheLegacyFile() {
        val root = createTempDir("legacy-audio-clear")
        writeLegacy(root, "project-1")
        assertTrue(LegacyAudioTimelineMigration.clear(root, "project-1"))
        assertFalse(legacyFile(root, "project-1").exists())
        assertFalse(LegacyAudioTimelineMigration.clear(root, "project-1"))
    }

    private fun createTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
}
