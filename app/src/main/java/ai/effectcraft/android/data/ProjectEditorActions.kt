package ai.effectcraft.android.data

/**
 * Pure editor operations kept outside Compose so layer-order semantics can be
 * unit tested independently of UI layout and Android lifecycle.
 */
object ProjectEditorActions {
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
}
