package com.codeair.effectcraft.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalProjectInfoTest {
    @Test
    fun parsesNativeStraightRgbaEffectParameters() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layers":[{"id":2,"name":"Layer","source":"Null","effects":[{"name":"Tint","kind":"colorTint","scalars":[],"vecs":[],"colors":[{"uid":42,"name":"Color","components":[0.1,0.2,0.3,0.4],"keys":[],"keyframes":[0,15]}]}]}]}]}"""
        val color = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().effects.single().colors.single()
        assertEquals(42L, color.uid)
        assertEquals("Color", color.name)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), color.components)
        assertTrue(color.keys.isEmpty())
        assertEquals(listOf(0, 15), color.keyframes)
    }

    @Test
    fun parsesGradientFillParametersAndRoundTripsStops() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layers":[{"id":2,"name":"Shape","source":"Shape","shapeItems":[{"name":"Gradient Fill 1","kind":"gfill","scalars":[],"vecs":[],"colors":[],"gradients":[{"uid":77,"name":"Colors","colors":[[0.0,[1.0,0.0,0.0,1.0]],[1.0,[0.0,0.0,1.0,1.0]]],"opacities":[[0.0,1.0],[1.0,0.5]],"keys":[],"keyframes":[]}]}]}]}]}"""
        val gradient = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().shapeItems.single().gradients.single()
        assertEquals(77L, gradient.uid)
        assertEquals("Colors", gradient.name)
        assertEquals(listOf(0.0, 1.0), gradient.colors.map { it.position })
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), gradient.colors[0].components)
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0), gradient.colors[1].components)
        assertEquals(listOf(0.0, 1.0), gradient.opacities.map { it.position })
        assertEquals(listOf(1.0, 0.5), gradient.opacities.map { it.opacity })
        val roundTripped = JSONObject(gradient.toJson())
        assertEquals(2, roundTripped.getJSONArray("colors").length())
        assertEquals(0.5, roundTripped.getJSONArray("opacities").getJSONArray(1).getDouble(1), 0.001)
    }

    @Test
    fun parsesCanonicalMaskShapePathJsonAndKeyFrames() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layers":[{"id":2,"name":"Shape","source":"Shape","shapeItems":[{"name":"Mask 1","kind":"mask","paths":[{"uid":91,"name":"Mask Path","path":{"vertices":[[10.0,20.0],[40.0,20.0],[25.0,60.0]],"in_tangents":[[0.0,0.0],[0.0,0.0],[0.0,0.0]],"out_tangents":[[0.0,0.0],[0.0,0.0],[0.0,0.0]],"closed":false,"feather":[]},"keyframes":[0,15]}]}]}]}]}"""
        val path = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().shapeItems.single().paths.single()
        assertEquals(91L, path.uid)
        assertEquals("Mask Path", path.name)
        assertEquals(listOf(0, 15), path.keyframes)
        val json = JSONObject(path.pathJson)
        assertEquals(3, json.getJSONArray("vertices").length())
        assertEquals(25.0, json.getJSONArray("vertices").getJSONArray(2).getDouble(0), 0.001)
        assertFalse(json.getBoolean("closed"))
    }

    @Test
    fun parsesSourceTextTrackingValuesOnCompositeHoldKeys() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layers":[{"id":2,"name":"Title","source":"Text","text":{"uid":7,"text":"Base","font":"Inter","size":32.0,"tracking":0.0,"keys":[{"frame":15,"text":"Frame fifteen","font":"Noto Sans","tracking":42.5},{"frame":0,"text":"Base","font":"Inter","tracking":0.0}]}}]}]}"""
        val text = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().textParams!!
        assertEquals(listOf(0, 15), text.keys.map { it.frame })
        assertEquals("Frame fifteen", text.keys[1].text)
        assertEquals(42.5, text.keys[1].tracking, 0.001)
        assertEquals(0.0, text.keys[0].tracking, 0.001)
    }

    @Test
    fun parsesDiscreteLayerReferenceKeysIncludingNone() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layers":[{"id":2,"name":"Source","source":"Shape","effects":[{"name":"Layer Control","kind":"layerControl","layerRefs":[{"uid":31,"name":"Layer","value":null,"keys":[{"frame":15,"layerId":8},{"frame":0,"layerId":null},{"frame":30,"layerId":null}],"keyframes":[30,0,15]}]}]}]}]}"""
        val reference = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().effects.single().layerRefs.single()
        assertEquals(31L, reference.uid)
        assertEquals(listOf(0, 15, 30), reference.keyframes)
        assertEquals(listOf(CanonicalLayerRefKey(0, null), CanonicalLayerRefKey(15, 8L), CanonicalLayerRefKey(30, null)), reference.keys)
    }

    @Test
    fun parsesCompositionLayerAndLinkedMediaInventory() {
        val metadata = """{"valid":true,"schema":1,"itemCount":2,"compositionCount":1,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layerCount":1,"layers":[{"id":2,"name":"Headline","source":"Text","visible":false,"opacityPercent":35.0,"position":[123.5,456.25],"scale":[125.0,80.0],"rotationDegrees":42.5}]}],"items":[{"id":10,"name":"Main","type":"Composition","parentId":null,"media":{}},{"id":20,"name":"Footage","type":"Video","parentId":null,"media":{"path":"content://media/1","width":1920,"height":1080,"hasVideo":true,"hasAudio":true,"missing":false,"sequence":[]}}]}"""

        val project = CanonicalProjectInfo.parse(metadata)

        assertEquals(1, project.compositions.size)
        assertEquals(640, project.compositions.single().width)
        assertEquals("Headline", project.compositions.single().layers.single().name)
        assertFalse(project.compositions.single().layers.single().visible)
        assertEquals(35.0, project.compositions.single().layers.single().opacityPercent, 0.001)
        assertEquals(123.5, project.compositions.single().layers.single().positionX, 0.001)
        assertEquals(456.25, project.compositions.single().layers.single().positionY, 0.001)
        assertEquals(125.0, project.compositions.single().layers.single().scaleX, 0.001)
        assertEquals(80.0, project.compositions.single().layers.single().scaleY, 0.001)
        assertEquals(42.5, project.compositions.single().layers.single().rotationDegrees, 0.001)
        assertEquals(2, project.items.size)
        val footage = project.items.last()
        assertEquals("Video", footage.type)
        assertEquals("content://media/1", footage.path)
        assertEquals(1920, footage.width)
        assertTrue(footage.hasVideo)
        assertTrue(footage.hasAudio)
        assertFalse(footage.missing)
    }

    @Test
    fun parsesOpacityKeysSortedWithSameFrameReplacement() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"position":[0.0,0.0],"scale":[100.0,100.0],"rotationDegrees":0.0,"opacityKeys":[{"frame":30,"value":0.0},{"frame":0,"value":100.0},{"frame":30,"value":25.0}]}]}]}"""

        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()

        assertEquals(
            listOf(PropertyKeyframe(0, 100f), PropertyKeyframe(30, 25f)),
            layer.opacityKeys
        )
    }

    @Test
    fun parsesNativeTransformKeyframesSortedAndDeduplicated() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"transformKeys":{"position":[15,0,15],"scale":[0],"rotation":[30,10,30]}}]}]}"""

        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()

        assertEquals(listOf(0, 15), layer.transformKeyframes["position"])
        assertEquals(listOf(0), layer.transformKeyframes["scale"])
        assertEquals(listOf(10, 30), layer.transformKeyframes["rotation"])
        assertFalse(layer.transformKeyframes.containsKey("anchor"))
    }

    @Test
    fun parsesNativeThreeDimensionalTransformKeysAndCurves() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Layer","source":"Text","visible":true,"opacityPercent":100.0,"transformKeys":{"anchor":[0],"orientation":[15,30],"rotationX":[0],"rotationY":[15]},"transformCurves":{"anchor":[{"frame":0,"value":[12.0,34.0,0.0],"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]}],"orientation":[{"frame":15,"value":[10.0,20.0,30.0],"inInterp":"Bezier","outInterp":"Linear","inEase":[{"speed":2.0,"influence":0.3}],"outEase":[]}],"rotationX":[{"frame":0,"value":45.0,"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]}],"rotationY":[{"frame":15,"value":-30.0,"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]}]}}]}]}"""
        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()
        assertEquals(listOf(0), layer.transformKeyframes["anchor"])
        assertEquals(listOf(15, 30), layer.transformKeyframes["orientation"])
        assertEquals(listOf(0), layer.transformKeyframes["rotationX"])
        assertEquals(listOf(15), layer.transformKeyframes["rotationY"])
        assertEquals(12.0, layer.transformCurves["anchor"]!!.single().value, 0.001)
        assertEquals(10.0, layer.transformCurves["orientation"]!!.single().value, 0.001)
        assertEquals(45.0, layer.transformCurves["rotationX"]!!.single().value, 0.001)
        assertEquals(-30.0, layer.transformCurves["rotationY"]!!.single().value, 0.001)
    }

    @Test
    fun opacityKeysDefaultToEmptyWhenMetadataCarriesNone() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0}]}]}"""

        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()

        assertEquals(emptyList<PropertyKeyframe>(), layer.opacityKeys)
    }

    @Test
    fun parsesCurveKeysWithPerSideEaseAndParamKeys() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"transformCurves":{"position":[{"frame":30,"value":[100.0,200.0,0.0],"inInterp":"Bezier","outInterp":"Linear","inEase":[{"speed":20.0,"influence":0.25}],"outEase":[]},{"frame":0,"value":[10.0,20.0,0.0],"inInterp":"Linear","outInterp":"Bezier","inEase":[],"outEase":[{"speed":0.0,"influence":0.5}]}]},"opacityCurves":[{"frame":0,"value":100.0,"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]}],"shapeItems":[{"name":"Rectangle","kind":"rect","scalars":[{"uid":77,"name":"Roundness","value":24.0,"keys":[{"frame":15,"value":24.0,"inInterp":"Bezier","outInterp":"Bezier","inEase":[{"speed":8.0,"influence":0.6}],"outEase":[]}]}],"vecs":[]}]}]}]}"""

        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()

        val position = layer.transformCurves["position"]!!
        assertEquals(listOf(0, 30), position.map { it.frame })
        assertEquals(10.0, position[0].value, 0.001)
        assertEquals("Bezier", position[1].inInterp)
        assertEquals(20.0, position[1].inSpeed, 0.001)
        assertEquals(0.25, position[1].inInfluence, 0.001)
        assertEquals(0.5, position[0].outInfluence, 0.001)
        assertEquals(listOf(0), layer.opacityCurves.map { it.frame })
        val roundness = layer.shapeItems.single().scalars.single()
        assertEquals(77L, roundness.uid)
        assertEquals(listOf(15), roundness.keys.map { it.frame })
        assertEquals(8.0, roundness.keys.single().inSpeed, 0.001)
        assertEquals(0.6, roundness.keys.single().inInfluence, 0.001)
    }

    @Test
    fun parsesRovingAndSpatialFieldsWithDefaults() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"transformCurves":{"position":[{"frame":0,"value":[0.0,0.0,0.0],"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]},{"frame":15,"value":[100.0,0.0,0.0],"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[],"roving":true,"spatialIn":[0.0,-40.0,0.0],"spatialOut":[0.0,-40.0,0.0],"spatialAuto":false},{"frame":30,"value":[200.0,0.0,0.0],"inInterp":"Linear","outInterp":"Linear","inEase":[],"outEase":[]}]}}]}]}"""

        val position = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().transformCurves["position"]!!

        // Missing fields default to the upstream key defaults: not roving,
        // auto-Bezier tangents, zero stored tangents.
        assertEquals(false, position[0].roving)
        assertEquals(true, position[0].spatialAuto)
        assertEquals(listOf(0.0, 0.0, 0.0), position[0].spatialIn)
        assertEquals(listOf(0.0, 0.0, 0.0), position[0].spatialOut)
        assertEquals(true, position[1].roving)
        assertEquals(false, position[1].spatialAuto)
        assertEquals(listOf(0.0, -40.0, 0.0), position[1].spatialIn)
        assertEquals(listOf(0.0, -40.0, 0.0), position[1].spatialOut)
        // The full vector value rides along for the motion-path overlay.
        assertEquals(listOf(100.0, 0.0, 0.0), position[1].components)
        assertEquals(listOf(200.0, 0.0, 0.0), position[2].components)
    }

    @Test
    fun spatialTrackExposesSingleSpeedFromFirstEaseEntry() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"transformCurves":{"position":[{"frame":0,"value":[0.0,0.0,0.0],"inInterp":"Bezier","outInterp":"Bezier","inEase":[{"speed":11.0,"influence":0.2},{"speed":22.0,"influence":0.3},{"speed":33.0,"influence":0.4}],"outEase":[{"speed":44.0,"influence":0.5},{"speed":55.0,"influence":0.6},{"speed":66.0,"influence":0.7}]}]}}]}]}"""

        val key = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single().transformCurves["position"]!!.single()

        // A spatial track has one speed along the path, not one per
        // dimension: only the first ease entry is exposed.
        assertEquals(11.0, key.inSpeed, 0.001)
        assertEquals(0.2, key.inInfluence, 0.001)
        assertEquals(44.0, key.outSpeed, 0.001)
        assertEquals(0.5, key.outInfluence, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNativeMetadataEnvelope() {
        CanonicalProjectInfo.parse("""{"valid":false,"error":"invalid project"}""")
    }
}
