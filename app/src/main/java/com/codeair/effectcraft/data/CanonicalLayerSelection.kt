package com.codeair.effectcraft.data

/**
 * Selection parity for the canonical editor sheet: the layers the list
 * highlights, the layer whose fields the inspector shows, and the layers an
 * edit targets must always agree, and that selection must survive a re-parse
 * of the canonical project after each edit, undo, and redo.
 *
 * The original single-selection helpers below are kept unchanged. The
 * multi-selection helpers extend them: [resolveAll] is the multi-selection
 * form of [resolve], and the primary inspector layer is simply the first
 * entry of [resolveAll].
 */
object CanonicalLayerSelection {
    /**
     * Selects a newly appended canonical layer after an add action so its
     * inspector is immediately editable. If the project did not gain a
     * layer, preserve the surviving prior selection instead.
     */
    fun selectionAfterAdd(
        selectedIdsBefore: Set<Long>,
        layerIdsBefore: Set<Long>,
        layersAfter: List<CanonicalLayerInfo>
    ): Set<Long> {
        val added = layersAfter.lastOrNull { it.id !in layerIdsBefore }
        return added?.let { setOf(it.id) } ?: pruneSelection(selectedIdsBefore, layersAfter)
    }

    /** The selected layer when it still exists, otherwise the first layer, otherwise null. */
    fun resolve(layers: List<CanonicalLayerInfo>, selectedId: Long?): CanonicalLayerInfo? {
        if (layers.isEmpty()) return null
        return layers.firstOrNull { it.id == selectedId } ?: layers.first()
    }

    /**
     * The selected layers in composition order. Unknown or stale ids are
     * dropped; when none of the ids survive, the first layer is selected,
     * matching [resolve], so the inspector never shows a removed layer.
     */
    fun resolveAll(layers: List<CanonicalLayerInfo>, selectedIds: Set<Long>): List<CanonicalLayerInfo> {
        if (layers.isEmpty()) return emptyList()
        val selected = layers.filter { it.id in selectedIds }
        return selected.ifEmpty { listOf(layers.first()) }
    }

    /** The selected ids that still exist, in selection order; stale ids are dropped. */
    fun pruneSelection(selectedIds: Set<Long>, layers: List<CanonicalLayerInfo>): Set<Long> {
        val existing = layers.map { it.id }.toSet()
        return selectedIds.filter { it in existing }.toSet()
    }

    /**
     * Tap-to-toggle for the layer list: a tap on an unselected layer adds
     * it, a tap on a selected layer removes it, and the sole selected layer
     * is kept so the inspector always has a primary layer. Unknown ids
     * leave the selection unchanged.
     */
    fun toggleSelection(selectedIds: Set<Long>, layerId: Long, layers: List<CanonicalLayerInfo>): Set<Long> {
        if (layers.none { it.id == layerId }) return selectedIds
        if (layerId !in selectedIds) return selectedIds + layerId
        if (selectedIds.size <= 1) return selectedIds
        return selectedIds - layerId
    }

    /**
     * The selection after [deletedIds] are removed from [layersBefore]:
     * the selected layers that remain, or, when the whole selection was
     * deleted, the layer that took the lowest removed stack position, or
     * the previous final layer when the deleted layers were last, or empty
     * when no layer remains.
     */
    fun selectionAfterDelete(
        selectedIds: Set<Long>,
        layersBefore: List<CanonicalLayerInfo>,
        deletedIds: Set<Long>
    ): Set<Long> {
        val remaining = layersBefore.filterNot { it.id in deletedIds }
        val surviving = selectedIds.filter { id -> remaining.any { it.id == id } }.toSet()
        if (surviving.isNotEmpty()) return surviving
        if (remaining.isEmpty()) return emptySet()
        val lowestRemovedIndex = layersBefore.indexOfFirst { it.id in deletedIds }.coerceAtLeast(0)
        return setOf((remaining.getOrNull(lowestRemovedIndex) ?: remaining.last()).id)
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
