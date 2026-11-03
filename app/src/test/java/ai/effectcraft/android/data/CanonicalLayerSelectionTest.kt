package ai.effectcraft.android.data

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
    fun nextAfterDeleteMovesToNeighborAtSameStackIndex() {
        val layers = listOf(layer(2, "A"), layer(3, "B"), layer(4, "C"))

        assertEquals(4L, CanonicalLayerSelection.nextAfterDelete(layers, 3))
        assertEquals(3L, CanonicalLayerSelection.nextAfterDelete(layers, 4))
        assertEquals(3L, CanonicalLayerSelection.nextAfterDelete(layers, 2))
        assertNull(CanonicalLayerSelection.nextAfterDelete(listOf(layer(2, "A")), 2))
    }
}
