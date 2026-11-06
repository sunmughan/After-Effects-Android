package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSwatchTest {
    @Test
    fun rgbaToHexFormatsAndClamps() {
        assertEquals("#FF0000", rgbaToHex(listOf(1.0, 0.0, 0.0, 1.0)))
        assertEquals("#00FF00", rgbaToHex(listOf(0.0, 1.0, 0.0, 0.5)))
        assertEquals("#000000", rgbaToHex(listOf(-0.5, 0.0, 0.0)))
        assertEquals("#FFFFFF", rgbaToHex(listOf(2.0, 1.0, 1.0, 1.0)))
        assertEquals("#000000", rgbaToHex(emptyList()))
    }

    @Test
    fun colorKeysOfAggregatesDedupesAndSorts() {
        val red0 = CanonicalCurveKey(frame = 0, value = 1.0, components = listOf(1.0, 0.0, 0.0, 1.0))
        val green15 = CanonicalCurveKey(frame = 15, value = 0.0, components = listOf(0.0, 1.0, 0.0, 1.0))
        val red0Dup = CanonicalCurveKey(frame = 0, value = 1.0, components = listOf(1.0, 0.0, 0.0, 1.0))
        val blue0 = CanonicalCurveKey(frame = 0, value = 0.0, components = listOf(0.0, 0.0, 1.0, 1.0))
        val layer = CanonicalLayerInfo(
            id = 2, name = "Shape", source = "Shape", visible = true,
            opacityPercent = 100.0, positionX = 0.0, positionY = 0.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0,
            shapeItems = listOf(
                CanonicalParamGroup(
                    name = "Fill", kind = "fill",
                    colors = listOf(CanonicalColorParam(uid = 1, name = "Fill Color", components = listOf(1.0, 0.0, 0.0, 1.0), keys = listOf(green15, red0)))
                )
            ),
            effects = listOf(
                CanonicalParamGroup(
                    name = "Tint", kind = "effect",
                    colors = listOf(CanonicalColorParam(uid = 2, name = "Tint", components = listOf(0.0, 0.0, 1.0, 1.0), keys = listOf(red0Dup, blue0)))
                )
            )
        )
        val keys = colorKeysOf(layer)
        assertEquals(listOf(0, 0, 15), keys.map { it.frame })
        assertEquals(listOf("#FF0000", "#0000FF", "#00FF00"), keys.map { rgbaToHex(it.components) })
    }
}
