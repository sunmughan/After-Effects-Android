package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderQueuePlanTest {
    private fun settings(start: Int = 10, end: Int = 12, width: Int = 320, height: Int = 320, fps: Int = 30) =
        RenderQueueSettings(width, height, fps, start, end)

    @Test
    fun rangeIsInclusiveAndFrameNamesAreStable() {
        val plan = RenderQueuePlan.create(settings())
        assertEquals(3, plan.frameCount)
        assertEquals(listOf(10, 11, 12), plan.frames.toList())
        assertEquals(10, plan.frameAt(0))
        assertEquals(12, plan.frameAt(2))
        assertEquals("frame-000010.png", plan.zipEntryName(10))
        assertEquals("frame-000012.png", plan.zipEntryName(12))
        assertThrows(IllegalArgumentException::class.java) { plan.frameAt(3) }
        assertThrows(IllegalArgumentException::class.java) { plan.zipEntryName(9) }
    }

    @Test
    fun progressIsMonotonicAndReachesOneHundred() {
        val plan = RenderQueuePlan.create(settings(start = 4, end = 7))
        assertEquals(0, plan.progressPercent(0))
        assertEquals(25, plan.progressPercent(1))
        assertEquals(50, plan.progressPercent(2))
        assertEquals(75, plan.progressPercent(3))
        assertEquals(100, plan.progressPercent(4))
        assertThrows(IllegalArgumentException::class.java) { plan.progressPercent(5) }
    }

    @Test
    fun acceptsCommonSmallCompositionDimensions() {
        val smallWide = RenderQueuePlan.create(settings(start = 0, end = 0, width = 320, height = 180))
        assertEquals(320, smallWide.settings.width)
        assertEquals(180, smallWide.settings.height)
        assertEquals(230_400L, smallWide.estimatedUncompressedRgbaBytes)
    }

    @Test
    fun rejectsInvalidDimensionsFrameRateAndRanges() {
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(width = 0)) }
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(height = 4097)) }
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(fps = 0)) }
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(fps = 121)) }
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(start = -1)) }
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(start = 13, end = 12)) }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(start = 0, end = RenderQueuePlan.MAX_FRAME_COUNT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(end = RenderQueuePlan.MAX_FRAME_INDEX + 1))
        }
    }

    @Test
    fun mp4PlanRequiresEvenYuv420DimensionsAndBoundedBitrate() {
        val mp4 = RenderQueueSettings(
            width = 640, height = 360, frameRate = 30, startFrame = 0, endFrame = 29,
            outputFormat = RenderOutputFormat.MP4_H264, videoBitrate = 8_000_000
        )
        assertEquals(30, RenderQueuePlan.create(mp4).frameCount)
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(mp4.copy(width = 641))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(mp4.copy(height = 359))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(mp4.copy(videoBitrate = 249_999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(mp4.copy(videoBitrate = 50_000_001))
        }
    }

    @Test
    fun hevcPlanSharesMp4DimensionAndBitrateRules() {
        val hevc = RenderQueueSettings(
            width = 640, height = 360, frameRate = 30, startFrame = 0, endFrame = 29,
            outputFormat = RenderOutputFormat.MP4_HEVC, videoBitrate = 8_000_000
        )
        assertEquals(30, RenderQueuePlan.create(hevc).frameCount)
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(hevc.copy(width = 641))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(hevc.copy(videoBitrate = 50_000_001))
        }
    }

    @Test
    fun enforcesPerFramePixelsAndTotalRgbaBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(width = 4096, height = 4096))
        }
        // 3840×2160 is a valid UHD frame; the queue budget bounds its length.
        val uhd = RenderQueuePlan.create(settings(start = 0, end = 0, width = 3840, height = 2160))
        assertEquals(33_177_600L, uhd.estimatedUncompressedRgbaBytes)
        assertTrue(uhd.estimatedUncompressedRgbaBytes <= RenderQueuePlan.MAX_UNCOMPRESSED_RGBA_BYTES)
        // 258 UHD frames = 8,559,820,800 bytes, still under the 8 GiB budget;
        // 259 frames = 8,592,998,400 bytes, the first count that exceeds it.
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(start = 0, end = 258, width = 3840, height = 2160))
        }
    }
}
