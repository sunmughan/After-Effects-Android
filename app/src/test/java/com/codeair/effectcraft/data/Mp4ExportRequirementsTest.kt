package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4ExportRequirementsTest {
    private fun mp4Plan(width: Int = 1280, height: Int = 720, fps: Int = 30, start: Int = 0, end: Int = 29) =
        RenderQueuePlan.create(RenderQueueSettings(width, height, fps, start, end, RenderOutputFormat.MP4_H264))

    @Test
    fun acceptsEvenDimensionsWithMp4Format() {
        val plan = mp4Plan()
        Mp4ExportRequirements.checkPlan(plan)
        assertEquals(30, plan.frameCount)
        assertEquals(33_333L, Mp4ExportRequirements.frameDurationUs(30))
        assertEquals(0L, Mp4ExportRequirements.presentationTimeUs(0, 30))
        assertEquals(33_333L, Mp4ExportRequirements.presentationTimeUs(1, 30))
        // 30 frames at integer-truncated 33,333us per frame = 999,990us, not 1,000,000.
        assertEquals(999_990L, Mp4ExportRequirements.expectedDurationUs(30, 30))
    }

    @Test
    fun rejectsOddDimensionsAndNonMp4Plans() {
        // On this codebase RenderQueuePlan itself rejects odd MP4 dimensions
        // before checkPlan ever sees them, so the odd-dimension rejection is
        // asserted at plan creation; checkPlan independently rejects a
        // non-MP4 plan that plan creation accepts.
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(RenderQueueSettings(1279, 720, 30, 0, 29, RenderOutputFormat.MP4_H264))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(RenderQueueSettings(1280, 721, 30, 0, 29, RenderOutputFormat.MP4_H264))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mp4ExportRequirements.checkPlan(
                RenderQueuePlan.create(RenderQueueSettings(1280, 720, 30, 0, 29, RenderOutputFormat.PNG_SEQUENCE_ZIP))
            )
        }
    }

    @Test
    fun bitRateIsBoundedAndScalesWithPixelsAndFrameRate() {
        val sd = Mp4ExportRequirements.bitRate(640, 360, 30)
        val hd = Mp4ExportRequirements.bitRate(1920, 1080, 30)
        assertEquals(691_200.coerceAtLeast(1_000_000), sd)
        assertEquals(6_220_800, hd)
        assertEquals(20_000_000, Mp4ExportRequirements.bitRate(4096, 2048, 120))
    }
}
