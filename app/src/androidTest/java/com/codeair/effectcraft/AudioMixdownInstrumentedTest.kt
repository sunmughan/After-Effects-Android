package com.codeair.effectcraft

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.AudioClipDocument
import com.codeair.effectcraft.data.AudioDecodeException
import com.codeair.effectcraft.data.AudioMixdown
import com.codeair.effectcraft.data.AudioStatus
import com.codeair.effectcraft.data.ClipDocument
import com.codeair.effectcraft.data.Mp4ExportWorker
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the audio mixdown: a generated WAV fixture (a
 * 440 Hz sine, written by this test — no binary fixture is shipped)
 * decodes through MediaCodec, mixes to one AAC track honouring the
 * clip's timeline position and mute, and muxes into the staged MP4
 * through the same [Mp4ExportWorker.exportToStaged] the UI calls.
 * Unsupported audio fails closed instead of exporting video-only.
 */
@RunWith(AndroidJUnit4::class)
class AudioMixdownInstrumentedTest {
    private fun hasAacEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals("audio/mp4a-latm", true) }
        }

    private fun hasFlexibleYuvEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", true) } &&
                runCatching {
                    codec.getCapabilitiesForType("video/avc").colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }.getOrDefault(false)
        }

    /** Writes a mono 16-bit PCM WAV of a sine tone; returns the file. */
    private fun writeWavFixture(sampleRateHz: Int = 16000, seconds: Int = 1, frequencyHz: Double = 440.0): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "effectcraft-audio-test-${System.nanoTime()}.wav")
        val frames = sampleRateHz * seconds
        val pcm = ByteArray(frames * 2)
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (frame in 0 until frames) {
            val value = (sin(2.0 * PI * frequencyHz * frame / sampleRateHz) * 16000.0).toInt().toShort()
            shorts.put(value)
        }
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRateHz)
        header.putInt(sampleRateHz * 2)
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun wavClip(
        wav: File,
        startFrame: Int = 0,
        endFrame: Int = 30,
        sourceEndFrame: Int = 30,
        gain: Float = 1f,
        muted: Boolean = false,
        id: String = "audio-clip-1"
    ): AudioClipDocument = AudioClipDocument(
        clip = ClipDocument(
            id = id,
            startFrame = startFrame,
            endFrame = endFrame,
            sourceStartFrame = 0,
            sourceEndFrame = sourceEndFrame,
            sourceLengthFrames = 30
        ),
        sourcePath = wav.absolutePath,
        // What the import probe stores: Android reports WAV as audio/raw.
        sourceMime = "audio/raw",
        sourceDurationUs = 1_000_000L,
        gain = gain,
        muted = muted
    )

    private fun mixedFile(prefix: String): File {
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

    @Test
    fun wavClipMixesToAacWithCompositionTimestamps() {
        assumeTrue("Device has no AAC encoder", hasAacEncoder())
        val wav = writeWavFixture()
        val output = mixedFile("effectcraft-mixed-audio")
        try {
            val result = AudioMixdown.mixToAac(
                clips = listOf(wavClip(wav)),
                frameRateNum = 30,
                frameRateDen = 1,
                rangeStartUs = 0L,
                rangeEndUs = 1_000_000L,
                outputFile = output
            )
            assertTrue("Expected AAC samples, got ${result.aacSamplesWritten}", result.aacSamplesWritten > 0L)
            assertEquals(0L, result.firstPtsUs)
            assertTrue("Last AAC PTS ${result.lastPtsUs} must precede the 1 s range end",
                result.lastPtsUs != null && result.lastPtsUs!! < 1_000_000L)
            assertTrue(output.isFile && output.length() > 0L)
            // The mixed file is itself a valid MP4 with one AAC track.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                assertTrue("Mixed file must hold an AAC track, got $mime", mime.contains("audio/mp4a-latm"))
            } finally {
                extractor.release()
            }
        } finally {
            wav.delete()
            output.delete()
        }
    }

    @Test
    fun mutedClipProducesNoAacFile() {
        assumeTrue("Device has no AAC encoder", hasAacEncoder())
        val wav = writeWavFixture()
        val output = mixedFile("effectcraft-muted-audio")
        try {
            val result = AudioMixdown.mixToAac(
                clips = listOf(wavClip(wav, muted = true)),
                frameRateNum = 30,
                frameRateDen = 1,
                rangeStartUs = 0L,
                rangeEndUs = 1_000_000L,
                outputFile = output
            )
            assertEquals(0L, result.aacSamplesWritten)
            assertFalse("A silent mix must not produce an AAC file", output.exists())
        } finally {
            wav.delete()
            output.delete()
        }
    }

    @Test
    fun undecodableSourceFailsClosedWithTheClipId() {
        assumeTrue("Device has no AAC encoder", hasAacEncoder())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val junk = File(context.cacheDir, "effectcraft-audio-junk-${System.nanoTime()}.txt")
        junk.writeText("this is not audio", Charsets.UTF_8)
        val output = mixedFile("effectcraft-junk-audio")
        try {
            try {
                AudioMixdown.mixToAac(
                    clips = listOf(wavClip(junk)),
                    frameRateNum = 30,
                    frameRateDen = 1,
                    rangeStartUs = 0L,
                    rangeEndUs = 1_000_000L,
                    outputFile = output
                )
                fail("Undecodable audio must fail the mixdown, not export silence")
            } catch (failure: AudioDecodeException) {
                assertTrue("Failure must name the clip, got: ${failure.message}",
                    failure.message?.contains("audio-clip-1") == true)
            }
            assertFalse("A failed mixdown must not leave an AAC file", output.exists())
        } finally {
            junk.delete()
            output.delete()
        }
    }

    @Test
    fun wavClipExportsThroughStagedWorkerWithAudioIncluded() {
        assumeTrue("Device has no flexible-YUV H.264 encoder", hasFlexibleYuvEncoder())
        assumeTrue("Device has no AAC encoder", hasAacEncoder())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = writeWavFixture()
        val staged = mixedFile("effectcraft-video-audio")
        try {
            val plan = RenderQueuePlan.create(
                RenderQueueSettings(
                    width = 64, height = 64, frameRate = 30, startFrame = 0, endFrame = 1,
                    outputFormat = RenderOutputFormat.MP4_H264, videoBitrate = 500_000
                )
            )
            // Two frames at 30 fps: the clip covers frames 0..2 (66 ms).
            val result = Mp4ExportWorker.exportToStaged(
                plan = plan,
                stagedFile = staged,
                renderRgba = ::redFrame,
                audioFile = null,
                hasAudioItems = false,
                compositionFrameRateNum = 30,
                compositionFrameRateDen = 1,
                audioMix = Mp4ExportWorker.AudioMixRequest(
                    clips = listOf(wavClip(wav, endFrame = 2, sourceEndFrame = 2)),
                    frameRateNum = 30,
                    frameRateDen = 1
                )
            )
            assertEquals(AudioStatus.INCLUDED, result.audioStatus)
            assertTrue("Expected AAC samples in the staged export", result.audioSamplesWritten > 0L)
            assertEquals(0L, result.audioFirstPtsUs)
            assertTrue("Last AAC PTS ${result.audioLastPtsUs} must precede the video end ${result.durationUs}",
                result.audioLastPtsUs != null && result.audioLastPtsUs!! < result.durationUs)
        } finally {
            wav.delete()
            staged.delete()
        }
    }
}
