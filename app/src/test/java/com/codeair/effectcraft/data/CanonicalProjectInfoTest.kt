package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalProjectInfoTest {
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNativeMetadataEnvelope() {
        CanonicalProjectInfo.parse("""{"valid":false,"error":"invalid project"}""")
    }
}
