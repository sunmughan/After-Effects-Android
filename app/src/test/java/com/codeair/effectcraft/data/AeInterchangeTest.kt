package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3: the After Effects interchange parser. The interchange is the
 * open JSON format an After Effects project is exported to (see
 * docs/AE_IMPORT.md and docs/ae-export/ExportToEffectCraft.jsx). It is
 * an interim route only: direct .aep import is implemented by the
 * ported official EffectCraft .aep importer (see ImportDocumentResolver).
 */
class AeInterchangeTest {

    private fun sampleJson(
        layers: String = """
            [{
              "name": "Title", "type": "text", "text": "Hello",
              "font": "Arial", "fontSize": 72, "color": [1, 1, 1, 1],
              "position": [960, 540], "opacity": 100,
              "startTime": 0, "inPoint": 0, "outPoint": 10,
              "keyframes": {
                "position": [
                  {"time": 0, "value": [960, 540]},
                  {"time": 2, "value": [960, 300]}
                ],
                "opacity": [{"time": 0, "value": 100}, {"time": 1, "value": 0}]
              }
            },
            {"name": "BG", "type": "solid", "color": [0.1, 0.2, 0.8],
             "width": 1920, "height": 1080, "position": [960, 540]},
            {"name": "Clip.mp4", "type": "footage", "file": "media/Clip.mp4",
             "width": 1280, "height": 720, "duration": 8.0, "kind": "video",
             "position": [640, 360]}
        ]
        """.trimIndent()
    ): String = """
        {
          "format": "effectcraft-ae-interchange",
          "version": 1,
          "projectName": "My Project",
          "comps": [{
            "name": "Main", "width": 1920, "height": 1080,
            "frameRate": 30, "duration": 10.0,
            "background": [0, 0, 0],
            "layers": $layers
          }]
        }
    """.trimIndent()

    @Test
    fun parsesTextSolidAndFootageLayers() {
        val project = AeInterchange.parse(sampleJson())

        assertEquals("My Project", project.projectName)
        assertEquals(1, project.comps.size)
        val comp = project.comps[0]
        assertEquals("Main", comp.name)
        assertEquals(1920, comp.width)
        assertEquals(1080, comp.height)
        assertEquals(30.0, comp.frameRate, 0.0)
        assertEquals(10.0, comp.durationSeconds, 0.0)
        assertEquals(3, comp.layers.size)

        val text = comp.layers[0]
        assertEquals(AeLayerType.TEXT, text.type)
        assertEquals("Hello", text.text)
        assertEquals("Arial", text.font)
        assertEquals(72.0, text.fontSize, 0.0)
        assertEquals(960.0, text.position.first, 0.0)
        assertEquals(540.0, text.position.second, 0.0)

        val solid = comp.layers[1]
        assertEquals(AeLayerType.SOLID, solid.type)
        assertEquals(0.1, solid.color[0], 0.0)
        assertEquals(1920, solid.width)
        assertEquals(1080, solid.height)

        val footage = comp.layers[2]
        assertEquals(AeLayerType.FOOTAGE, footage.type)
        assertEquals("media/Clip.mp4", footage.file)
        assertEquals("video", footage.kind)
        assertEquals(8.0, footage.durationSeconds, 0.0)
    }

    @Test
    fun parsesPositionAndOpacityKeyframes() {
        val project = AeInterchange.parse(sampleJson())
        val text = project.comps[0].layers[0]

        val positionKeys = text.keyframes["position"]!!
        assertEquals(2, positionKeys.size)
        assertEquals(0.0, positionKeys[0].timeSeconds, 0.0)
        assertEquals(2.0, positionKeys[1].timeSeconds, 0.0)
        val moved = positionKeys[1].value as AeValue.Vec2
        assertEquals(960.0, moved.x, 0.0)
        assertEquals(300.0, moved.y, 0.0)

        val opacityKeys = text.keyframes["opacity"]!!
        assertEquals(2, opacityKeys.size)
        assertEquals(100.0, (opacityKeys[0].value as AeValue.Scalar).value, 0.0)
        assertEquals(0.0, (opacityKeys[1].value as AeValue.Scalar).value, 0.0)
    }

    @Test
    fun defaultsPositionToCompCentreAndOutPointToCompDuration() {
        val json = """
            {
              "format": "effectcraft-ae-interchange", "version": 1,
              "comps": [{
                "name": "C", "width": 640, "height": 360,
                "frameRate": 25, "duration": 4.0,
                "layers": [{"name": "T", "type": "text", "text": "Hi"}]
              }]
            }
        """.trimIndent()
        val layer = AeInterchange.parse(json).comps[0].layers[0]

        assertEquals(320.0, layer.position.first, 0.0)
        assertEquals(180.0, layer.position.second, 0.0)
        assertEquals(0.0, layer.inPoint, 0.0)
        assertEquals(4.0, layer.outPoint, 0.0)
        assertEquals(100.0, layer.opacity, 0.0)
        assertEquals("Inter", layer.font)
    }

    @Test
    fun unknownLayerTypeIsSkippedWithWarningNotACrash() {
        val json = sampleJson(
            layers = """
                [{"name": "Cam", "type": "camera"},
                 {"name": "T", "type": "text", "text": "Hi"}]
            """.trimIndent()
        )
        val project = AeInterchange.parse(json)

        assertEquals(1, project.comps[0].layers.size)
        assertEquals("T", project.comps[0].layers[0].name)
        assertEquals(1, project.warnings.size)
        assertTrue(project.warnings[0].contains("camera"))
    }

    @Test
    fun rejectsDocumentsWithoutTheInterchangeMarker() {
        assertThrows(IllegalArgumentException::class.java) {
            AeInterchange.parse("""{"schema": 2, "items": {}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AeInterchange.parse("""{"format": "effectcraft-ae-interchange", "version": 99, "comps": []}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AeInterchange.parse("not json at all")
        }
    }

    @Test
    fun rejectsCompsWithInvalidDimensionsOrFrameRate() {
        val badWidth = """
            {"format": "effectcraft-ae-interchange", "version": 1,
             "comps": [{"name": "C", "width": 0, "height": 360, "frameRate": 30, "duration": 4.0, "layers": []}]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { AeInterchange.parse(badWidth) }

        val badRate = """
            {"format": "effectcraft-ae-interchange", "version": 1,
             "comps": [{"name": "C", "width": 640, "height": 360, "frameRate": 0, "duration": 4.0, "layers": []}]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { AeInterchange.parse(badRate) }
    }

    @Test
    fun rejectsProjectWithNoCompositions() {
        assertThrows(IllegalArgumentException::class.java) {
            AeInterchange.parse(
                """{"format": "effectcraft-ae-interchange", "version": 1, "comps": []}"""
            )
        }
    }

    @Test
    fun isInterchangeSniffsOnlyTheMarker() {
        assertTrue(AeInterchange.isInterchange(sampleJson()))
        assertFalse(AeInterchange.isInterchange("""{"schema": 2}"""))
        assertFalse(AeInterchange.isInterchange("garbage"))
        assertFalse(AeInterchange.isInterchange(""))
    }

    @Test
    fun footageKindDefaultsFromFileExtension() {
        val json = """
            {
              "format": "effectcraft-ae-interchange", "version": 1,
              "comps": [{
                "name": "C", "width": 640, "height": 360,
                "frameRate": 30, "duration": 4.0,
                "layers": [
                  {"name": "Pic", "type": "footage", "file": "media/pic.png"},
                  {"name": "Song", "type": "footage", "file": "media/song.mp3"}
                ]
              }]
            }
        """.trimIndent()
        val layers = AeInterchange.parse(json).comps[0].layers

        assertEquals("still", layers[0].kind)
        assertEquals("audio", layers[1].kind)
    }
}
