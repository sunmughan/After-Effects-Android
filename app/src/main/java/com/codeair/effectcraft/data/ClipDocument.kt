package com.codeair.effectcraft.data

import java.util.UUID

/**
 * One clip: a window of a layer's source media placed on the composition
 * timeline. All ranges are half-open [start, end), so a split at frame F
 * yields [start, F) and [F, end) with no frame lost or doubled.
 *
 * Invariants (enforced by [validationErrors] and [ClipTimeline]):
 * - 0 <= startFrame < endFrame <= composition durationFrames.
 * - 0 <= sourceStartFrame < sourceEndFrame <= sourceLengthFrames.
 * - Timeline length equals source span: endFrame - startFrame is always
 *   sourceEndFrame - sourceStartFrame, so trimming one end shifts the
 *   matching source point by the same delta and coverage stays consistent.
 * - No two clips on the same layer overlap; adjacency is allowed.
 *
 * A clip belongs to its layer structurally: it lives in
 * [LayerDocument.clips], so there is no separate layerId to drift.
 */
data class ClipDocument(
    val id: String,
    val startFrame: Int,
    val endFrame: Int,
    val sourceStartFrame: Int,
    val sourceEndFrame: Int,
    val sourceLengthFrames: Int
) {
    /** Timeline frames covered, always equal to [sourceSpan]. */
    val timelineLength: Int get() = endFrame - startFrame

    /** Source frames covered, always equal to [timelineLength]. */
    val sourceSpan: Int get() = sourceEndFrame - sourceStartFrame

    /** All invariant violations for this clip; empty means valid. */
    fun validationErrors(durationFrames: Int): List<String> = buildList {
        if (sourceLengthFrames < 1) add("Clip $id source length must be at least 1 frame")
        if (startFrame < 0) add("Clip $id starts before frame 0")
        if (endFrame > durationFrames) add("Clip $id ends after the composition duration ($durationFrames frames)")
        if (startFrame >= endFrame) add("Clip $id must start before it ends")
        if (sourceStartFrame < 0) add("Clip $id source starts before frame 0")
        if (sourceEndFrame > sourceLengthFrames) add("Clip $id source ends after its source media ($sourceLengthFrames frames)")
        if (sourceStartFrame >= sourceEndFrame) add("Clip $id source must start before it ends")
        if (timelineLength != sourceSpan) add("Clip $id timeline length ($timelineLength) must equal its source span ($sourceSpan)")
    }

    companion object {
        /** A clip covering the whole composition from the whole source. */
        fun fullRange(durationFrames: Int, sourceLengthFrames: Int = durationFrames): ClipDocument =
            ClipDocument(
                id = UUID.randomUUID().toString(),
                startFrame = 0,
                endFrame = durationFrames,
                sourceStartFrame = 0,
                sourceEndFrame = sourceLengthFrames,
                sourceLengthFrames = sourceLengthFrames
            )
    }
}
