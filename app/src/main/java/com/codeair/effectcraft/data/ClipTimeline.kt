package com.codeair.effectcraft.data

import java.util.UUID

/**
 * Pure clip operations: validation, trim, split, lookup, and duration
 * clamping. No Android types, no I/O, no mutation of the input lists —
 * every operation returns a new list (or the same list for a no-op), so
 * undo is a plain snapshot restore and a crash mid-edit leaves the
 * previous list intact.
 *
 * Trim rule: trimming the timeline start shifts the source in-point by the
 * same delta; trimming the timeline end shifts the source out-point. A
 * trim may extend back into unused source (source in/out permitting) but
 * never past a neighbouring clip or past the source media.
 *
 * Split rule: splitting at frame F divides [start, end) into [start, F)
 * and [F, end), with the source span divided at the same frame. The first
 * half keeps the original id; the second half gets a fresh id.
 */
/** The outcome of a trim or split: either new clips or a reason why nothing changed. */
sealed interface ClipEditOutcome {
    data class Changed(val clips: List<ClipDocument>) : ClipEditOutcome
    data class Unchanged(val reason: String) : ClipEditOutcome
}

object ClipTimeline {
    /** Every invariant violation across one layer's clips; empty means valid. */
    fun validateClips(clips: List<ClipDocument>, durationFrames: Int): List<String> = buildList {
        clips.forEach { addAll(it.validationErrors(durationFrames)) }
        val ids = clips.map { it.id }
        if (ids.toSet().size != ids.size) add("Clip IDs must be unique within a layer")
        clips.sortedBy { it.startFrame }.zipWithNext { previous, next ->
            if (next.startFrame < previous.endFrame) {
                add("Clips ${previous.id} and ${next.id} overlap on the same layer")
            }
        }
    }

    /** The clip covering [frame], or null when the frame falls in a gap. */
    fun clipAt(clips: List<ClipDocument>, frame: Int): ClipDocument? =
        clips.firstOrNull { frame in it.startFrame until it.endFrame }

    /**
     * Timeline ranges a layer covers: each clip's range intersected with
     * the layer's legacy in/out window, so a frame renders only when it
     * is inside both. A layer with no clips follows its legacy window
     * alone (full composition when untrimmed), so documents written
     * before clips existed keep their behaviour instead of going dark.
     * The editor mutations keep the two representations in sync, so the
     * intersection normally equals the clips; it only matters for a
     * directly constructed layer whose sides disagree.
     */
    fun layerCoverage(layer: LayerDocument, durationFrames: Int): List<IntRange> {
        if (durationFrames <= 0) return emptyList()
        val windowStart = layer.inFrame.coerceIn(0, durationFrames - 1)
        val windowEndExclusive = ((layer.outFrame ?: durationFrames - 1).coerceIn(0, durationFrames - 1)) + 1
        if (layer.clips.isEmpty()) {
            return listOf(windowStart until windowEndExclusive)
        }
        return layer.clips.sortedBy { it.startFrame }.mapNotNull { clip ->
            val start = maxOf(clip.startFrame, windowStart)
            val end = minOf(clip.endFrame, windowEndExclusive)
            if (start < end) start until end else null
        }
    }

    /**
     * Changes a clip's timeline bounds; null keeps that side. The source
     * in/out points shift by the same deltas, so timeline length always
     * equals the source span afterwards. Refused trims return [Unchanged]
     * with a reason instead of throwing or clamping silently.
     */
    fun trimClip(
        clips: List<ClipDocument>,
        clipId: String,
        newStartFrame: Int?,
        newEndFrame: Int?,
        durationFrames: Int
    ): ClipEditOutcome {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return ClipEditOutcome.Unchanged("Clip $clipId does not exist")
        val clip = clips[index]
        val newStart = newStartFrame ?: clip.startFrame
        val newEnd = newEndFrame ?: clip.endFrame
        if (newStart == clip.startFrame && newEnd == clip.endFrame) {
            return ClipEditOutcome.Unchanged("Clip is already at frames $newStart–$newEnd")
        }
        if (newStart < 0 || newEnd > durationFrames) {
            return ClipEditOutcome.Unchanged("Trimmed clip must stay inside the composition (0–$durationFrames frames)")
        }
        if (newStart >= newEnd) {
            return ClipEditOutcome.Unchanged("A clip must start before it ends")
        }
        val others = clips.filterIndexed { i, _ -> i != index }
        val previousEnd = others.filter { it.endFrame <= clip.startFrame }.maxOfOrNull { it.endFrame } ?: 0
        if (newStart < previousEnd) {
            return ClipEditOutcome.Unchanged("Trim would overlap the previous clip on this layer")
        }
        val nextStart = others.filter { it.startFrame >= clip.endFrame }.minOfOrNull { it.startFrame } ?: durationFrames
        if (newEnd > nextStart) {
            return ClipEditOutcome.Unchanged("Trim would overlap the next clip on this layer")
        }
        val newSourceStart = clip.sourceStartFrame + (newStart - clip.startFrame)
        if (newSourceStart < 0) {
            return ClipEditOutcome.Unchanged("Trim would start before the source media")
        }
        val newSourceEnd = clip.sourceEndFrame + (newEnd - clip.endFrame)
        if (newSourceEnd > clip.sourceLengthFrames) {
            return ClipEditOutcome.Unchanged("Trim would end after the source media")
        }
        val updated = clip.copy(
            startFrame = newStart,
            endFrame = newEnd,
            sourceStartFrame = newSourceStart,
            sourceEndFrame = newSourceEnd
        )
        return ClipEditOutcome.Changed(clips.toMutableList().apply { set(index, updated) })
    }

    /**
     * Divides one clip at [frame] into two clips preserving the original
     * coverage exactly. Splitting at a clip edge (or outside the clip) is
     * a no-op with a reason, never a crash.
     */
    fun splitClip(
        clips: List<ClipDocument>,
        clipId: String,
        frame: Int,
        durationFrames: Int,
        newClipId: () -> String = { UUID.randomUUID().toString() }
    ): ClipEditOutcome {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return ClipEditOutcome.Unchanged("Clip $clipId does not exist")
        val clip = clips[index]
        if (frame <= clip.startFrame || frame >= clip.endFrame) {
            return ClipEditOutcome.Unchanged("Frame $frame is at the edge of clip $clipId, so there is nothing to split")
        }
        val offset = frame - clip.startFrame
        val first = clip.copy(endFrame = frame, sourceEndFrame = clip.sourceStartFrame + offset)
        val second = clip.copy(
            id = newClipId(),
            startFrame = frame,
            sourceStartFrame = clip.sourceStartFrame + offset
        )
        return ClipEditOutcome.Changed(
            clips.toMutableList().apply {
                set(index, first)
                add(index + 1, second)
            }
        )
    }

    /**
     * Fits clips to a shorter composition: clips ending past the new
     * duration are trimmed (source out-point shifts with the timeline
     * end) and clips starting at or after it are dropped. A zero or
     * negative duration leaves no clips.
     */
    fun clampToDuration(clips: List<ClipDocument>, durationFrames: Int): List<ClipDocument> {
        if (durationFrames <= 0) return emptyList()
        return clips.mapNotNull { clip ->
            when {
                clip.startFrame >= durationFrames -> null
                clip.endFrame <= durationFrames -> clip
                else -> clip.copy(
                    endFrame = durationFrames,
                    sourceEndFrame = clip.sourceEndFrame + (durationFrames - clip.endFrame)
                )
            }
        }
    }
}
