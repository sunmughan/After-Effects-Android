package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioTimelineStoreTest {
    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "audio-timeline-test-${System.nanoTime()}")

    @Test
    fun saveAndLoadRoundTripsClips() {
        val root = tempRoot()
        try {
            val store = AudioTimelineStore(root)
            val timeline = AudioTimeline.EMPTY
                .addClip("content://media/audio/1", 10_000_000L, 1_000_000L, durationUs = 3_000_000L, volume = 0.5)
                .addClip("content://media/audio/2", 8_000_000L, 5_000_000L, durationUs = 2_000_000L)
            store.save("project-1", timeline)
            val loaded = store.load("project-1")
            assertEquals(2, loaded.clips.size)
            assertEquals(1_000_000L, loaded.clips[0].timelineStartUs)
            assertEquals(0.5, loaded.clips[0].volume, 1e-9)
            assertEquals("content://media/audio/2", loaded.clips[1].sourceUri)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadOfUnknownProjectIsEmpty() {
        val root = tempRoot()
        try {
            assertTrue(AudioTimelineStore(root).load("no-such-project").isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesTheTimeline() {
        val root = tempRoot()
        try {
            val store = AudioTimelineStore(root)
            store.save("project-1", AudioTimeline.EMPTY.addClip("content://media/audio/1", 5_000_000L, 0L))
            assertTrue(store.clear("project-1"))
            assertTrue(store.load("project-1").isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedWriteTempIsPromotedOnNextLoad() {
        val root = tempRoot()
        try {
            val store = AudioTimelineStore(root)
            store.save("project-1", AudioTimeline.EMPTY.addClip("content://media/audio/1", 5_000_000L, 0L))
            // Simulate a crash after a newer temp was written but before
            // the rename: drop a valid newer document in as the temp file.
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("project-1".toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b) }
            val temp = File(root, "$hash.json.tmp")
            root.mkdirs()
            temp.writeText(
                """{"format":"effectcraft-audio-timeline","version":1,"projectId":"project-1","clips":[{"id":"c9","sourceUri":"content://media/audio/9","sourceDurationUs":6000000,"timelineStartUs":2000000,"sourceStartUs":0,"durationUs":2000000,"volume":1.0,"fadeInUs":0,"fadeOutUs":0}]}""",
                Charsets.UTF_8
            )
            val loaded = store.load("project-1")
            assertEquals(1, loaded.clips.size)
            assertEquals("content://media/audio/9", loaded.clips[0].sourceUri)
            assertEquals(2_000_000L, loaded.clips[0].timelineStartUs)
        } finally {
            root.deleteRecursively()
        }
    }
}
