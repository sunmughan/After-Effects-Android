package com.codeair.effectcraft.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the pure plan gate of [Mp4OutputValidator]. The gate is
 * Android-free by design so these cases run without MediaExtractor: an
 * H.264 plan and an HEVC plan both pass (HEVC is an explicit opt-in after
 * the hardware probe, and the production export path validates the final
 * copy with this validator, so an H.264-only gate would fail every HEVC
 * export the app itself offers); any non-MP4 plan fails with a named MP4
 * error. The file-side MIME match in `inspect` needs a real container and
 * is covered by codec instrumentation, not here.
 */
class Mp4OutputValidatorTest {
    private fun plan(format: RenderOutputFormat) =
        RenderQueuePlan.create(
            RenderQueueSettings(640, 360, 30, 0, 29, format, 8_000_000)
        )

    @Test
    fun h264PlanPassesTheGate() {
        Mp4OutputValidator.requireMp4Plan(plan(RenderOutputFormat.MP4_H264))
    }

    @Test
    fun hevcPlanPassesTheGate() {
        Mp4OutputValidator.requireMp4Plan(plan(RenderOutputFormat.MP4_HEVC))
    }

    @Test
    fun pngPlanIsRejectedAsNotMp4() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Mp4OutputValidator.requireMp4Plan(plan(RenderOutputFormat.PNG_SEQUENCE_ZIP))
        }
        assertTrue(failure.message!!.contains("MP4"))
    }

    @Test
    fun pngPlanIsRejectedByValidateFileBeforeAnyExtractorWork() {
        // The format gate runs before MediaExtractor touches the file: a
        // PNG plan fails with the format error even when the file exists
        // and is not a video at all (mimeFor throws for a non-MP4 plan
        // before requireMp4Plan runs).
        val root = Files.createTempDirectory("effectcraft-validator-gate").toFile()
        try {
            val file = File(root, "not-a-video.mp4").apply { writeText("not a video") }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Mp4OutputValidator.validateFile(file, plan(RenderOutputFormat.PNG_SEQUENCE_ZIP))
            }
            assertTrue(
                "A PNG plan must fail the format gate, not the extractor: ${failure.message}",
                failure.message!!.contains("PNG")
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
