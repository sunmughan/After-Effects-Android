package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalProjectParamsTest {
    private fun layerJson(): String {
        return """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[
            {"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"position":[320.0,180.0],"scale":[100.0,100.0],"rotationDegrees":15.0,"anchor":[120.5,80.25],"orientation":[10.0,20.0,30.0],"rotationXDegrees":45.0,"rotationYDegrees":-30.0,"locked":true,"threeD":false,
             "text":{"text":"Hello mobile","font":"Inter","size":48.0,"tracking":12.5},
             "shapeItems":[{"name":"Rectangle Path 1","kind":"rect","scalars":[{"uid":101,"name":"Roundness","value":24.0}],"vecs":[{"uid":102,"name":"Size","components":[320.0,160.0]}]}],
             "effects":[{"name":"Gaussian Blur","kind":"gaussianBlur","scalars":[{"uid":201,"name":"Blurriness","value":15.0}],"vecs":[]}]},
            {"id":3,"name":"Clip","source":"Footage","visible":true,"opacityPercent":100.0}
        ]}]}"""
    }

    @Test
    fun parsesAnchorOrientationRotationsLockAndTextParams() {
        val layer = CanonicalProjectInfo.parse(layerJson()).compositions.single().layers.first()

        assertEquals(120.5, layer.anchorX, 0.001)
        assertEquals(80.25, layer.anchorY, 0.001)
        assertEquals(10.0, layer.orientationX, 0.001)
        assertEquals(20.0, layer.orientationY, 0.001)
        assertEquals(30.0, layer.orientationZ, 0.001)
        assertEquals(45.0, layer.rotationXDegrees, 0.001)
        assertEquals(-30.0, layer.rotationYDegrees, 0.001)
        assertTrue(layer.locked)
        assertFalse(layer.threeD)
        val text = layer.textParams!!
        assertEquals("Hello mobile", text.text)
        assertEquals("Inter", text.font)
        assertEquals(48.0, text.size, 0.001)
        assertEquals(12.5, text.tracking, 0.001)
    }

    @Test
    fun parsesShapeItemsAndEffectsByUid() {
        val layer = CanonicalProjectInfo.parse(layerJson()).compositions.single().layers.first()

        assertEquals(1, layer.shapeItems.size)
        val rect = layer.shapeItems.single()
        assertEquals("Rectangle Path 1", rect.name)
        assertEquals("rect", rect.kind)
        assertEquals(listOf(CanonicalScalarParam(101, "Roundness", 24.0)), rect.scalars)
        assertEquals(listOf(CanonicalVecParam(102, "Size", listOf(320.0, 160.0))), rect.vecs)
        assertEquals(1, layer.effects.size)
        val blur = layer.effects.single()
        assertEquals("Gaussian Blur", blur.name)
        assertEquals(listOf(CanonicalScalarParam(201, "Blurriness", 15.0)), blur.scalars)
        assertEquals(emptyList<CanonicalVecParam>(), blur.vecs)
    }

    @Test
    fun defaultsToUnlockedWithNoTextShapeOrEffectParams() {
        val layer = CanonicalProjectInfo.parse(layerJson()).compositions.single().layers.last()

        assertEquals(0.0, layer.anchorX, 0.001)
        assertEquals(0.0, layer.rotationXDegrees, 0.001)
        assertFalse(layer.locked)
        assertNull(layer.textParams)
        assertEquals(emptyList<CanonicalParamGroup>(), layer.shapeItems)
        assertEquals(emptyList<CanonicalParamGroup>(), layer.effects)
    }
}
