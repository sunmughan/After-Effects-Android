package com.codeair.effectcraft

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.AudioStatus
import com.codeair.effectcraft.data.Mp4BoxInspector
import com.codeair.effectcraft.data.Mp4ExportValidator
import com.codeair.effectcraft.data.Mp4ExportWorker
import com.codeair.effectcraft.data.Mp4SequenceExporter
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.data.SafDocumentWriter
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the wired M5 path: the UI's exact worker function
 * ([Mp4ExportWorker.exportToStaged]) encodes a small frame range through
 * the sibling [Mp4SequenceExporter], validates the staged file before any
 * SAF copy, and (when an AAC fixture is present) muxes audio through. The
 * frame renderer is a synthetic solid-color RGBA envelope (not the native
 * session), so this test proves encoder/muxer/validator/remux behavior;
 * the native-session render path is the UI's render lambda over the same
 * worker, covered by the existing native session tests.
 */
@RunWith(AndroidJUnit4::class)
class Mp4ExportWorkerInstrumentedTest {
    private fun plan() = RenderQueuePlan.create(
        RenderQueueSettings(
            width = 64, height = 64, frameRate = 30, startFrame = 0, endFrame = 1,
            outputFormat = RenderOutputFormat.MP4_H264, videoBitrate = 500_000
        )
    )

    private fun stagedFile(prefix: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "$prefix-${System.nanoTime()}.mp4")
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

    private fun hasFlexibleYuvEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(Mp4SequenceExporter.MIME, true) } &&
                runCatching {
                    codec.getCapabilitiesForType(Mp4SequenceExporter.MIME).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }.getOrDefault(false)
        }

    @Test
    fun stagedVideoOnlyExportValidatesAndCopiesThroughSafWriter() {
        assumeTrue("Device has no flexible-YUV H.264 encoder", hasFlexibleYuvEncoder())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = stagedFile("effectcraft-video-test")
        try {
            val result = Mp4ExportWorker.exportToStaged(
                plan = plan(), stagedFile = staged, renderRgba = ::redFrame,
                audioFile = null, hasAudioItems = false,
                compositionFrameRateNum = 30, compositionFrameRateDen = 1
            )
            assertEquals(2, result.framesWritten)
            assertEquals(AudioStatus.NO_AUDIO_IN_COMPOSITION, result.audioStatus)
            assertEquals(2L, result.videoSamples)
            assertTrue("duration ${result.durationUs}us should be within one frame of 66,666us",
                result.durationUs in 33_333L..100_000L)
            assertEquals(0L, result.audioSamplesWritten)
            assertTrue(staged.isFile && staged.length() > 0L)

            // Independent container proof: the ported box inspector reads the
            // staged file's own headers (not MediaExtractor's view of it).
            val inspected = Mp4BoxInspector.inspect(staged.readBytes())
            assertEquals("avc1", inspected.videoTrack.codec)
            assertEquals(64, inspected.videoTrack.width)
            assertEquals(64, inspected.videoTrack.height)
            assertEquals(2L, inspected.videoTrack.sampleCount)

            // SAF-copy path: the production copy adapter writes the validated
            // staged file into a document Uri (here a MediaStore video entry,
            // the same openOutputStream("wt") route the SAF picker returns);
            // the copy re-validates as a complete MP4.
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "effectcraft-video-test-${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EffectCraftTest")
                }
            }
            val destination = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("MediaStore refused the test video entry")
            try {
                val copied = SafDocumentWriter.copyFileToDocument(context, staged, destination)
                assertTrue("SAF copy should move the whole staged file", copied == staged.length())
                val copyBack = File(context.cacheDir, "effectcraft-video-copy-${System.nanoTime()}.mp4")
                try {
                    context.contentResolver.openInputStream(destination)?.use { input ->
                        copyBack.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot read back the copied document")
                    val revalidated = Mp4ExportValidator.validate(copyBack, plan(), expectAudio = false)
                    assertEquals(2L, revalidated.videoSamples)
                } finally {
                    copyBack.delete()
                }
            } finally {
                runCatching { context.contentResolver.delete(destination, null, null) }
            }
        } finally {
            staged.delete()
        }
    }

    @Test
    fun junkAudioFileExportsVideoOnlyWithUnsupportedReason() {
        assumeTrue("Device has no flexible-YUV H.264 encoder", hasFlexibleYuvEncoder())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val junk = File(context.cacheDir, "effectcraft-video-junk-${System.nanoTime()}.bin")
        val staged = stagedFile("effectcraft-video-test")
        try {
            junk.writeText("this is not media")
            val result = Mp4ExportWorker.exportToStaged(
                plan = plan(), stagedFile = staged, renderRgba = ::redFrame,
                audioFile = junk, hasAudioItems = true,
                compositionFrameRateNum = 30, compositionFrameRateDen = 1
            )
            assertEquals(AudioStatus.AUDIO_TRACK_UNSUPPORTED, result.audioStatus)
            assertEquals(2L, result.videoSamples)
            Mp4ExportValidator.validate(staged, plan(), expectAudio = false)
        } finally {
            junk.delete()
            staged.delete()
        }
    }

    @Test
    fun aacFixtureIsPassedThroughWithSamplesInRange() {
        assumeTrue("Device has no flexible-YUV H.264 encoder", hasFlexibleYuvEncoder())
        assumeTrue("Device has no AAC encoder", hasAacEncoder())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "effectcraft-audio-fixture-${System.nanoTime()}.m4a")
        val staged = stagedFile("effectcraft-video-test")
        try {
            encodeSilentAac(fixture, frames = 20)
            val result = Mp4ExportWorker.exportToStaged(
                plan = plan(), stagedFile = staged, renderRgba = ::redFrame,
                audioFile = fixture, hasAudioItems = true,
                compositionFrameRateNum = 30, compositionFrameRateDen = 1
            )
            assertEquals(AudioStatus.INCLUDED, result.audioStatus)
            assertTrue("AAC samples should be muxed, got ${result.audioSamplesWritten}",
                result.audioSamplesWritten > 0L)
            assertEquals(2L, result.videoSamples)
            val validated = Mp4ExportValidator.validate(staged, plan(), expectAudio = true)
            assertTrue(validated.audioSamples > 0L)
        } finally {
            fixture.delete()
            staged.delete()
        }
    }

    private fun hasAacEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals("audio/mp4a-latm", true) }
        }

    /** Encodes [frames] AAC frames of digital silence into [output] via MediaMuxer. */
    private fun encodeSilentAac(output: File, frames: Int) {
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        val silence = ByteArray(1024 * 2 * 2)
        var queued = 0
        var eosSent = false
        var eosSeen = false
        try {
            while (!eosSeen) {
                if (!eosSent) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = encoder.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        if (queued < frames) {
                            buffer.put(silence)
                            encoder.queueInputBuffer(
                                inputIndex, 0, silence.size,
                                queued.toLong() * 1_000_000L * 1024L / 44100L, 0
                            )
                            queued++
                        } else {
                            encoder.queueInputBuffer(
                                inputIndex, 0, 0,
                                queued.toLong() * 1_000_000L * 1024L / 44100L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosSent = true
                        }
                    }
                }
                when (val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                    }
                    else -> if (outputIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outputIndex)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started) { "AAC encoder emitted samples before its output format" }
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        check(started && output.length() > 0L) { "AAC fixture was not produced" }
    }
}
