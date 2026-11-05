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
    fun parsesBoolAndPopupParamsWithHoldKeysAndOptions() {
        val json = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[
            {"id":2,"name":"Shape","source":"Shape","visible":true,"opacityPercent":100.0,"position":[320.0,180.0],"scale":[100.0,100.0],"rotationDegrees":0.0,
             "shapeItems":[{"name":"Rectangle Path 1","kind":"rect","scalars":[],"vecs":[],"colors":[],
                "bools":[{"uid":301,"name":"Per-Corner Roundness","value":false,"keys":[{"frame":0,"value":false,"inInterp":"Hold","outInterp":"Hold"},{"frame":15,"value":true,"inInterp":"Hold","outInterp":"Hold"}],"keyframes":[0,15]}],
                "popups":[{"uid":302,"name":"Fill Rule","value":0,"options":["Non-Zero Winding","Even-Odd"],"keys":[{"frame":0,"value":0,"inInterp":"Hold","outInterp":"Hold"},{"frame":15,"value":1,"inInterp":"Hold","outInterp":"Hold"}],"keyframes":[0,15]}]}],
             "effects":[{"name":"Gaussian Blur","kind":"gaussianBlur","scalars":[],"vecs":[],"colors":[],
                "bools":[{"uid":401,"name":"Repeat Edge Pixels","value":true,"keys":[],"keyframes":[]}],
                "popups":[{"uid":402,"name":"Blur Dimensions","value":2,"options":["Horizontal","Vertical","Both"],"keys":[],"keyframes":[]}]}]}
        ]}]}"""
        val layer = CanonicalProjectInfo.parse(json).compositions.single().layers.single()
        val rect = layer.shapeItems.single()
        assertEquals(listOf(CanonicalBoolParam(301, "Per-Corner Roundness", false, listOf(
            CanonicalCurveKey(frame = 0, value = 0.0, inInterp = "Hold", outInterp = "Hold"),
            CanonicalCurveKey(frame = 15, value = 1.0, inInterp = "Hold", outInterp = "Hold")
        ), listOf(0, 15))), rect.bools)
        assertEquals(listOf(CanonicalPopupParam(302, "Fill Rule", 0, listOf("Non-Zero Winding", "Even-Odd"), listOf(
            CanonicalCurveKey(frame = 0, value = 0.0, inInterp = "Hold", outInterp = "Hold"),
            CanonicalCurveKey(frame = 15, value = 1.0, inInterp = "Hold", outInterp = "Hold")
        ), listOf(0, 15))), rect.popups)
        val blur = layer.effects.single()
        assertEquals(listOf(CanonicalBoolParam(401, "Repeat Edge Pixels", true)), blur.bools)
        assertEquals(listOf(CanonicalPopupParam(402, "Blur Dimensions", 2, listOf("Horizontal", "Vertical", "Both"))), blur.popups)
    }

    @Test
    fun holdValueAtFollowsLastKeyAtOrBeforePlayhead() {
        val keys = listOf(
            CanonicalCurveKey(frame = 0, value = 0.0),
            CanonicalCurveKey(frame = 15, value = 1.0),
            CanonicalCurveKey(frame = 30, value = 0.0)
        )
        assertEquals(0.0, holdValueAt(keys, 7, 0.0), 0.001)
        assertEquals(0.0, holdValueAt(keys, 0, 1.0), 0.001)
        assertEquals(1.0, holdValueAt(keys, 15, 0.0), 0.001)
        assertEquals(1.0, holdValueAt(keys, 29, 0.0), 0.001)
        assertEquals(0.0, holdValueAt(keys, 30, 1.0), 0.001)
        assertEquals(2.0, holdValueAt(emptyList(), 10, 2.0), 0.001)
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
