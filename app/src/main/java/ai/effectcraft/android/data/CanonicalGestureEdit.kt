package ai.effectcraft.android.data

/**
 * Pure mapping from preview gestures to canonical layer edits. The preview
 * is a proportional view of the composition, so a drag in preview pixels
 * scales to composition pixels by the preview-to-composition width ratio.
 * Locked layers never move or scale: the native layer rejects those edits,
 * so the gesture map skips them instead of producing a failing batch.
 */
object CanonicalGestureEdit {
    private const val MAX_POSITION = 1_000_000.0
    private const val MAX_SCALE = 100_000.0

    /** Drag distance in composition pixels, or zero when the preview size cannot map the drag. */
    fun dragPositionDelta(deltaXPx: Float, deltaYPx: Float, previewWidthPx: Float, compositionWidthPx: Float): Pair<Double, Double> {
        if (!deltaXPx.isFinite() || !deltaYPx.isFinite()) return 0.0 to 0.0
        if (!previewWidthPx.isFinite() || previewWidthPx <= 0f) return 0.0 to 0.0
        if (!compositionWidthPx.isFinite() || compositionWidthPx <= 0f) return 0.0 to 0.0
        val scale = compositionWidthPx / previewWidthPx
        val dx = (deltaXPx * scale).toDouble().coerceIn(-MAX_POSITION, MAX_POSITION)
        val dy = (deltaYPx * scale).toDouble().coerceIn(-MAX_POSITION, MAX_POSITION)
        return dx to dy
    }

    /** New positions for the selected unlocked layers after a drag, keyed by layer id. */
    fun draggedPositions(
        layers: List<CanonicalLayerInfo>,
        selectedIds: Set<Long>,
        delta: Pair<Double, Double>
    ): Map<Long, Pair<Double, Double>> {
        if (delta.first == 0.0 && delta.second == 0.0) return emptyMap()
        return layers
            .filter { it.id in selectedIds && !it.locked }
            .filter { it.positionX.isFinite() && it.positionY.isFinite() }
            .associate { layer ->
                layer.id to (
                    (layer.positionX + delta.first).coerceIn(-MAX_POSITION, MAX_POSITION) to
                        (layer.positionY + delta.second).coerceIn(-MAX_POSITION, MAX_POSITION)
                )
            }
    }

    /** One layer scale after a pinch, clamped to the native scale range. Invalid zoom keeps the current scale. */
    fun pinchedScale(currentScalePercent: Double, zoomFactor: Double): Double {
        if (!currentScalePercent.isFinite() || !zoomFactor.isFinite() || zoomFactor <= 0.0) return currentScalePercent
        return (currentScalePercent * zoomFactor).coerceIn(0.0, MAX_SCALE)
    }

    /** New X/Y scales for the selected unlocked layers after a pinch, keyed by layer id. */
    fun pinchedScales(
        layers: List<CanonicalLayerInfo>,
        selectedIds: Set<Long>,
        zoomFactor: Double
    ): Map<Long, Pair<Double, Double>> {
        if (!zoomFactor.isFinite() || zoomFactor <= 0.0 || zoomFactor == 1.0) return emptyMap()
        return layers
            .filter { it.id in selectedIds && !it.locked }
            .associate { layer ->
                layer.id to (pinchedScale(layer.scaleX, zoomFactor) to pinchedScale(layer.scaleY, zoomFactor))
            }
    }
}
