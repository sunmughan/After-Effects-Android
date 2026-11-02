package ai.effectcraft.android.data

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
}
