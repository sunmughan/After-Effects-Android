package com.codeair.effectcraft.data

import java.util.UUID

data class ProjectDocument(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationFrames: Int,
    val layers: List<LayerDocument>,
    /** Audio tracks on this composition. Empty means the legacy behaviour: no editor audio clips. */
    val audioTracks: List<AudioTrackDocument> = emptyList(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    /** Returns all editor-model invariant violations; an empty list means saveable. */
    fun validationErrors(): List<String> = buildList {
        if (name.isBlank()) add("Project name must not be blank")
        if (width !in 320..3840) add("Composition width must be between 320 and 3840")
        if (height !in 320..3840) add("Composition height must be between 320 and 3840")
        if (frameRate !in 1..120) add("Frame rate must be between 1 and 120")
        if (durationFrames !in 1..3600) add("Duration must be between 1 and 3600 frames")
        if (layers.isEmpty()) add("A composition must contain at least one layer")
        if (layers.map { it.id }.toSet().size != layers.size) add("Layer IDs must be unique")
        layers.forEachIndexed { index, layer ->
            if (layer.name.isBlank()) add("Layer ${index + 1} name must not be blank")
            if (!layer.opacity.isFinite() || layer.opacity !in 0f..1f) add("Layer ${index + 1} opacity must be finite and normalized")
            if (layer.inFrame !in 0 until durationFrames) add("Layer ${index + 1} trim in-frame must be within the composition")
            val outFrame = layer.outFrame
            if (outFrame != null && outFrame !in layer.inFrame until durationFrames) {
                add("Layer ${index + 1} trim out-frame must be at or after its in-frame and within the composition")
            }
            ClipTimeline.validateClips(layer.clips, durationFrames).forEach { error ->
                add("Layer ${index + 1}: $error")
            }
        }
        if (audioTracks.map { it.id }.toSet().size != audioTracks.size) add("Audio track IDs must be unique")
        audioTracks.forEachIndexed { index, track ->
            track.validationErrors(durationFrames).forEach { error ->
                add("Audio track ${index + 1}: $error")
            }
        }
    }

    companion object {
        fun starter(): ProjectDocument = ProjectDocument(
            id = UUID.randomUUID().toString(),
            name = "Untitled Composition",
            width = 1080,
            height = 1920,
            frameRate = 30,
            durationFrames = 150,
            layers = listOf(
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Background",
                    kind = "Footage",
                    opacity = 1f,
                    tint = 0xFF56647A,
                    clips = listOf(ClipDocument.fullRange(150))
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Title / Main",
                    kind = "Text",
                    opacity = 1f,
                    tint = 0xFF9278F6,
                    clips = listOf(ClipDocument.fullRange(150))
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Accent Shapes",
                    kind = "Shape",
                    opacity = 0.82f,
                    tint = 0xFF45C8B5,
                    clips = listOf(ClipDocument.fullRange(150))
                )
            )
        )
    }
}

/**
 * One clip on the timeline. A layer spans the whole composition unless
 * trimmed: [inFrame] is the first visible frame (inclusive) and [outFrame]
 * the last visible frame (inclusive); a null [outFrame] means the clip runs
 * untrimmed to the end of the composition. Frames are composition frames,
 * so a trim stays valid when the frame rate changes and only needs
 * re-clamping when the composition duration changes.
 */
data class LayerDocument(
    val id: String,
    val name: String,
    val kind: String,
    val opacity: Float,
    val tint: Long,
    val visible: Boolean = true,
    /** Legacy single-range trim (HEAD): first/last visible frame. Used when [clips] is empty. */
    val inFrame: Int = 0,
    val outFrame: Int? = null,
    /** Clips on this layer's timeline. Empty means the legacy behaviour: the layer follows [inFrame]/[outFrame] (see [ClipTimeline.layerCoverage]). */
    val clips: List<ClipDocument> = emptyList()
) {
    /** First visible frame, clamped into a composition of [durationFrames] frames. Clips win when present, else the legacy in-frame. */
    fun effectiveInFrame(durationFrames: Int): Int =
        if (durationFrames <= 0) 0
        else if (clips.isNotEmpty()) clips.minOf { it.startFrame }.coerceIn(0, durationFrames - 1)
        else inFrame.coerceIn(0, durationFrames - 1)

    /** Last visible frame, clamped into a composition of [durationFrames] frames. Clips win when present, else the legacy out-frame. */
    fun effectiveOutFrame(durationFrames: Int): Int {
        if (durationFrames <= 0) return 0
        if (clips.isNotEmpty()) return (clips.maxOf { it.endFrame } - 1).coerceIn(0, durationFrames - 1)
        val end = outFrame ?: durationFrames - 1
        return end.coerceIn(0, durationFrames - 1)
    }

    /** True when the clip shows at [frame] in a composition of [durationFrames] frames. Clips win when present, else the legacy range. */
    fun isActiveAt(frame: Int, durationFrames: Int): Boolean =
        if (clips.isNotEmpty()) ClipTimeline.clipAt(clips, frame) != null
        else frame >= effectiveInFrame(durationFrames) && frame <= effectiveOutFrame(durationFrames)

    /** Visible frame count in a composition of [durationFrames] frames; zero when the trim is degenerate. */
    fun trimmedDurationFrames(durationFrames: Int): Int =
        if (clips.isNotEmpty()) clips.sumOf { it.timelineLength }.coerceAtLeast(0)
        else (effectiveOutFrame(durationFrames) - effectiveInFrame(durationFrames) + 1).coerceAtLeast(0)

    /** True when the clip is trimmed away from the full composition range. */
    fun isTrimmed(durationFrames: Int): Boolean =
        effectiveInFrame(durationFrames) != 0 || effectiveOutFrame(durationFrames) != durationFrames - 1

    companion object {
        fun newShape(index: Int): LayerDocument = LayerDocument(
            id = UUID.randomUUID().toString(),
            name = "Shape $index",
            kind = "Shape",
            opacity = 1f,
            tint = 0xFF9278F6
        )
    }
}
