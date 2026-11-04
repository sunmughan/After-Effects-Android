package com.codeair.effectcraft.data

/**
 * A scalar property keyframe on the composition timeline.
 *
 * Frames are zero-based and values are finite. Tracks are deliberately
 * independent of Compose so interpolation and edit semantics stay testable.
 */
data class PropertyKeyframe(val frame: Int, val value: Float)

data class PropertyTrack(
    val property: String,
    val keyframes: List<PropertyKeyframe>
)

/** Deterministic, immutable editing and evaluation operations for scalar tracks. */
object PropertyTrackEditor {
    fun upsert(track: PropertyTrack, frame: Int, value: Float): PropertyTrack {
        require(track.property.isNotBlank()) { "Property name must not be blank" }
        require(frame >= 0) { "Keyframe frame must be non-negative" }
        require(value.isFinite()) { "Keyframe value must be finite" }
        val next = track.keyframes.filterNot { it.frame == frame } + PropertyKeyframe(frame, value)
        return track.copy(keyframes = next.sortedBy { it.frame })
    }

    fun remove(track: PropertyTrack, frame: Int): PropertyTrack =
        track.copy(keyframes = track.keyframes.filterNot { it.frame == frame }.sortedBy { it.frame })

    /**
     * Drags the key at [fromFrame] to [toFrame], keeping its value. Dragging
     * past a neighbour reorders the track (keys stay sorted); dragging exactly
     * onto a neighbour replaces that neighbour, matching same-frame upsert
     * semantics. Negative targets and missing source keys are rejected.
     */
    fun move(track: PropertyTrack, fromFrame: Int, toFrame: Int): PropertyTrack {
        require(toFrame >= 0) { "Drag target frame must be non-negative" }
        val dragged = track.keyframes.firstOrNull { it.frame == fromFrame }
            ?: throw IllegalArgumentException("No keyframe at frame $fromFrame")
        if (fromFrame == toFrame) return track.copy(keyframes = track.keyframes.sortedBy { it.frame })
        val next = track.keyframes
            .filterNot { it.frame == fromFrame || it.frame == toFrame } + dragged.copy(frame = toFrame)
        return track.copy(keyframes = next.sortedBy { it.frame })
    }

    /**
     * Evaluates a scalar track using linear interpolation. Values outside the
     * keyed interval hold the nearest endpoint (constant extrapolation).
     * Returns null for an empty track.
     */
    fun evaluate(track: PropertyTrack, frame: Double): Float? {
        require(frame.isFinite()) { "Evaluation frame must be finite" }
        val keys = track.keyframes.sortedBy { it.frame }
            .groupBy { it.frame }
            .map { (_, sameFrame) -> sameFrame.last() }
        if (keys.isEmpty()) return null
        if (frame <= keys.first().frame) return keys.first().value
        if (frame >= keys.last().frame) return keys.last().value

        val rightIndex = keys.indexOfFirst { it.frame >= frame }
        val right = keys[rightIndex]
        val left = keys[rightIndex - 1]
        val span = (right.frame - left.frame).toDouble()
        if (span <= 0.0) return right.value
        val progress = ((frame - left.frame) / span).coerceIn(0.0, 1.0)
        return (left.value + (right.value - left.value) * progress).toFloat()
    }
}
