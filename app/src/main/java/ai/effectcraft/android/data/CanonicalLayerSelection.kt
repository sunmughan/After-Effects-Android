package ai.effectcraft.android.data

/**
 * Single-selection parity for the canonical editor sheet: the layer the list
 * highlights, the layer whose fields the inspector shows, and the layer an
 * edit targets must always be the same layer, and that layer must survive a
 * re-parse of the canonical project after each edit.
 *
 * This is deliberately single-selection only. Multi-selection and selection
 * persistence across sessions belong to a separate criterion and are not
 * handled here.
 */
object CanonicalLayerSelection {
    /** The selected layer when it still exists, otherwise the first layer, otherwise null. */
    fun resolve(layers: List<CanonicalLayerInfo>, selectedId: Long?): CanonicalLayerInfo? {
        if (layers.isEmpty()) return null
        return layers.firstOrNull { it.id == selectedId } ?: layers.first()
    }

    /**
     * The layer id selection must move to after [deletedId] is removed: the
     * layer that took its stack position, or the previous final layer when
     * the deleted layer was last, or null when no layer remains.
     */
    fun nextAfterDelete(layers: List<CanonicalLayerInfo>, deletedId: Long): Long? {
        val removedIndex = layers.indexOfFirst { it.id == deletedId }
        if (removedIndex < 0) return layers.firstOrNull()?.id
        val remaining = layers.filterNot { it.id == deletedId }
        if (remaining.isEmpty()) return null
        return (remaining.getOrNull(removedIndex) ?: remaining.last()).id
    }
}
