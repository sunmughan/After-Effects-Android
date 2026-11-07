package com.codeair.effectcraft.data

/**
 * Audio policy for MP4 export. Timeline audio is decoded, mixed and
 * re-encoded to AAC inside the app ([MIXED]); when the timeline is
 * empty the pre-existing passthrough applies: an AAC (audio/mp4a-latm)
 * track is copied sample-for-sample through MediaExtractor/MediaMuxer,
 * and any other codec, missing file, or composition without audio
 * produces a video-only export. An audio track is never synthesized
 * or faked.
 */
enum class AudioStatus {
    INCLUDED,
    /** Timeline clips were decoded, mixed and encoded to AAC in-app. */
    MIXED,
    NO_AUDIO_IN_COMPOSITION,
    AUDIO_FILE_UNAVAILABLE,
    AUDIO_TRACK_UNSUPPORTED;

    /** True when the exported MP4 carries an audio track. */
    val hasAudioTrack: Boolean get() = this == INCLUDED || this == MIXED

    /** Visible status text: the video-only reason is always stated, never hidden. */
    fun describe(): String = when (this) {
        INCLUDED -> "with audio"
        MIXED -> "with mixed audio"
        NO_AUDIO_IN_COMPOSITION -> "video-only — composition has no audio"
        AUDIO_FILE_UNAVAILABLE -> "video-only — audio file unavailable"
        AUDIO_TRACK_UNSUPPORTED -> "video-only — no compatible AAC audio track"
    }
}

data class AudioCandidate(
    val mimeType: String,
    val sampleRateHz: Int,
    val channelCount: Int
)

object AudioTrackSelection {
    const val PASSTHROUGH_MIME = "audio/mp4a-latm"

    /** Index of the first AAC track, or null when no track can be passed through. */
    fun selectPassthroughIndex(candidates: List<AudioCandidate>): Int? =
        candidates.indexOfFirst { it.mimeType == PASSTHROUGH_MIME }.takeIf { it >= 0 }

    /**
     * Enforces the timestamp invariants of a finalized muxed AAC track.
     * The first sample may be at zero; subsequent samples must be strictly
     * later. Returns the current timestamp to make streaming validation
     * allocation-free. AAC priming offsets are handled before muxing; a
     * negative PTS in the final output is therefore invalid.
     */
    fun validateSamplePts(previousPtsUs: Long?, currentPtsUs: Long): Long {
        require(currentPtsUs >= 0L) { "AAC sample has a negative presentation timestamp" }
        require(previousPtsUs == null || currentPtsUs > previousPtsUs) {
            "AAC presentation timestamps are not strictly increasing"
        }
        return currentPtsUs
    }

    /**
     * Shifts an audio sample timestamp into the exported range, or null when
     * the sample falls outside [rangeStartUs, rangeEndUs). The range is in
     * composition-timeline microseconds, so the caller supplies composition
     * FPS-derived bounds, not export-FPS bounds, keeping audio in sync when
     * the export frame rate differs from the composition frame rate.
     */
    fun shiftedPts(ptsUs: Long, rangeStartUs: Long, rangeEndUs: Long): Long? {
        require(rangeEndUs >= rangeStartUs) { "Audio range end must be at or after its start" }
        if (ptsUs < rangeStartUs || ptsUs >= rangeEndUs) return null
        return ptsUs - rangeStartUs
    }
}
