package com.codeair.effectcraft.data

import java.util.UUID

/**
 * The immutable audio timeline: the ordered set of [AudioClip]s for one
 * project, kept separate from the canonical .ecproj until the native
 * EffectCraft model exposes an audio schema (same split as the keyframe
 * store). Clips are kept sorted by timeline start; overlapping clips are
 * allowed and the mixer sums them, which is how layered audio is built.
 * Every mutation returns a new timeline; the caller persists the result
 * through [AudioTimelineStore].
 */
data class AudioTimeline(val clips: List<AudioClip> = emptyList()) {
    init {
        require(clips.map { it.id }.toSet().size == clips.size) { "Audio timeline has duplicate clip ids" }
        require(clips == clips.sortedWith(compareBy({ it.timelineStartUs }, { it.id }))) {
            "Audio timeline clips must be sorted by timeline start"
        }
    }

    companion object {
        val EMPTY = AudioTimeline()
    }

    val isEmpty: Boolean get() = clips.isEmpty()

    /** Exclusive end of the latest clip, or 0 when the timeline is empty. */
    val durationUs: Long get() = clips.maxOfOrNull { it.timelineEndUs } ?: 0L

    fun clip(id: String): AudioClip? = clips.firstOrNull { it.id == id }

    fun clipsAt(timelineUs: Long): List<AudioClip> = clips.filter { it.covers(timelineUs) }

    /**
     * Adds a clip for [sourceUri] at [timelineStartUs], trimming the
     * source to [durationUs] from [sourceStartUs]. The id is generated
     * here so callers never invent one.
     */
    fun addClip(
        sourceUri: String,
        sourceDurationUs: Long,
        timelineStartUs: Long,
        sourceStartUs: Long = 0L,
        durationUs: Long = sourceDurationUs - sourceStartUs,
        volume: Double = 1.0,
        fadeInUs: Long = 0L,
        fadeOutUs: Long = 0L
    ): AudioTimeline {
        val clip = AudioClip(
            id = UUID.randomUUID().toString(),
            sourceUri = sourceUri,
            sourceDurationUs = sourceDurationUs,
            timelineStartUs = timelineStartUs,
            sourceStartUs = sourceStartUs,
            durationUs = durationUs,
            volume = volume,
            fadeInUs = fadeInUs,
            fadeOutUs = fadeOutUs
        )
        return copy(clips = (clips + clip).sortedWith(compareBy({ it.timelineStartUs }, { it.id })))
    }

    fun removeClip(id: String): AudioTimeline {
        require(clip(id) != null) { "Audio timeline has no clip with id $id" }
        return copy(clips = clips.filterNot { it.id == id })
    }

    fun moveClip(id: String, newStartUs: Long): AudioTimeline =
        updateClip(id) { it.movedTo(newStartUs) }

    fun trimClip(id: String, newSourceStartUs: Long, newDurationUs: Long): AudioTimeline =
        updateClip(id) { it.trimmed(newSourceStartUs, newDurationUs) }

    fun setVolume(id: String, volume: Double): AudioTimeline =
        updateClip(id) { it.withVolume(volume) }

    fun setFades(id: String, fadeInUs: Long, fadeOutUs: Long): AudioTimeline =
        updateClip(id) { it.withFades(fadeInUs, fadeOutUs) }

    private fun updateClip(id: String, update: (AudioClip) -> AudioClip): AudioTimeline {
        val existing = clip(id) ?: throw IllegalArgumentException("Audio timeline has no clip with id $id")
        val updated = update(existing)
        val replaced = clips.map { if (it.id == id) updated else it }
        return copy(clips = replaced.sortedWith(compareBy({ it.timelineStartUs }, { it.id })))
    }
}
