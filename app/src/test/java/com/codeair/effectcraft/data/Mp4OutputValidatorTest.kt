package com.codeair.effectcraft.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the format gate in [Mp4OutputValidator]: a PNG plan must be
 * rejected before any MediaExtractor work, and both MP4 formats must pass
 * the gate and reach the file check. Full validation against a real file
 * lives in the instrumented exporter tests, where MediaExtractor exists.
 */
class Mp4OutputValidatorTest {
    private fun plan(format: RenderOutputFormat) =
        RenderQueuePlan.create(RenderQueueSettings(64, 64, 30, 0, 1, format))

    @Test
    fun pngPlanIsRejectedBeforeAnyExtractorWork() {
        val root = Files.createTempDirectory("effectcraft-validator-gate").toFile()
        try {
            val file = File(root, "not-a-video.mp4").apply { writeText("not a video") }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Mp4OutputValidator.validateFile(file, plan(RenderOutputFormat.PNG_SEQUENCE_ZIP))
            }
            assertTrue("A PNG plan must fail the format gate, not the extractor: ${failure.message}",
                failure.message!!.contains("PNG"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun h264AndHevcPlansPassTheFormatGateToTheFileCheck() {
        val root = Files.createTempDirectory("effectcraft-validator-gate-mp4").toFile()
        try {
            val missing = File(root, "no-such-file.mp4")
            for (format in listOf(RenderOutputFormat.MP4_H264, RenderOutputFormat.MP4_HEVC)) {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    Mp4OutputValidator.validateFile(missing, plan(format))
                }
                assertTrue("An MP4 plan must reach the file check: ${failure.message}",
                    failure.message!!.contains("missing or empty"))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
