package com.codeair.effectcraft.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ExportWorkerTest {
    private fun item(
        hasAudio: Boolean,
        missing: Boolean = false,
        path: String? = null
    ) = CanonicalProjectItemInfo(
        id = 1L, name = "clip", type = "footage", parentId = null, path = path,
        width = 0, height = 0, hasVideo = false, hasAudio = hasAudio,
        missing = missing, sequence = emptyList()
    )

    private val sha = "a".repeat(64)

    @Test
    fun resolveAudioReturnsFirstResolvableOfflinePayload() {
        val found = File("/tmp/fake-audio.m4a")
        val resolution = Mp4ExportWorker.resolveAudio(
            listOf(
                item(hasAudio = true, path = "https://example.invalid/not-offline.mp4"),
                item(hasAudio = true, path = "offline/$sha.m4a"),
                item(hasAudio = true, path = "offline/${"b".repeat(64)}.m4a")
            )
        ) { hash -> if (hash == sha) found else null }
        assertTrue(resolution.hasAudioItems)
        assertEquals(found, resolution.file)
    }

    @Test
    fun resolveAudioReportsAudioItemsWhenNothingResolves() {
        val resolution = Mp4ExportWorker.resolveAudio(
            listOf(
                item(hasAudio = true, missing = true, path = "offline/$sha.m4a"),
                item(hasAudio = true, path = "offline/not-a-hash.m4a")
            )
        ) { null }
        assertTrue(resolution.hasAudioItems)
        assertNull(resolution.file)
    }

    @Test
    fun resolveAudioIgnoresNonAudioItems() {
        val resolution = Mp4ExportWorker.resolveAudio(
            listOf(item(hasAudio = false, path = "offline/$sha.m4a"))
        ) { File("/tmp/never.m4a") }
        assertFalse(resolution.hasAudioItems)
        assertNull(resolution.file)
    }

    @Test
    fun audioRangeUsesCompositionFrameRateNotExportFrameRate() {
        // Export frames 30..59 at 10 FPS from a 30 FPS composition:
        // composition time runs 1,000,000..2,000,000us regardless of export FPS.
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(64, 64, 10, 30, 59, RenderOutputFormat.MP4_H264)
        )
        val (startUs, endUs) = Mp4ExportWorker.audioRangeUs(plan, 30, 1)
        assertEquals(1_000_000L, startUs)
        assertEquals(2_000_000L, endUs)
    }

    @Test
    fun audioRangeUsesFractionalCompositionFrameRate() {
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(64, 64, 30, 0, 29, RenderOutputFormat.MP4_H264)
        )
        // 30000/1001 FPS: 30 frames = 30 * 1,000,000 * 1001 / 30000 = 1,001,000us.
        val (startUs, endUs) = Mp4ExportWorker.audioRangeUs(plan, 30000, 1001)
        assertEquals(0L, startUs)
        assertEquals(1_001_000L, endUs)
    }
}
