package com.codeair.effectcraft.data

import kotlin.math.roundToLong

/**
 * Pure editor operations kept outside Compose so layer-order semantics can be
 * unit tested independently of UI layout and Android lifecycle.
 */
object ProjectEditorActions {
    /** Rounded frame interval in milliseconds for the composition's configured FPS. */
    fun frameIntervalMillis(frameRate: Int): Long = (1_000.0 / frameRate.coerceIn(1, 120)).roundToLong().coerceAtLeast(1L)

    /**
     * Moves [layerId] by [offset] slots (negative = toward the beginning).
     * The order is clamped to the list bounds; unknown IDs and empty lists
     * are returned unchanged. Layer IDs remain stable across reordering.
     */
    fun moveLayer(layers: List<LayerDocument>, layerId: String, offset: Int): List<LayerDocument> {
        if (offset == 0 || layers.size < 2) return layers
        val from = layers.indexOfFirst { it.id == layerId }
        if (from < 0) return layers
        val to = (from + offset).coerceIn(0, layers.lastIndex)
        if (from == to) return layers

        return layers.toMutableList().apply {
            add(to, removeAt(from))
        }
    }

    /**
     * Renames a layer without changing its stable ID. Blank names and unknown IDs
     * are ignored so a UI draft can never corrupt the project model.
     */
    fun renameLayer(layers: List<LayerDocument>, layerId: String, name: String): List<LayerDocument> {
        val normalized = name.trim()
        if (normalized.isEmpty() || layers.none { it.id == layerId }) return layers
        return layers.map { if (it.id == layerId) it.copy(name = normalized) else it }
    }
    /** Removes a layer only when at least one layer remains in the composition. */
    fun deleteLayer(layers: List<LayerDocument>, layerId: String): List<LayerDocument> {
        if (layers.size <= 1 || layers.none { it.id == layerId }) return layers
        return layers.filterNot { it.id == layerId }
    }

    /**
     * Deletes the selected layer and chooses the next layer at the removed
     * stack index (or the previous final layer). If deletion is rejected
     * because it is the only layer, selection remains unchanged.
     */
    fun deleteLayerAndSelection(layers: List<LayerDocument>, selectedLayerId: String): Pair<List<LayerDocument>, String?> {
        val remaining = deleteLayer(layers, selectedLayerId)
        if (remaining.any { it.id == selectedLayerId }) return remaining to selectedLayerId
        val removedIndex = layers.indexOfFirst { it.id == selectedLayerId }
        val next = remaining.getOrNull(removedIndex.coerceAtLeast(0)) ?: remaining.lastOrNull()
        return remaining to next?.id
    }

    /** Updates visibility without changing identity or other layer properties. */
    fun setLayerVisibility(layers: List<LayerDocument>, layerId: String, visible: Boolean): List<LayerDocument> {
        if (layers.none { it.id == layerId }) return layers
        return layers.map { if (it.id == layerId) it.copy(visible = visible) else it }
    }

    /** Opacity is stored normalized (0..1); NaN/infinity are rejected. */
    fun setLayerOpacity(layers: List<LayerDocument>, layerId: String, opacity: Float): List<LayerDocument> {
        if (!opacity.isFinite() || layers.none { it.id == layerId }) return layers
        val bounded = opacity.coerceIn(0f, 1f)
        return layers.map { if (it.id == layerId) it.copy(opacity = bounded) else it }
    }

    /**
     * Trims [layerId] to the inclusive frame range [inFrame]..[outFrame] in a
     * composition of [durationFrames] frames. Bounds are clamped, never
     * rejected: an in-frame past the end lands on the last frame, and an
     * out-frame before the in-frame collapses to a one-frame clip at the
     * in-frame. A null [outFrame] leaves the clip running to the end of the
     * composition. Unknown IDs, non-positive durations and no-op trims return
     * the list unchanged, so the caller can tell a real edit from a no-op.
     */
    fun trimLayer(
        layers: List<LayerDocument>,
        layerId: String,
        inFrame: Int,
        outFrame: Int?,
        durationFrames: Int
    ): List<LayerDocument> {
        if (durationFrames <= 0 || layers.none { it.id == layerId }) return layers
        val clampedIn = inFrame.coerceIn(0, durationFrames - 1)
        val clampedOut = outFrame?.coerceIn(clampedIn, durationFrames - 1)
        return layers.map { layer ->
            if (layer.id == layerId && (layer.inFrame != clampedIn || layer.outFrame != clampedOut)) {
                layer.copy(inFrame = clampedIn, outFrame = clampedOut)
            } else {
                layer
            }
        }
    }

    /** Removes the trim on [layerId], restoring the full composition range. Unknown IDs return the list unchanged. */
    fun clearTrim(layers: List<LayerDocument>, layerId: String): List<LayerDocument> {
        if (layers.none { it.id == layerId }) return layers
        return layers.map { layer ->
            if (layer.id == layerId && (layer.inFrame != 0 || layer.outFrame != null)) {
                layer.copy(inFrame = 0, outFrame = null)
            } else {
                layer
            }
        }
    }

    /** Visible layers whose trim covers [frame]: the layers actually playing at the playhead. */
    fun activeLayersAt(layers: List<LayerDocument>, frame: Int, durationFrames: Int): List<LayerDocument> =
        layers.filter { it.visible && it.isActiveAt(frame, durationFrames) }

    /**
     * The export range for a requested [startFrame]..[endFrame] range: the
     * requested range intersected with the union of the visible layers'
     * trimmed spans, clamped into the composition. Null when no visible layer
     * covers any requested frame, so the caller reports "nothing to export"
     * instead of rendering blank frames.
     */
    fun trimmedExportRange(
        layers: List<LayerDocument>,
        startFrame: Int,
        endFrame: Int,
        durationFrames: Int
    ): IntRange? {
        if (durationFrames <= 0) return null
        val requestedStart = startFrame.coerceIn(0, durationFrames - 1)
        val requestedEnd = endFrame.coerceIn(0, durationFrames - 1)
        if (requestedStart > requestedEnd) return null
        var first: Int? = null
        var last: Int? = null
        layers.filter { it.visible && it.trimmedDurationFrames(durationFrames) > 0 }.forEach { layer ->
            val spanStart = maxOf(layer.effectiveInFrame(durationFrames), requestedStart)
            val spanEnd = minOf(layer.effectiveOutFrame(durationFrames), requestedEnd)
            if (spanStart <= spanEnd) {
                first = minOf(first ?: spanStart, spanStart)
                last = maxOf(last ?: spanEnd, spanEnd)
            }
        }
        val resolvedFirst = first ?: return null
        return resolvedFirst..(last ?: return null)
    }
}
