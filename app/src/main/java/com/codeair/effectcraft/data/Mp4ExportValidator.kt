package com.codeair.effectcraft.data

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Verifies a staged MP4 with MediaExtractor before any SAF copy, mirroring
 * the ZIP exporter's staged-integrity discipline. Rejects a missing or
 * empty file, a missing H.264 video track, a video sample count that does
 * not equal the plan frame count, and a duration that disagrees with the
 * plan by more than one frame. When audio was muxed, the AAC track must
 * also be present with at least one sample. A rejected file is never
 * copied; the caller deletes it.
 */
object Mp4ExportValidator {
    data class Result(
        val videoSamples: Long,
        val durationUs: Long,
        val audioSamples: Long,
        /** First muxed AAC sample PTS; null when the output has no audio track. */
        val audioFirstPtsUs: Long? = null,
        /** Last muxed AAC sample PTS; null when the output has no audio track. */
        val audioLastPtsUs: Long? = null,
        /** MediaExtractor's AAC track duration, when the container exposes it. */
        val audioTrackDurationUs: Long? = null
    )

    fun validate(file: File, plan: RenderQueuePlan, expectAudio: Boolean): Result {
        Mp4ExportRequirements.checkPlan(plan)
        require(file.isFile && file.length() > 0L) { "MP4 output is missing or empty" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var audioTrack = -1
            var videoDurationUs: Long? = null
            var audioTrackDurationUs: Long? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime == "video/avc" && videoTrack < 0) {
                    videoTrack = index
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                }
                if (mime == "audio/mp4a-latm" && audioTrack < 0) {
                    audioTrack = index
                    audioTrackDurationUs = format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                        ?.getLong(MediaFormat.KEY_DURATION)
                }
            }
            require(videoTrack >= 0) { "MP4 is missing its H.264 video track" }
            if (expectAudio) {
                require(audioTrack >= 0) { "MP4 is missing its muxed AAC audio track" }
            }
            val videoSamples = countSamples(extractor, videoTrack, unselectFirst = null, bufferBytes = 4 * 1024 * 1024)
            require(videoSamples == plan.frameCount.toLong()) {
                "MP4 contains $videoSamples video samples, expected ${plan.frameCount}"
            }
            val frameDurationUs = Mp4ExportRequirements.frameDurationUs(plan.settings.frameRate)
            val expectedUs = Mp4ExportRequirements.expectedDurationUs(plan.frameCount, plan.settings.frameRate)
            // Video is still the only selected track here, so the fallback
            // re-read stays on the video track.
            val durationUs = videoDurationUs ?: (lastVideoPts(extractor, videoTrack) + frameDurationUs)
            require(durationUs in expectedUs - frameDurationUs..expectedUs + frameDurationUs) {
                "MP4 duration ${durationUs}us does not match plan duration ${expectedUs}us"
            }
            var audioSamples = 0L
            var audioFirstPtsUs: Long? = null
            var audioLastPtsUs: Long? = null
            if (audioTrack >= 0) {
                audioSamples = countSamples(extractor, audioTrack, unselectFirst = videoTrack, bufferBytes = 256 * 1024)
                if (expectAudio) {
                    require(audioSamples > 0L) { "MP4 AAC audio track contains no samples" }
                }
                if (audioSamples > 0L) {
                    val timing = sampleWindow(extractor, bufferBytes = 256 * 1024)
                    audioFirstPtsUs = timing.first
                    audioLastPtsUs = timing.second
                    require(audioFirstPtsUs != null && audioFirstPtsUs >= 0L) {
                        "MP4 AAC first presentation timestamp must be nonnegative"
                    }
                    require(audioLastPtsUs != null && audioLastPtsUs < durationUs) {
                        "MP4 AAC last presentation timestamp must precede the video end"
                    }
                }
            }
            return Result(videoSamples, durationUs, audioSamples, audioFirstPtsUs, audioLastPtsUs, audioTrackDurationUs)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Rewinds the currently selected AAC track and captures a validated PTS window. */
    private fun sampleWindow(extractor: MediaExtractor, bufferBytes: Int): Pair<Long?, Long?> {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(bufferBytes)
        var first: Long? = null
        var last: Long? = null
        while (true) {
            if (extractor.readSampleData(buffer, 0) < 0) break
            val pts = extractor.sampleTime
            last = AudioTrackSelection.validateSamplePts(previousPtsUs = last, currentPtsUs = pts)
            if (first == null) first = pts
            extractor.advance()
        }
        return first to last
    }

    private fun lastVideoPts(extractor: MediaExtractor, track: Int): Long {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var last = 0L
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        while (true) {
            if (extractor.readSampleData(buffer, 0) < 0) break
            last = extractor.sampleTime
            extractor.advance()
        }
        return last
    }

    private fun countSamples(
        extractor: MediaExtractor,
        track: Int,
        unselectFirst: Int?,
        bufferBytes: Int
    ): Long {
        if (unselectFirst != null) extractor.unselectTrack(unselectFirst)
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(bufferBytes)
        var count = 0L
        while (true) {
            if (extractor.readSampleData(buffer, 0) < 0) break
            count++
            extractor.advance()
        }
        return count
    }
}
