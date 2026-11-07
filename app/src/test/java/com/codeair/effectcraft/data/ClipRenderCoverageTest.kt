package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Clip-aware render planning tests. Written before ClipRenderCoverage
 * existed: every test here must fail until it lands.
 *
 * Gap behavior under test (the documented contract): a frame no visible
 * layer covers is a GAP. Gaps render as the background/empty frame —
 * transparent-black RGBA — never a stale frame, never a crash, and the
 * underlying renderer is never invoked for a gap frame.
 */
class ClipRenderCoverageTest {
    private fun clip(start: Int, end: Int, id: String = "c1"): ClipDocument =
        ClipDocument(id, start, end, start, end, 150)

    private fun layer(id: String, clips: List<ClipDocument>, visible: Boolean = true): LayerDocument =
        LayerDocument(id = id, name = id, kind = "Shape", opacity = 1f, tint = 0, visible = visible, clips = clips)

    private fun project(vararg layers: LayerDocument): ProjectDocument =
        ProjectDocument.starter().copy(layers = layers.toList())

    @Test
    fun frameCoveredByAnyVisibleLayerCountsAsCovered() {
        val doc = project(layer("a", listOf(clip(0, 50))), layer("b", listOf(clip(50, 100))))
        assertTrue(ClipRenderCoverage.isFrameCovered(doc, 0))
        assertTrue(ClipRenderCoverage.isFrameCovered(doc, 99))
        assertFalse(ClipRenderCoverage.isFrameCovered(doc, 100))
    }

    @Test
    fun invisibleLayerNeverCoversAFrame() {
        val doc = project(layer("a", listOf(clip(0, 150)), visible = false))
        assertFalse(ClipRenderCoverage.isFrameCovered(doc, 10))
    }

    @Test
    fun layerWithNoClipsKeepsLegacyFullCoverage() {
        val doc = project(layer("a", emptyList()))
        assertTrue(ClipRenderCoverage.isFrameCovered(doc, 0))
        assertTrue(ClipRenderCoverage.isFrameCovered(doc, 149))
        assertEquals((0 until 150).toList(), ClipRenderCoverage.coveredFrames(doc, 0, 149))
        assertEquals(emptyList<Int>(), ClipRenderCoverage.gapFrames(doc, 0, 149))
    }

    @Test
    fun coveredAndGapFramesPartitionAnExportRange() {
        val doc = project(layer("a", listOf(clip(10, 20), clip(30, 40))))
        assertEquals((10 until 20).toList() + (30 until 40).toList(), ClipRenderCoverage.coveredFrames(doc, 0, 49))
        val gaps = ClipRenderCoverage.gapFrames(doc, 0, 49)
        assertEquals((0 until 10).toList() + (20 until 30).toList() + (40 until 50).toList(), gaps)
        assertEquals(50, ClipRenderCoverage.coveredFrames(doc, 0, 49).size + gaps.size)
    }

    @Test
    fun exportRangeIntersectsClipCoverage() {
        val doc = project(layer("a", listOf(clip(10, 20))))
        val settings = RenderQueueSettings(width = 1920, height = 1080, frameRate = 30, startFrame = 0, endFrame = 49)
        val plan = RenderQueuePlan.createForClips(settings, doc)
        assertEquals(listOf(10 until 20), plan.coveredRanges(doc))
        assertEquals((10 until 20).toList(), plan.coveredFrames(doc))
        assertEquals((0 until 10).toList() + (20 until 50).toList(), plan.gapFrames(doc))
    }

    @Test
    fun planRejectsInvalidClipsInsteadOfRenderingThem() {
        val doc = project(layer("a", listOf(clip(10, 20), ClipDocument("c2", 15, 25, 15, 25, 150))))
        val settings = RenderQueueSettings(width = 1920, height = 1080, frameRate = 30, startFrame = 0, endFrame = 49)
        try {
            RenderQueuePlan.createForClips(settings, doc)
            assertTrue("Overlapping clips must be rejected at plan time", false)
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun backgroundFrameIsAWellFormedTransparentBlackEnvelope() {
        val frame = ClipRenderCoverage.backgroundFrame(1920, 1080)
        assertEquals(8 + 1920 * 1080 * 4, frame.size)
        assertEquals(1920, ByteBuffer.wrap(frame, 0, 4).int)
        assertEquals(1080, ByteBuffer.wrap(frame, 4, 4).int)
        assertTrue(frame.drop(8).all { it == 0.toByte() })
    }

    @Test
    fun backgroundFrameStaysInsideTheEnvelopeSizeLimitForLargePlans() {
        val frame = ClipRenderCoverage.backgroundFrame(3840, 2160)
        val width = ByteBuffer.wrap(frame, 0, 4).int
        val height = ByteBuffer.wrap(frame, 4, 4).int
        assertTrue(width <= 2048 && height <= 2048)
        assertEquals(8 + width * height * 4, frame.size)
    }

    @Test
    fun gapFramesNeverInvokeTheRendererAndCoveredFramesPassThrough() {
        val doc = project(layer("a", listOf(clip(10, 12))))
        var rendered = 0
        val calls = mutableListOf<Int>()
        val wrapped = ClipRenderCoverage.clipAwareRenderRgba(doc, 64, 64) { frame ->
            rendered++
            calls += frame
            ByteArray(8 + 64 * 64 * 4) { if (it < 8) 0 else 7 }
        }
        val gap = wrapped(5)
        assertEquals(0, rendered)
        assertTrue(gap.drop(8).all { it == 0.toByte() })
        val covered = wrapped(10)
        assertEquals(1, rendered)
        assertEquals(listOf(10), calls)
        assertEquals(7.toByte(), covered[8])
        // Idempotent: rendering the same frames again gives the same bytes.
        assertTrue(wrapped(5).contentEquals(gap))
        assertTrue(wrapped(10).contentEquals(covered))
    }
}
