package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class CanonicalGestureEditTest {
    private fun layer(id: Long, x: Double, y: Double, locked: Boolean = false): CanonicalLayerInfo {
        return CanonicalLayerInfo(
            id = id, name = "Layer $id", source = "Text", visible = true,
            opacityPercent = 100.0, positionX = x, positionY = y,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0, locked = locked
        )
    }

    private fun assertPairEquals(expected: Pair<Double, Double>, actual: Pair<Double, Double>) {
        assertEquals(expected.first, actual.first, 0.000001)
        assertEquals(expected.second, actual.second, 0.000001)
    }

    @Test
    fun dragDeltaScalesPreviewPixelsToCompositionUnits() {
        // A 960px wide preview of a 1920px wide composition maps 1 preview
        // pixel to 2 composition pixels on both axes.
        assertPairEquals(
            100.0 to 50.0,
            CanonicalGestureEdit.dragPositionDelta(50f, 25f, 960f, 1920f)
        )
    }

    @Test
    fun dragDeltaIsZeroWithoutAUsablePreviewWidth() {
        assertPairEquals(0.0 to 0.0, CanonicalGestureEdit.dragPositionDelta(50f, 25f, 0f, 1920f))
        assertPairEquals(0.0 to 0.0, CanonicalGestureEdit.dragPositionDelta(50f, 25f, -10f, 1920f))
        assertPairEquals(0.0 to 0.0, CanonicalGestureEdit.dragPositionDelta(50f, 25f, 960f, 0f))
        assertPairEquals(0.0 to 0.0, CanonicalGestureEdit.dragPositionDelta(Float.NaN, 25f, 960f, 1920f))
    }

    @Test
    fun draggedPositionsApplyToSelectedUnlockedLayersOnly() {
        val layers = listOf(layer(2, 10.0, 20.0), layer(3, 30.0, 40.0), layer(4, 50.0, 60.0, locked = true))
        val moved = CanonicalGestureEdit.draggedPositions(layers, setOf(2, 4), 5.0 to -10.0)

        assertEquals(mapOf(2L to (15.0 to 10.0)), moved)
    }

    @Test
    fun draggedPositionsClampToNativePositionBounds() {
        val layers = listOf(layer(2, 999_999.0, 0.0))
        val moved = CanonicalGestureEdit.draggedPositions(layers, setOf(2), 100.0 to 0.0)

        assertPairEquals(1_000_000.0 to 0.0, moved.getValue(2))
    }

    @Test
    fun pinchedScaleMultipliesAndClampsToNativeBounds() {
        assertEquals(200.0, CanonicalGestureEdit.pinchedScale(100.0, 2.0), 0.000001)
        assertEquals(50.0, CanonicalGestureEdit.pinchedScale(100.0, 0.5), 0.000001)
        assertEquals(100_000.0, CanonicalGestureEdit.pinchedScale(90_000.0, 2.0), 0.000001)
    }

    @Test
    fun pinchedScaleKeepsCurrentScaleForInvalidZoom() {
        assertEquals(100.0, CanonicalGestureEdit.pinchedScale(100.0, 0.0), 0.000001)
        assertEquals(100.0, CanonicalGestureEdit.pinchedScale(100.0, -1.0), 0.000001)
        assertEquals(100.0, CanonicalGestureEdit.pinchedScale(100.0, Double.NaN), 0.000001)
    }

    @Test
    fun pinchedScalesApplyToSelectedUnlockedLayersOnly() {
        val layers = listOf(layer(2, 0.0, 0.0), layer(3, 0.0, 0.0, locked = true))
        val scaled = CanonicalGestureEdit.pinchedScales(layers, setOf(2, 3), 1.5)

        assertEquals(mapOf(2L to (150.0 to 150.0)), scaled)
    }

    @Test
    fun dragDeltaMagnitudeIsFiniteForHugeInputs() {
        val delta = CanonicalGestureEdit.dragPositionDelta(1e20f, 0f, 960f, 1920f)
        assertEquals(true, delta.first.isFinite())
        assertEquals(true, abs(delta.first) <= 1_000_000.0)
    }
}
