package com.codeair.effectcraft.data

/**
 * One audio clip on the timeline: a trimmed window of a decoded audio
 * source, placed at a composition-timeline offset, with its own volume
 * and linear fades. All times are microseconds.
 *
 * The clip is pure data: decoding happens in [AudioPcmSource] and mixing
 * in [AudioMixer], so this model is fully unit-testable on the JVM.
 * Volume is linear gain in [0, 1] (0 = silent, 1 = full); fades are
 * linear ramps inside the clip, each no longer than the clip itself.
 */
data class AudioClip(
    val id: String,
    val sourceUri: String,
    val sourceDurationUs: Long,
    val timelineStartUs: Long,
    val sourceStartUs: Long,
    val durationUs: Long,
    val volume: Double = 1.0,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L
) {
    init {
        require(id.isNotBlank()) { "Audio clip id must not be blank" }
        require(sourceUri.isNotBlank()) { "Audio clip source must not be blank" }
        require(sourceDurationUs > 0L) { "Audio source duration must be positive" }
        require(timelineStartUs >= 0L) { "Audio clip timeline start cannot be negative" }
        require(sourceStartUs >= 0L) { "Audio clip source start cannot be negative" }
        require(durationUs > 0L) { "Audio clip duration must be positive" }
        require(sourceStartUs + durationUs <= sourceDurationUs) {
            "Audio clip window extends past the end of its source"
        }
        require(volume in 0.0..1.0) { "Audio clip volume must be between 0 and 1" }
        require(fadeInUs >= 0L && fadeOutUs >= 0L) { "Audio clip fades cannot be negative" }
        require(fadeInUs <= durationUs && fadeOutUs <= durationUs) {
            "Audio clip fades cannot be longer than the clip"
        }
    }

    /** Exclusive end of the clip on the composition timeline. */
    val timelineEndUs: Long get() = timelineStartUs + durationUs

    /** Exclusive end of the trimmed window inside the source. */
    val sourceEndUs: Long get() = sourceStartUs + durationUs

    /** True when [timelineUs] falls inside this clip's timeline window. */
    fun covers(timelineUs: Long): Boolean =
        timelineUs in timelineStartUs until timelineEndUs

    /**
     * Linear gain at [timelineUs]: volume multiplied by the fade-in ramp
     * (0 at the clip start, 1 after [fadeInUs]) and the fade-out ramp
     * (1 until [fadeOutUs] before the clip end, 0 at the end). Zero
     * outside the clip, so the mixer can call this unconditionally.
     */
    fun gainAt(timelineUs: Long): Double {
        if (!covers(timelineUs)) return 0.0
        val offsetUs = timelineUs - timelineStartUs
        val fadeIn = if (fadeInUs == 0L) 1.0 else (offsetUs.toDouble() / fadeInUs).coerceIn(0.0, 1.0)
        val remainingUs = timelineEndUs - timelineUs
        val fadeOut = if (fadeOutUs == 0L) 1.0 else (remainingUs.toDouble() / fadeOutUs).coerceIn(0.0, 1.0)
        return volume * fadeIn * fadeOut
    }

    /** The same clip placed at [newStartUs] on the timeline. */
    fun movedTo(newStartUs: Long): AudioClip {
        require(newStartUs >= 0L) { "Audio clip timeline start cannot be negative" }
        return copy(timelineStartUs = newStartUs)
    }

    /**
     * The same clip retimed to a new trimmed window of its source.
     * The timeline position is unchanged; only the audible window moves.
     */
    fun trimmed(newSourceStartUs: Long, newDurationUs: Long): AudioClip {
        require(newSourceStartUs >= 0L) { "Audio clip source start cannot be negative" }
        require(newDurationUs > 0L) { "Audio clip duration must be positive" }
        require(newSourceStartUs + newDurationUs <= sourceDurationUs) {
            "Audio clip window extends past the end of its source"
        }
        return copy(sourceStartUs = newSourceStartUs, durationUs = newDurationUs)
    }

    fun withVolume(newVolume: Double): AudioClip {
        require(newVolume in 0.0..1.0) { "Audio clip volume must be between 0 and 1" }
        return copy(volume = newVolume)
    }

    fun withFades(newFadeInUs: Long, newFadeOutUs: Long): AudioClip {
        require(newFadeInUs >= 0L && newFadeOutUs >= 0L) { "Audio clip fades cannot be negative" }
        require(newFadeInUs <= durationUs && newFadeOutUs <= durationUs) {
            "Audio clip fades cannot be longer than the clip"
        }
        return copy(fadeInUs = newFadeInUs, fadeOutUs = newFadeOutUs)
    }
}
