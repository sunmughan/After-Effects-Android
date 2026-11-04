package com.codeair.effectcraft

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.Mp4SequenceExporter
import com.codeair.effectcraft.data.Mp4OutputValidator
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class Mp4SequenceExporterInstrumentedTest {
    @Test fun flexibleYuv420EncoderProducesReadableH264Mp4() {
        val hasEncoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(Mp4SequenceExporter.MIME, true) } &&
                runCatching {
                    codec.getCapabilitiesForType(Mp4SequenceExporter.MIME).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }.getOrDefault(false)
        }
        assumeTrue("Device has no flexible-YUV H.264 encoder", hasEncoder)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "mp4-export-${System.nanoTime()}.mp4")
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(
                width = 64, height = 64, frameRate = 30, startFrame = 0, endFrame = 1,
                outputFormat = RenderOutputFormat.MP4_H264, videoBitrate = 500_000
            )
        )
        try {
            val result = FileOutputStream(output).use { stream ->
                Mp4SequenceExporter.export(plan, stream.fd, ::redFrame)
            }
            assertEquals(2, result.framesEncoded)
            assertEquals(66_666L, result.durationUs)
            assertTrue(output.length() > 0L)
            val validated = Mp4OutputValidator.validateFile(output, plan)
            assertEquals(64, validated.width)
            assertEquals(64, validated.height)
            assertEquals(2, validated.frameCount)
            assertEquals(result.durationUs, validated.durationUs)
            assertEquals(Mp4SequenceExporter.MIME, validated.mime)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == Mp4SequenceExporter.MIME
                } ?: error("MP4 has no H.264 video track")
                extractor.selectTrack(videoTrack)
                val sample = ByteBuffer.allocate(1024 * 1024)
                var samples = 0
                while (extractor.readSampleData(sample, 0) >= 0) {
                    samples++
                    extractor.advance()
                    sample.clear()
                }
                assertTrue("MP4 should contain encoded samples", samples >= 1)
            } finally {
                extractor.release()
            }
        } finally {
            output.delete()
        }
    }

    private fun redFrame(frame: Int): ByteArray {
        val bytes = ByteArray(8 + 2 * 2 * 4)
        bytes[3] = 2
        bytes[7] = 2
        for (pixel in 0 until 4) {
            val offset = 8 + pixel * 4
            bytes[offset] = 255.toByte()
            bytes[offset + 3] = 255.toByte()
        }
        return bytes
    }
}
