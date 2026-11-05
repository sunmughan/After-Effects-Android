package com.codeair.effectcraft.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Validates a completed H.264 MP4 against the exact render queue that produced it.
 *
 * Validation is performed after the muxer and output descriptor are closed:
 * MediaExtractor must see a finalized container, not an in-flight file.
 * A failure means the caller must not report success and should remove the
 * partial/invalid SAF document.
 */
object Mp4OutputValidator {
    data class Report(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val durationUs: Long,
        val mime: String,
        val audioSamples: Long = 0L,
        val audioFirstPtsUs: Long? = null,
        val audioLastPtsUs: Long? = null,
        val audioTrackDurationUs: Long? = null
    )

    fun validate(context: Context, uri: Uri, plan: RenderQueuePlan, expectAudio: Boolean = false): Report {
        require(plan.settings.outputFormat == RenderOutputFormat.MP4_H264) {
            "Render queue is not configured for H.264 MP4"
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return inspect(extractor, plan, expectAudio)
        } finally {
            extractor.release()
        }
    }

    /** File-backed variant used by codec instrumentation; production uses the SAF Uri overload. */
    fun validateFile(file: File, plan: RenderQueuePlan, expectAudio: Boolean = false): Report {
        require(file.isFile && file.length() > 0L) { "MP4 output is missing or empty" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return inspect(extractor, plan, expectAudio)
        } finally {
            extractor.release()
        }
    }

    private fun inspect(extractor: MediaExtractor, plan: RenderQueuePlan, expectAudio: Boolean): Report {
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                ?.equals(Mp4SequenceExporter.MIME, ignoreCase = true) == true
        } ?: error("MP4 has no H.264 video track")
        val format = extractor.getTrackFormat(track)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        require(width == plan.settings.width && height == plan.settings.height) {
            "MP4 dimensions are ${width}×${height}; expected ${plan.settings.width}×${plan.settings.height}"
        }

        extractor.selectTrack(track)
        // Compressed samples are normally much smaller than a raw frame. Keep
        // this allocation bounded and independent of render queue length.
        val pixels = width.toLong() * height.toLong()
        val sampleBuffer = ByteBuffer.allocateDirect(
            maxOf(1024L * 1024L, minOf(32L * 1024L * 1024L, pixels)).toInt()
        )
        var count = 0
        var firstPts = -1L
        var lastPts = -1L
        while (true) {
            sampleBuffer.clear()
            val bytes = extractor.readSampleData(sampleBuffer, 0)
            if (bytes < 0) break
            val pts = extractor.sampleTime
            require(pts >= 0L) { "MP4 contains a sample without a presentation timestamp" }
            if (count == 0) {
                firstPts = pts
                require(firstPts == 0L) { "MP4 first presentation timestamp must be zero" }
            } else {
                require(pts > lastPts) { "MP4 presentation timestamps are not strictly increasing" }
            }
            lastPts = pts
            count++
            require(count <= plan.frameCount) { "MP4 contains more frames than the render plan" }
            extractor.advance()
        }
        require(count == plan.frameCount) {
            "MP4 contains $count encoded frames; expected ${plan.frameCount}"
        }

        val frameDurationUs = 1_000_000L / plan.settings.frameRate
        val inferredDurationUs = lastPts + frameDurationUs
        val trackDurationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }
            .getOrNull() ?: inferredDurationUs
        val expectedDurationUs = plan.frameCount.toLong() * 1_000_000L / plan.settings.frameRate
        require(trackDurationUs > 0L) { "MP4 video track has no positive duration" }
        require(kotlin.math.abs(trackDurationUs - expectedDurationUs) <= frameDurationUs) {
            "MP4 duration ${trackDurationUs}us differs from expected ${expectedDurationUs}us"
        }
        val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                ?.equals(AudioTrackSelection.PASSTHROUGH_MIME, ignoreCase = true) == true
        }
        require(!expectAudio || audioTrack != null) {
            "MP4 is missing the expected AAC audio track"
        }

        var audioSamples = 0L
        var audioFirstPtsUs: Long? = null
        var audioLastPtsUs: Long? = null
        var audioTrackDurationUs: Long? = null
        if (audioTrack != null) {
            val audioFormat = extractor.getTrackFormat(audioTrack)
            audioTrackDurationUs = runCatching { audioFormat.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
            extractor.unselectTrack(track)
            extractor.selectTrack(audioTrack)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // AAC access units are small; keep validation memory fixed
            // regardless of export duration or sample count.
            val audioBuffer = ByteBuffer.allocateDirect(1024 * 1024)
            while (true) {
                audioBuffer.clear()
                val bytes = extractor.readSampleData(audioBuffer, 0)
                if (bytes < 0) break
                val pts = extractor.sampleTime
                audioLastPtsUs = AudioTrackSelection.validateSamplePts(audioLastPtsUs, pts)
                if (audioFirstPtsUs == null) audioFirstPtsUs = pts
                audioSamples++
                extractor.advance()
            }
            require(!expectAudio || audioSamples > 0L) {
                "MP4 AAC track contains no samples"
            }
            require(audioTrackDurationUs == null || audioTrackDurationUs > 0L) {
                "MP4 AAC track has no positive duration"
            }
            require(!expectAudio || (audioTrackDurationUs != null && audioTrackDurationUs > 0L)) {
                "MP4 AAC track duration is unavailable"
            }
            if (audioSamples > 0L) {
                require(audioLastPtsUs!! < trackDurationUs) {
                    "MP4 AAC last presentation timestamp must precede the video end"
                }
            }
        }
        return Report(
            width = width,
            height = height,
            frameCount = count,
            durationUs = trackDurationUs,
            mime = format.getString(MediaFormat.KEY_MIME) ?: Mp4SequenceExporter.MIME,
            audioSamples = audioSamples,
            audioFirstPtsUs = audioFirstPtsUs,
            audioLastPtsUs = audioLastPtsUs,
            audioTrackDurationUs = audioTrackDurationUs
        )
    }
}
