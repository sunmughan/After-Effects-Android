package ai.effectcraft.android.data

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
    fun rejectsInvalidDimensionsFrameRateAndRanges() {
        assertThrows(IllegalArgumentException::class.java) { RenderQueuePlan.create(settings(width = 319)) }
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
    fun enforcesPerFramePixelsAndTotalRgbaBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(width = 4096, height = 4096))
        }
        // 3840×2160 is a valid UHD frame; the queue budget bounds its length.
        val uhd = RenderQueuePlan.create(settings(start = 0, end = 0, width = 3840, height = 2160))
        assertEquals(33_177_600L, uhd.estimatedUncompressedRgbaBytes)
        assertTrue(uhd.estimatedUncompressedRgbaBytes <= RenderQueuePlan.MAX_UNCOMPRESSED_RGBA_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            RenderQueuePlan.create(settings(start = 0, end = 257, width = 3840, height = 2160))
        }
    }
}
