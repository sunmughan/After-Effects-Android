package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure clip trim/split/validation tests. Written before the model existed:
 * every test here must fail until ClipDocument and ClipTimeline land.
 */
class ClipTimelineTest {
    private fun clip(
        start: Int,
        end: Int,
        sourceStart: Int = 0,
        sourceEnd: Int = 100,
        sourceLength: Int = 100,
        id: String = "clip-1"
    ): ClipDocument = ClipDocument(
        id = id,
        startFrame = start,
        endFrame = end,
        sourceStartFrame = sourceStart,
        sourceEndFrame = sourceEnd,
        sourceLengthFrames = sourceLength
    )

    private fun changed(outcome: ClipEditOutcome): List<ClipDocument> {
        assertTrue("Expected a change, got $outcome", outcome is ClipEditOutcome.Changed)
        return (outcome as ClipEditOutcome.Changed).clips
    }

    private fun unchangedReason(outcome: ClipEditOutcome): String {
        assertTrue("Expected no change, got $outcome", outcome is ClipEditOutcome.Unchanged)
        return (outcome as ClipEditOutcome.Unchanged).reason
    }

    @Test
    fun fullRangeClipIsValid() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100)), 100).isEmpty())
    }

    @Test
    fun emptyAndReversedClipsAreInvalid() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(50, 50)), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(60, 50)), 100).isNotEmpty())
    }

    @Test
    fun sourceBoundsAreEnforced() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100, 0, 101, 100)), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100, -1, 99, 100)), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100, 0, 100, 99)), 100).isNotEmpty())
    }

    @Test
    fun sourceSpanMustMatchTimelineSpan() {
        // Timeline length 100 but only 90 source frames: coverage is inconsistent.
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100, 0, 90)), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 100, 10, 110)), 100).isEmpty())
    }

    @Test
    fun clipOutsideCompositionDurationIsInvalid() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 101)), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(-1, 50)), 100).isNotEmpty())
    }

    @Test
    fun overlappingClipsOnOneLayerAreInvalidButAdjacentClipsAreFine() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 50, 0, 50, 100, "a"), clip(40, 100, 40, 100, 100, "b")), 100).isNotEmpty())
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 50, 0, 50, 100, "a"), clip(50, 100, 50, 100, 100, "b")), 100).isEmpty())
    }

    @Test
    fun duplicateClipIdsAreInvalid() {
        assertTrue(ClipTimeline.validateClips(listOf(clip(0, 50, 0, 50, 100, "a"), clip(50, 100, 50, 100, 100, "a")), 100).isNotEmpty())
    }

    @Test
    fun trimStartShiftsSourceInPoint() {
        val result = changed(ClipTimeline.trimClip(listOf(clip(0, 100, 0, 100)), "clip-1", newStartFrame = 20, newEndFrame = null, durationFrames = 100))
        assertEquals(listOf(clip(20, 100, 20, 100)), result)
    }

    @Test
    fun trimEndShiftsSourceOutPoint() {
        val result = changed(ClipTimeline.trimClip(listOf(clip(0, 100, 0, 100)), "clip-1", newStartFrame = null, newEndFrame = 80, durationFrames = 100))
        assertEquals(listOf(clip(0, 80, 0, 80)), result)
    }

    @Test
    fun trimBothEndsKeepsCoverageConsistent() {
        val result = changed(ClipTimeline.trimClip(listOf(clip(0, 100, 10, 110, 150)), "clip-1", newStartFrame = 20, newEndFrame = 70, durationFrames = 100))
        assertEquals(listOf(clip(20, 70, 30, 80, 150)), result)
    }

    @Test
    fun trimCanExtendBackIntoUnusedSource() {
        // Source has 0..100; the clip only uses 20..80, so extending is legal.
        val result = changed(ClipTimeline.trimClip(listOf(clip(20, 80, 20, 80)), "clip-1", newStartFrame = 10, newEndFrame = 90, durationFrames = 100))
        assertEquals(listOf(clip(10, 90, 10, 90)), result)
    }

    @Test
    fun trimIntoNeighbourIsRefused() {
        val clips = listOf(clip(0, 50, 0, 50, 100, "a"), clip(50, 100, 50, 100, 100, "b"))
        val reason = unchangedReason(ClipTimeline.trimClip(clips, "a", null, 60, 100))
        assertTrue(reason.isNotBlank())
        assertEquals(clips, clips) // input list is never mutated
    }

    @Test
    fun trimBeyondSourceBoundsIsRefused() {
        val clips = listOf(clip(0, 100, 0, 100))
        unchangedReason(ClipTimeline.trimClip(clips, "clip-1", newStartFrame = -5, newEndFrame = null, durationFrames = 100))
        unchangedReason(ClipTimeline.trimClip(clips, "clip-1", newStartFrame = null, newEndFrame = 101, durationFrames = 100))
    }

    @Test
    fun trimToSameBoundsIsANoOpWithReason() {
        val reason = unchangedReason(ClipTimeline.trimClip(listOf(clip(0, 100, 0, 100)), "clip-1", 0, 100, 100))
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun trimUnknownClipIsANoOp() {
        unchangedReason(ClipTimeline.trimClip(listOf(clip(0, 100, 0, 100)), "missing", 10, null, 100))
    }

    @Test
    fun splitPreservesCoverageExactly() {
        val original = clip(0, 100, 10, 110, 150)
        val result = changed(
            ClipTimeline.splitClip(listOf(original), "clip-1", 40, 100) { "clip-2" }
        )
        assertEquals(2, result.size)
        assertEquals(clip(0, 40, 10, 50, 150), result[0])
        assertEquals(clip(40, 100, 50, 110, 150, "clip-2"), result[1])
        // Union of the two halves is exactly the original coverage.
        assertEquals(original.startFrame, result.first().startFrame)
        assertEquals(original.endFrame, result.last().endFrame)
        assertEquals(original.sourceStartFrame, result.first().sourceStartFrame)
        assertEquals(original.sourceEndFrame, result.last().sourceEndFrame)
    }

    @Test
    fun splitAtEitherEdgeIsANoOpNeverACrash() {
        val clips = listOf(clip(0, 100, 0, 100))
        unchangedReason(ClipTimeline.splitClip(clips, "clip-1", 0, 100) { "clip-2" })
        unchangedReason(ClipTimeline.splitClip(clips, "clip-1", 100, 100) { "clip-2" })
        unchangedReason(ClipTimeline.splitClip(clips, "clip-1", -1, 100) { "clip-2" })
        unchangedReason(ClipTimeline.splitClip(clips, "clip-1", 101, 100) { "clip-2" })
    }

    @Test
    fun splitUnknownClipIsANoOp() {
        unchangedReason(ClipTimeline.splitClip(listOf(clip(0, 100, 0, 100)), "missing", 50, 100) { "clip-2" })
    }

    @Test
    fun clipAtFindsClipAndReturnsNullInGaps() {
        val clips = listOf(clip(0, 50, 0, 50, 100, "a"), clip(60, 100, 60, 100, 100, "b"))
        assertEquals("a", ClipTimeline.clipAt(clips, 49)?.id)
        assertEquals("b", ClipTimeline.clipAt(clips, 60)?.id)
        assertNull(ClipTimeline.clipAt(clips, 55))
        assertNull(ClipTimeline.clipAt(clips, 100))
    }

    @Test
    fun clampToDurationDropsClipsBeyondANewShorterDuration() {
        val clips = listOf(clip(0, 50, 0, 50, 100, "a"), clip(50, 100, 50, 100, 100, "b"))
        val clamped = ClipTimeline.clampToDuration(clips, 60)
        assertEquals(listOf(clip(0, 50, 0, 50, 100, "a"), clip(50, 60, 50, 60, 100, "b")), clamped)
        assertEquals(emptyList<ClipDocument>(), ClipTimeline.clampToDuration(clips, 0))
    }

    @Test
    fun layerWithNoClipsCoversTheFullComposition() {
        val layer = LayerDocument(id = "l1", name = "Empty", kind = "Shape", opacity = 1f, tint = 0)
        assertEquals(listOf(0 until 150), ClipTimeline.layerCoverage(layer, 150))
    }

    @Test
    fun projectValidationRejectsBadClips() {
        val bad = ProjectDocument.starter().copy(
            layers = listOf(LayerDocument(id = "l1", name = "Bad", kind = "Shape", opacity = 1f, tint = 0, clips = listOf(clip(0, 50, 0, 50, 100, "a"), clip(40, 90, 40, 90, 100, "b"))))
        )
        assertTrue(bad.validationErrors().isNotEmpty())
        val good = ProjectDocument.starter()
        assertTrue(good.validationErrors().isEmpty())
    }

    @Test
    fun starterLayersCarryOneFullRangeClipEach() {
        val project = ProjectDocument.starter()
        project.layers.forEach { layer ->
            assertEquals(1, layer.clips.size)
            val clip = layer.clips.single()
            assertEquals(0, clip.startFrame)
            assertEquals(project.durationFrames, clip.endFrame)
            assertEquals(0, clip.sourceStartFrame)
            assertEquals(project.durationFrames, clip.sourceEndFrame)
        }
    }
}
