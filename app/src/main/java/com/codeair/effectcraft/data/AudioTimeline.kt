package com.codeair.effectcraft.data

import java.util.UUID

/**
 * The immutable audio timeline: the ordered set of [AudioClip]s for one
 * export mix, kept separate from the canonical .ecproj until the native
 * EffectCraft model exposes an audio schema (same split as the keyframe
 * store). Clips are kept sorted by timeline start; overlapping clips are
 * allowed and the mixer sums them, which is how layered audio is built.
 * Every mutation returns a new timeline.
 *
 * Batch 2 (P15): this is NOT editor state. The editor's one audio model
 * is the frame-based [AudioClipDocument] in [ProjectDocument.audioTracks];
 * [fromAudioTracks] converts it for the export mixer, the only consumer
 * left. The per-project AudioTimelineStore is deleted with it.
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

        /**
         * Batch 2 (P15): the editor's one audio model is the frame-based
         * [AudioClipDocument] in [ProjectDocument.audioTracks]. The export
         * mixer still consumes microsecond [AudioClip]s, so this converts
         * project clips for that one consumer — it is not a second editor
         * model. Muted clips are skipped (the mixer would sum silence for
         * them anyway) and gain above unity is clamped: the mixer takes
         * volume in 0..1, while the project model allows up to +6 dB.
         */
        fun fromAudioTracks(
            tracks: List<AudioTrackDocument>,
            frameRateNum: Int,
            frameRateDen: Int
        ): AudioTimeline {
            val num = frameRateNum.coerceAtLeast(1).toLong()
            val den = frameRateDen.coerceAtLeast(1).toLong()
            fun framesToUs(frames: Int): Long = frames.toLong() * 1_000_000L * den / num
            val clips = tracks.flatMap { it.clips }
                .filterNot { it.muted }
                .map { doc ->
                    AudioClip(
                        id = doc.id,
                        sourceUri = doc.sourcePath,
                        sourceDurationUs = framesToUs(doc.clip.sourceLengthFrames),
                        timelineStartUs = framesToUs(doc.clip.startFrame),
                        sourceStartUs = framesToUs(doc.clip.sourceStartFrame),
                        durationUs = framesToUs(doc.timelineLength),
                        volume = doc.gain.toDouble().coerceIn(0.0, 1.0),
                        fadeInUs = framesToUs(doc.fadeInFrames),
                        fadeOutUs = framesToUs(doc.fadeOutFrames)
                    )
                }
                .sortedWith(compareBy({ it.timelineStartUs }, { it.id }))
            return AudioTimeline(clips)
        }
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
