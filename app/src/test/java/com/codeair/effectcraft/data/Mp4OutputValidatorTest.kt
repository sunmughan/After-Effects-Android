package com.codeair.effectcraft.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure plan gate of [Mp4OutputValidator]. The gate is
 * Android-free by design so these cases run without MediaExtractor: an
 * HEVC plan is rejected with a named HEVC error, any other non-MP4 plan
 * with the generic H.264 error, and an H.264 plan passes. The file-side
 * HEVC track scan in `inspect` needs a real container and is covered by
 * codec instrumentation, not here.
 */
class Mp4OutputValidatorTest {
    private fun plan(format: RenderOutputFormat) =
        RenderQueuePlan.create(
            RenderQueueSettings(640, 360, 30, 0, 29, format, 8_000_000)
        )

    @Test
    fun h264PlanPassesTheGate() {
        Mp4OutputValidator.requireH264Plan(plan(RenderOutputFormat.MP4_H264))
    }

    @Test
    fun hevcPlanIsRejectedWithNamedHevcError() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Mp4OutputValidator.requireH264Plan(plan(RenderOutputFormat.MP4_HEVC))
        }
        assertTrue(failure.message!!.contains("HEVC"))
        assertTrue(failure.message!!.contains("H.264 MP4 only"))
    }

    @Test
    fun pngPlanIsRejectedAsNotH264() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Mp4OutputValidator.requireH264Plan(plan(RenderOutputFormat.PNG_SEQUENCE_ZIP))
        }
        assertTrue(failure.message!!.contains("H.264 MP4"))
    }
}
