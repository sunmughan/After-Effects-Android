package com.codeair.effectcraft.data

import java.nio.ByteBuffer

/**
 * Clip-aware render planning: which frames of an export range are
 * covered by clips, and what a gap frame renders as.
 *
 * Coverage rule: a frame is covered when at least one VISIBLE layer
 * covers it (a layer with no clips covers the whole composition, so
 * pre-clip documents render exactly as before). A frame no visible
 * layer covers is a GAP.
 *
 * Gap behavior (the contract, implemented by [clipAwareRenderRgba]):
 * gaps render as the background/empty frame — a transparent-black RGBA
 * envelope — never a stale frame from another position, never a crash,
 * and the underlying renderer is never invoked for a gap frame. The
 * export still encodes every frame in its range, so frame count and
 * duration are unchanged; only gap pixels are background.
 *
 * Scope note: the draft timeline's clips describe the draft project.
 * They apply to an export only when the exported composition has the
 * same duration ([appliesTo]), i.e. the two timelines can be read as
 * one. Otherwise the export path is untouched.
 */
object ClipRenderCoverage {
    /** The 8-byte big-endian header the native RGBA envelope carries. */
    const val MAX_BACKGROUND_SIDE = 2048

    /** True when the draft project's clips may describe [compositionFrameCount]. */
    fun appliesTo(project: ProjectDocument, compositionFrameCount: Int): Boolean =
        project.durationFrames == compositionFrameCount

    /** True when any visible layer of [project] covers [frame]. */
    fun isFrameCovered(project: ProjectDocument, frame: Int): Boolean =
        project.layers.any { layer ->
            layer.visible && ClipTimeline.layerCoverage(layer, project.durationFrames)
                .any { frame in it }
        }

    /** Merged, sorted coverage ranges inside [startFrame, endFrame] (inclusive). */
    fun coveredRanges(project: ProjectDocument, startFrame: Int, endFrame: Int): List<IntRange> {
        val ranges = project.layers.filter { it.visible }.flatMap { layer ->
            ClipTimeline.layerCoverage(layer, project.durationFrames).mapNotNull { range ->
                val start = maxOf(range.first, startFrame)
                val end = minOf(range.last, endFrame)
                if (start <= end) start..end else null
            }
        }.sortedBy { it.first }
        return ranges.fold(mutableListOf<IntRange>()) { merged, range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
            merged
        }
    }

    /** Every covered frame inside [startFrame, endFrame] (inclusive), in order. */
    fun coveredFrames(project: ProjectDocument, startFrame: Int, endFrame: Int): List<Int> =
        coveredRanges(project, startFrame, endFrame).flatMap { it.toList() }

    /** Every gap frame inside [startFrame, endFrame] (inclusive), in order. */
    fun gapFrames(project: ProjectDocument, startFrame: Int, endFrame: Int): List<Int> {
        val covered = coveredRanges(project, startFrame, endFrame).flatMap { it.toList() }.toSet()
        return (startFrame..endFrame).filter { it !in covered }
    }

    /**
     * A transparent-black RGBA frame envelope for a gap frame: the 8-byte
     * big-endian (width, height) header the native renderer emits,
     * followed by width × height × 4 zero bytes. Dimensions are the
     * requested ones scaled down (aspect preserved) so neither side
     * exceeds the envelope limit the frame decoder accepts; the pixels
     * are uniform, so scaling to the export size stays solid background.
     */
    fun backgroundFrame(width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "Background frame dimensions must be positive" }
        val scale = minOf(1.0, MAX_BACKGROUND_SIDE.toDouble() / maxOf(width, height))
        val frameWidth = evenSize((width * scale).toInt())
        val frameHeight = evenSize((height * scale).toInt())
        val bytes = ByteArray(8 + frameWidth * frameHeight * 4)
        ByteBuffer.wrap(bytes).putInt(frameWidth).putInt(frameHeight)
        return bytes
    }

    private fun evenSize(value: Int): Int = maxOf(2, value - value % 2)

    /**
     * Wraps a per-frame RGBA renderer so gap frames return the background
     * frame without ever invoking [renderRgba]. Covered frames pass
     * through untouched. The returned background bytes are a fresh copy
     * per call, so no caller can mutate a shared frame.
     */
    fun clipAwareRenderRgba(
        project: ProjectDocument,
        width: Int,
        height: Int,
        renderRgba: (frame: Int) -> ByteArray
    ): (Int) -> ByteArray {
        val background = backgroundFrame(width, height)
        return { frame ->
            if (isFrameCovered(project, frame)) renderRgba(frame) else background.copyOf()
        }
    }
}
