package com.codeair.effectcraft.data

import java.util.UUID

/**
 * Pure audio clip operations: validation, trim, split, gain/fade/mute,
 * lookup, and duration clamping. No Android types, no I/O, no mutation
 * of the input lists — every operation returns a new list (or the same
 * list for a no-op), so undo is a plain snapshot restore and a crash
 * mid-edit leaves the previous list intact.
 *
 * Name note: this was `AudioTimeline` on feat/ec-audio-editing; it is
 * `AudioClipTimeline` here because the microsecond-based [AudioTimeline]
 * data class (the export mixer's input type, converted from project
 * clips by [AudioTimeline.fromAudioTracks]) already owns that name.
 * Batch 2 (P15): the editor has ONE audio model — the frame-based
 * [AudioClipDocument]s in [ProjectDocument.audioTracks]. [AudioTimeline]
 * is no longer editor state; it exists only as the mixer's input.
 *
 * Trim and split delegate to [ClipTimeline] for the timeline geometry
 * (source in/out shift by the same deltas, coverage preserved exactly)
 * and only re-apply the audio settings afterwards:
 * - Trim clamps the fades to the new length (fade-in first, fade-out
 *   to the remainder), so a trimmed clip is always still valid.
 * - Split keeps the fade-in on the first half and the fade-out on the
 *   second half, each clamped to its half. The first half keeps the
 *   original id; the second half gets a fresh id.
 */
sealed interface AudioClipEditOutcome {
    data class Changed(val clips: List<AudioClipDocument>) : AudioClipEditOutcome
    data class Unchanged(val reason: String) : AudioClipEditOutcome
}

object AudioClipTimeline {
    /** Every invariant violation across one track's clips; empty means valid. */
    fun validateAudioClips(clips: List<AudioClipDocument>, durationFrames: Int): List<String> = buildList {
        clips.forEach { addAll(it.validationErrors(durationFrames)) }
        val ids = clips.map { it.id }
        if (ids.toSet().size != ids.size) add("Audio clip IDs must be unique within a track")
        clips.sortedBy { it.clip.startFrame }.zipWithNext { previous, next ->
            if (next.clip.startFrame < previous.clip.endFrame) {
                add("Audio clips ${previous.id} and ${next.id} overlap on the same track")
            }
        }
    }

    /** The audio clip covering [frame], or null when the frame falls in a gap. */
    fun clipAt(clips: List<AudioClipDocument>, frame: Int): AudioClipDocument? =
        clips.firstOrNull { frame in it.clip.startFrame until it.clip.endFrame }

    /** The audio clip covering [frame] on any track, or null in a gap. */
    fun audioClipAtPlayhead(tracks: List<AudioTrackDocument>, frame: Int): AudioClipDocument? =
        tracks.firstNotNullOfOrNull { track -> clipAt(track.clips, frame) }

    /**
     * Places [clip] on the first track it does not overlap, or on a new
     * track named after the highest existing "Audio N" number. One
     * function owns placement so the import path and the legacy migration
     * can never disagree about where a clip lands.
     */
    fun placeClip(tracks: List<AudioTrackDocument>, clip: AudioClipDocument): List<AudioTrackDocument> {
        val fitting = tracks.firstOrNull { track ->
            track.clips.none { existing ->
                clip.clip.startFrame < existing.clip.endFrame && existing.clip.startFrame < clip.clip.endFrame
            }
        }
        if (fitting != null) {
            return tracks.map { if (it.id == fitting.id) it.copy(clips = it.clips + clip) else it }
        }
        val highestNumber = tracks.mapNotNull { track ->
            track.name.removePrefix("Audio ").toIntOrNull()?.takeIf { track.name.startsWith("Audio ") }
        }.maxOrNull() ?: tracks.size
        val nextNumber = maxOf(highestNumber + 1, tracks.size + 1)
        return tracks + AudioTrackDocument.named("Audio $nextNumber").copy(clips = listOf(clip))
    }

    /**
     * Moves one audio clip to [newStartFrame], keeping its source window
     * untouched: only the timeline placement changes. Refused when the
     * move would leave the composition or overlap another clip on the
     * same track.
     */
    fun moveAudioClip(
        clips: List<AudioClipDocument>,
        clipId: String,
        newStartFrame: Int,
        durationFrames: Int
    ): AudioClipEditOutcome {
        val clip = clips.firstOrNull { it.id == clipId }
            ?: return AudioClipEditOutcome.Unchanged("Audio clip $clipId does not exist")
        val newEnd = newStartFrame + clip.timelineLength
        if (newStartFrame < 0 || newEnd > durationFrames) {
            return AudioClipEditOutcome.Unchanged("Moved audio clip must stay inside the composition (0–$durationFrames frames)")
        }
        if (newStartFrame == clip.clip.startFrame) {
            return AudioClipEditOutcome.Unchanged("Audio clip is already at frame $newStartFrame")
        }
        val overlaps = clips.any { other ->
            other.id != clipId && newStartFrame < other.clip.endFrame && other.clip.startFrame < newEnd
        }
        if (overlaps) return AudioClipEditOutcome.Unchanged("Moved audio clip would overlap another clip on the same track")
        val moved = clip.copy(clip = clip.clip.copy(startFrame = newStartFrame, endFrame = newEnd))
        return AudioClipEditOutcome.Changed(clips.map { if (it.id == clipId) moved else it })
    }

    /**
     * Converts one legacy microsecond [AudioClip] (the retired
     * AudioTimelineStore model) into the unified frame-based model, or
     * null when the clip has no audible frame inside the composition
     * (starts at/after the end, or rounds to zero frames). The caller
     * supplies the probed [sourcePath]/[sourceMime]: the legacy clip only
     * carried a URI string.
     */
    fun migratedClip(
        clip: AudioClip,
        frameRate: Int,
        durationFrames: Int,
        sourcePath: String,
        sourceMime: String
    ): AudioClipDocument? {
        val fps = frameRate.coerceAtLeast(1)
        fun usToFrames(us: Long): Int = ((us * fps) / 1_000_000L).toInt()
        val sourceLengthFrames = usToFrames(clip.sourceDurationUs).coerceAtLeast(1)
        val startFrame = usToFrames(clip.timelineStartUs)
        val sourceStartFrame = usToFrames(clip.sourceStartUs).coerceIn(0, sourceLengthFrames - 1)
        val endFrame = minOf(durationFrames, startFrame + usToFrames(clip.durationUs))
            .coerceAtMost(startFrame + (sourceLengthFrames - sourceStartFrame))
        if (startFrame < 0 || endFrame <= startFrame) return null
        return AudioClipDocument.covering(
            startFrame = startFrame,
            endFrame = endFrame,
            sourceStartFrame = sourceStartFrame,
            sourceEndFrame = sourceStartFrame + (endFrame - startFrame),
            sourceLengthFrames = sourceLengthFrames,
            sourcePath = sourcePath,
            sourceMime = sourceMime,
            sourceDurationUs = clip.sourceDurationUs,
            id = clip.id
        ).copy(
            gain = clip.volume.toFloat().coerceIn(0f, AudioClipDocument.MAX_GAIN),
            fadeInFrames = usToFrames(clip.fadeInUs).coerceIn(0, endFrame - startFrame),
            fadeOutFrames = usToFrames(clip.fadeOutUs).coerceIn(0, endFrame - startFrame)
        )
    }

    /**
     * Changes an audio clip's timeline bounds through [ClipTimeline.trimClip];
     * the source in/out points shift by the same deltas and the fades are
     * clamped to the new length. Refused trims return [Unchanged] with a
     * reason instead of throwing or clamping silently.
     */
    fun trimAudioClip(
        clips: List<AudioClipDocument>,
        clipId: String,
        newStartFrame: Int?,
        newEndFrame: Int?,
        durationFrames: Int
    ): AudioClipEditOutcome {
        val plain = clips.map { it.clip }
        return when (val outcome = ClipTimeline.trimClip(plain, clipId, newStartFrame, newEndFrame, durationFrames)) {
            is ClipEditOutcome.Unchanged -> AudioClipEditOutcome.Unchanged(outcome.reason)
            is ClipEditOutcome.Changed -> {
                val updated = outcome.clips.first { it.id == clipId }
                val original = clips.first { it.id == clipId }
                val (fadeIn, fadeOut) = clampedFades(updated.timelineLength, original.fadeInFrames, original.fadeOutFrames)
                AudioClipEditOutcome.Changed(
                    clips.map { if (it.id == clipId) original.copy(clip = updated, fadeInFrames = fadeIn, fadeOutFrames = fadeOut) else it }
                )
            }
        }
    }

    /**
     * Divides one audio clip at [frame] into two clips preserving the
     * original coverage exactly. The first half keeps the fade-in, the
     * second half keeps the fade-out, each clamped to its half; gain,
     * mute, source file and MIME carry to both halves.
     */
    fun splitAudioClip(
        clips: List<AudioClipDocument>,
        clipId: String,
        frame: Int,
        durationFrames: Int,
        newClipId: () -> String = { UUID.randomUUID().toString() }
    ): AudioClipEditOutcome {
        val plain = clips.map { it.clip }
        val secondId = newClipId()
        return when (val outcome = ClipTimeline.splitClip(plain, clipId, frame, durationFrames) { secondId }) {
            is ClipEditOutcome.Unchanged -> AudioClipEditOutcome.Unchanged(outcome.reason)
            is ClipEditOutcome.Changed -> {
                val original = clips.first { it.id == clipId }
                val firstClip = outcome.clips.first { it.id == clipId }
                val secondClip = outcome.clips.first { it.id == secondId }
                val (firstFadeIn, _) = clampedFades(firstClip.timelineLength, original.fadeInFrames, 0)
                val (_, secondFadeOut) = clampedFades(secondClip.timelineLength, 0, original.fadeOutFrames)
                val first = original.copy(clip = firstClip, fadeInFrames = firstFadeIn, fadeOutFrames = 0)
                val second = original.copy(clip = secondClip, fadeInFrames = 0, fadeOutFrames = secondFadeOut)
                AudioClipEditOutcome.Changed(
                    clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) }
                )
            }
        }
    }

    /** Sets a clip's gain; out-of-range or non-finite gain is refused, never clamped. */
    fun setGain(clips: List<AudioClipDocument>, clipId: String, gain: Float): AudioClipEditOutcome {
        val clip = clips.firstOrNull { it.id == clipId }
            ?: return AudioClipEditOutcome.Unchanged("Audio clip $clipId does not exist")
        if (!gain.isFinite() || gain !in 0f..AudioClipDocument.MAX_GAIN) {
            return AudioClipEditOutcome.Unchanged("Gain must be finite and between 0 and ${AudioClipDocument.MAX_GAIN}")
        }
        if (gain == clip.gain) return AudioClipEditOutcome.Unchanged("Audio clip gain is already $gain")
        return AudioClipEditOutcome.Changed(clips.map { if (it.id == clipId) it.copy(gain = gain) else it })
    }

    /** Sets a clip's fades; fades that do not fit inside the clip are refused. */
    fun setFades(clips: List<AudioClipDocument>, clipId: String, fadeInFrames: Int, fadeOutFrames: Int): AudioClipEditOutcome {
        val clip = clips.firstOrNull { it.id == clipId }
            ?: return AudioClipEditOutcome.Unchanged("Audio clip $clipId does not exist")
        if (fadeInFrames < 0 || fadeOutFrames < 0) {
            return AudioClipEditOutcome.Unchanged("Fades must not be negative")
        }
        if (fadeInFrames + fadeOutFrames > clip.timelineLength) {
            return AudioClipEditOutcome.Unchanged("Fades ($fadeInFrames + $fadeOutFrames frames) exceed the clip length (${clip.timelineLength} frames)")
        }
        if (fadeInFrames == clip.fadeInFrames && fadeOutFrames == clip.fadeOutFrames) {
            return AudioClipEditOutcome.Unchanged("Audio clip fades are already $fadeInFrames/$fadeOutFrames frames")
        }
        return AudioClipEditOutcome.Changed(
            clips.map { if (it.id == clipId) it.copy(fadeInFrames = fadeInFrames, fadeOutFrames = fadeOutFrames) else it }
        )
    }

    /** Sets a clip's mute flag; setting the value it already has is a no-op with a reason. */
    fun setMuted(clips: List<AudioClipDocument>, clipId: String, muted: Boolean): AudioClipEditOutcome {
        val clip = clips.firstOrNull { it.id == clipId }
            ?: return AudioClipEditOutcome.Unchanged("Audio clip $clipId does not exist")
        if (muted == clip.muted) {
            return AudioClipEditOutcome.Unchanged("Audio clip is already ${if (muted) "muted" else "unmuted"}")
        }
        return AudioClipEditOutcome.Changed(clips.map { if (it.id == clipId) it.copy(muted = muted) else it })
    }

    /**
     * Fits clips to a shorter composition: clips ending past the new
     * duration are trimmed (source out-point shifts with the timeline
     * end) and clips starting at or after it are dropped, with fades
     * clamped to each surviving clip's new length. A zero or negative
     * duration leaves no clips.
     */
    fun clampToDuration(clips: List<AudioClipDocument>, durationFrames: Int): List<AudioClipDocument> {
        if (durationFrames <= 0) return emptyList()
        val plain = ClipTimeline.clampToDuration(clips.map { it.clip }, durationFrames)
        val surviving = plain.map { it.id }.toSet()
        return clips.filter { it.id in surviving }.map { audio ->
            val trimmed = plain.first { it.id == audio.id }
            val (fadeIn, fadeOut) = clampedFades(trimmed.timelineLength, audio.fadeInFrames, audio.fadeOutFrames)
            audio.copy(clip = trimmed, fadeInFrames = fadeIn, fadeOutFrames = fadeOut)
        }
    }

    /** Clamps fades to [length]: fade-in first, fade-out to the remainder. */
    private fun clampedFades(length: Int, fadeIn: Int, fadeOut: Int): Pair<Int, Int> {
        val clampedIn = fadeIn.coerceIn(0, length)
        val clampedOut = fadeOut.coerceIn(0, length - clampedIn)
        return clampedIn to clampedOut
    }
}
