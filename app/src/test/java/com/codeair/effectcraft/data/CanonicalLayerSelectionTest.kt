package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalLayerSelectionTest {
    private fun layer(id: Long, name: String): CanonicalLayerInfo {
        return CanonicalLayerInfo(
            id = id, name = name, source = "Text", visible = true,
            opacityPercent = 100.0, positionX = 0.0, positionY = 0.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0
        )
    }

    @Test
    fun resolveKeepsExistingSelectionAcrossReparse() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(3L, CanonicalLayerSelection.resolve(layers, 3)?.id)
    }

    @Test
    fun resolveFallsBackToFirstLayerWhenSelectedLayerIsGone() {
        val layers = listOf(layer(2, "A"), layer(3, "B"))

        assertEquals(2L, CanonicalLayerSelection.resolve(layers, 99)?.id)
        assertEquals(2L, CanonicalLayerSelection.resolve(layers, null)?.id)
        assertNull(CanonicalLayerSelection.resolve(emptyList(), 2))
    }

    @Test
    fun resolveAllKeepsCompositionOrderAndFallsBackToFirstLayer() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(listOf(2L, 4L), CanonicalLayerSelection.resolveAll(layers, setOf(4, 2, 99)).map { it.id })
        assertEquals(listOf(2L), CanonicalLayerSelection.resolveAll(layers, setOf(99)).map { it.id })
        assertEquals(listOf(2L), CanonicalLayerSelection.resolveAll(layers, emptySet()).map { it.id })
        assertEquals(emptyList<CanonicalLayerInfo>(), CanonicalLayerSelection.resolveAll(emptyList(), setOf(2)))
    }

    @Test
    fun pruneSelectionDropsMissingIdsAndKeepsSelectionOrder() {
        val layers = listOf(layer(2, "A"), layer(3, "B"))

        assertEquals(setOf(3L, 2L), CanonicalLayerSelection.pruneSelection(setOf(3, 99, 2), layers))
        assertEquals(emptySet<Long>(), CanonicalLayerSelection.pruneSelection(setOf(99), layers))
    }

    @Test
    fun toggleSelectionAddsRemovesAndKeepsSoleSelection() {
        val layers = listOf(layer(2, "A"), layer(3, "B"))

        assertEquals(setOf(2L, 3L), CanonicalLayerSelection.toggleSelection(setOf(2), 3, layers))
        assertEquals(setOf(2L), CanonicalLayerSelection.toggleSelection(setOf(2, 3), 3, layers))
        assertEquals(setOf(2L), CanonicalLayerSelection.toggleSelection(setOf(2), 2, layers))
        assertEquals(setOf(2L), CanonicalLayerSelection.toggleSelection(setOf(2), 99, layers))
    }

    @Test
    fun selectionAfterDeleteKeepsRemainingSelection() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(setOf(4L), CanonicalLayerSelection.selectionAfterDelete(setOf(3, 4), layers, setOf(3)))
        assertEquals(setOf(3L), CanonicalLayerSelection.selectionAfterDelete(setOf(2), layers, setOf(2)))
    }

    @Test
    fun selectionAfterDeleteMovesToNeighborWhenWholeSelectionIsDeleted() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(setOf(4L), CanonicalLayerSelection.selectionAfterDelete(setOf(3), layers, setOf(3)))
        assertEquals(setOf(3L), CanonicalLayerSelection.selectionAfterDelete(setOf(4), layers, setOf(4)))
        assertEquals(emptySet<Long>(), CanonicalLayerSelection.selectionAfterDelete(setOf(2), listOf(layer(2, "A")), setOf(2)))
    }

    @Test
    fun nextAfterDeleteMovesToNeighborAtSameStackIndex() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(4L, CanonicalLayerSelection.nextAfterDelete(layers, 3))
        assertEquals(3L, CanonicalLayerSelection.nextAfterDelete(layers, 4))
        assertEquals(3L, CanonicalLayerSelection.nextAfterDelete(layers, 2))
        assertNull(CanonicalLayerSelection.nextAfterDelete(listOf(layer(2, "A")), 2))
    }
}
