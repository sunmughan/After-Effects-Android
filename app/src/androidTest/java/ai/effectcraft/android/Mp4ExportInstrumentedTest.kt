package ai.effectcraft.android

import ai.effectcraft.android.data.Mp4ExportRequirements
import ai.effectcraft.android.data.Mp4ExportValidator
import ai.effectcraft.android.data.Mp4VideoExporter
import ai.effectcraft.android.data.RenderOutputFormat
import ai.effectcraft.android.data.RenderQueuePlan
import ai.effectcraft.android.data.RenderQueueSettings
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator proof for the M5 MP4 path: a real MediaCodec H.264 encoder and
 * MediaMuxer produce a container that MediaExtractor validates. The frame
 * renderer is a synthetic solid-color RGBA envelope (not the native
 * session), so this test proves encoder/muxer/validator behavior; the
 * native-session render path is covered by the UI export flow and the
 * existing native session tests.
 */
class Mp4ExportInstrumentedTest {
    private fun envelope(width: Int, height: Int, red: Int, green: Int, blue: Int): ByteArray {
        val rgba = ByteArray(8 + width * height * 4)
        ByteBuffer.wrap(rgba, 0, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        for (index in 0 until width * height) {
            rgba[8 + index * 4] = red.toByte()
            rgba[8 + index * 4 + 1] = green.toByte()
            rgba[8 + index * 4 + 2] = blue.toByte()
            rgba[8 + index * 4 + 3] = 255.toByte()
        }
        return rgba
    }

    private fun plan(width: Int = 64, height: Int = 64, fps: Int = 10, start: Int = 0, end: Int = 5) =
        RenderQueuePlan.create(RenderQueueSettings(width, height, fps, start, end, RenderOutputFormat.MP4_H264))

    @Test
    fun encodesShortMp4AndValidatesContainerWithMediaExtractor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(context.cacheDir, "effectcraft-video-test-${System.nanoTime()}.mp4")
        try {
            val frames = (0..5).map { frame ->
                envelope(64, 64, (frame * 40) % 256, 120, 200)
            }
            val result = Mp4VideoExporter.export(
                plan(), staged,
                renderEnvelope = { frame -> frames[frame] },
                audioFile = null,
                audioRangeStartUs = 0L,
                audioRangeEndUs = 600_000L
            )
            assertEquals(6, result.framesWritten)
            assertFalse(result.audioTrackIncluded)
            assertTrue(staged.isFile && staged.length() > 0L)
            val validated = Mp4ExportValidator.validate(staged, plan(), expectAudio = false)
            assertEquals(6L, validated.videoSamples)
            assertEquals(0L, validated.audioSamples)
            // 6 frames at 10 FPS = 600,000us, within one frame of tolerance.
            assertTrue(validated.durationUs in 500_000L..700_000L)
        } finally {
            staged.delete()
        }
    }

    @Test
    fun nonMediaAudioFileExportsVideoOnlyInsteadOfFailing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val junk = File(context.cacheDir, "effectcraft-video-junk-${System.nanoTime()}.bin")
        val staged = File(context.cacheDir, "effectcraft-video-test-${System.nanoTime()}.mp4")
        try {
            junk.writeText("this is not media")
            val result = Mp4VideoExporter.export(
                plan(), staged,
                renderEnvelope = { envelope(64, 64, 10, 20, 30) },
                audioFile = junk,
                audioRangeStartUs = 0L,
                audioRangeEndUs = 600_000L
            )
            assertFalse(result.audioTrackIncluded)
            Mp4ExportValidator.validate(staged, plan(), expectAudio = false)
        } finally {
            junk.delete()
            staged.delete()
        }
    }

    @Test
    fun rejectsOddDimensionsBeforeTouchingTheEncoder() {
        val odd = RenderQueuePlan.create(RenderQueueSettings(63, 64, 10, 0, 5, RenderOutputFormat.MP4_H264))
        assertThrows(IllegalArgumentException::class.java) { Mp4ExportRequirements.checkPlan(odd) }
    }
}
