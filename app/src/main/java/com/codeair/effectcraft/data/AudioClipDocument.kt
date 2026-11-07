package com.codeair.effectcraft.data

import java.util.UUID

/**
 * One audio clip: a window of a source audio file placed on the
 * composition timeline. The timeline geometry and the source in/out
 * points are a [ClipDocument], so audio clips reuse exactly the video
 * clip trim/split semantics (half-open [start, end), timeline length
 * equals source span, 1:1 speed, no time-stretch) — only the settings
 * below are audio-specific.
 *
 * The clip's [id] is its [ClipDocument]'s id: there is one identity,
 * never two that can drift.
 *
 * Settings invariants (enforced by [validationErrors]):
 * - gain in 0..2 (0 silent, 1 unity, 2 is +6 dB). Out-of-range or
 *   non-finite gain is invalid, never clamped silently.
 * - fadeInFrames, fadeOutFrames >= 0 and their sum never exceeds the
 *   clip length: a fade always fits inside the clip it belongs to.
 * - sourceDurationUs >= 1: a zero-length source cannot play.
 */
data class AudioClipDocument(
    val clip: ClipDocument,
    val sourcePath: String,
    val sourceMime: String,
    val sourceDurationUs: Long,
    val gain: Float = 1f,
    val fadeInFrames: Int = 0,
    val fadeOutFrames: Int = 0,
    val muted: Boolean = false
) {
    /** The clip's single identity: its ClipDocument's id. */
    val id: String get() = clip.id

    /** Timeline frames covered, always equal to [ClipDocument.sourceSpan]. */
    val timelineLength: Int get() = clip.timelineLength

    /** All invariant violations for this clip; empty means valid. */
    fun validationErrors(durationFrames: Int): List<String> = buildList {
        addAll(clip.validationErrors(durationFrames))
        if (sourcePath.isBlank()) add("Audio clip $id has no source file")
        if (sourceMime.isBlank()) add("Audio clip $id has no source MIME type")
        if (sourceDurationUs < 1L) add("Audio clip $id source duration must be at least 1 microsecond")
        if (!gain.isFinite() || gain !in 0f..MAX_GAIN) {
            add("Audio clip $id gain must be finite and between 0 and $MAX_GAIN")
        }
        if (fadeInFrames < 0) add("Audio clip $id fade-in must not be negative")
        if (fadeOutFrames < 0) add("Audio clip $id fade-out must not be negative")
        if (fadeInFrames + fadeOutFrames > timelineLength) {
            add("Audio clip $id fades ($fadeInFrames + $fadeOutFrames frames) exceed its length ($timelineLength frames)")
        }
    }

    companion object {
        /** Maximum gain: 2.0 is +6 dB above unity. */
        const val MAX_GAIN = 2f

        /**
         * A clip covering [startFrame, endFrame) of the composition from
         * [sourceStartFrame, sourceEndFrame) of a source whose length in
         * composition frames is [sourceLengthFrames].
         */
        fun covering(
            startFrame: Int,
            endFrame: Int,
            sourceStartFrame: Int,
            sourceEndFrame: Int,
            sourceLengthFrames: Int,
            sourcePath: String,
            sourceMime: String,
            sourceDurationUs: Long,
            id: String = UUID.randomUUID().toString()
        ): AudioClipDocument = AudioClipDocument(
            clip = ClipDocument(
                id = id,
                startFrame = startFrame,
                endFrame = endFrame,
                sourceStartFrame = sourceStartFrame,
                sourceEndFrame = sourceEndFrame,
                sourceLengthFrames = sourceLengthFrames
            ),
            sourcePath = sourcePath,
            sourceMime = sourceMime,
            sourceDurationUs = sourceDurationUs
        )
    }
}

/**
 * One audio track: an ordered set of audio clips that never overlap each
 * other. Several tracks may coexist in one composition; the mixdown sums
 * across tracks. A track belongs to its project structurally: it lives in
 * [ProjectDocument.audioTracks], so there is no separate projectId to drift.
 */
data class AudioTrackDocument(
    val id: String,
    val name: String,
    val clips: List<AudioClipDocument> = emptyList()
) {
    /** All invariant violations for this track; empty means valid. */
    fun validationErrors(durationFrames: Int): List<String> = buildList {
        if (name.isBlank()) add("Audio track $id name must not be blank")
        clips.forEach { addAll(it.validationErrors(durationFrames)) }
        val ids = clips.map { it.id }
        if (ids.toSet().size != ids.size) add("Audio clip IDs must be unique within a track")
        clips.sortedBy { it.clip.startFrame }.zipWithNext { previous, next ->
            if (next.clip.startFrame < previous.clip.endFrame) {
                add("Audio clips ${previous.id} and ${next.id} overlap on the same track")
            }
        }
    }

    companion object {
        /** A new empty track with a fresh id. */
        fun named(name: String): AudioTrackDocument = AudioTrackDocument(
            id = UUID.randomUUID().toString(),
            name = name
        )
    }
}
