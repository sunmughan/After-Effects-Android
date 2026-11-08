package com.codeair.effectcraft.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3: the AE interchange -> canonical .ecproj converter. These tests
 * assert the canonical document's shape (the same shape the native
 * inspector parses); the instrumented test proves the native side
 * accepts it end to end.
 */
class AeInterchangeConverterTest {

    private val interchangeJson = """
        {
          "format": "effectcraft-ae-interchange",
          "version": 1,
          "projectName": "My Project",
          "comps": [{
            "name": "Main", "width": 1920, "height": 1080,
            "frameRate": 30, "duration": 10.0,
            "background": [0.1, 0.2, 0.3],
            "layers": [
              {"name": "Title", "type": "text", "text": "Hello",
               "font": "Arial", "fontSize": 72, "color": [1, 1, 1, 1],
               "position": [960, 540], "opacity": 100,
               "startTime": 0, "inPoint": 0, "outPoint": 10,
               "keyframes": {
                 "position": [
                   {"time": 0, "value": [960, 540]},
                   {"time": 2, "value": [960, 300]}
                 ],
                 "opacity": [{"time": 0, "value": 100}, {"time": 1, "value": 0}]
               }},
              {"name": "BG", "type": "solid", "color": [0.1, 0.2, 0.8],
               "width": 1920, "height": 1080, "position": [960, 540]},
              {"name": "Clip.mp4", "type": "footage", "file": "media/Clip.mp4",
               "width": 1280, "height": 720, "duration": 8.0, "kind": "video",
               "position": [640, 360], "inPoint": 1, "outPoint": 9}
            ]
          }]
        }
    """.trimIndent()

    private fun converted(): JSONObject {
        val project = AeInterchange.parse(interchangeJson)
        val json = AeInterchangeConverter.toCanonicalJson(project) { rel ->
            "/imported/$rel"
        }
        return JSONObject(json)
    }

    @Test
    fun outputIsCanonicalSchemaTwoWithOneComp() {
        val doc = converted()

        assertEquals(2, doc.getInt("schema"))
        val items = doc.getJSONObject("items")
        // Items hold the comp + the solid item + the footage item = 3
        // entries; the 3 layers live inside the comp, not in items.
        assertEquals(3, items.length())
        // Ids allocated: comp 1, text layer 2, solid item 3, solid
        // layer 4, footage item 5, footage layer 6; the prop groups
        // and properties share the same counter, so next_id is far
        // above the item/layer count — it only has to exceed it.
        assertTrue(doc.getLong("next_id") > 6)
    }

    @Test
    fun compKeepsNameDimensionsFrameRateAndDurationTicks() {
        val doc = converted()
        val items = doc.getJSONObject("items")
        val comp = items.getJSONObject("1").getJSONObject("kind")

        assertEquals("Comp", comp.getString("type"))
        assertEquals(1920, comp.getInt("width"))
        assertEquals(1080, comp.getInt("height"))
        assertEquals(30, comp.getJSONObject("frame_rate").getInt("num"))
        assertEquals(1, comp.getJSONObject("frame_rate").getInt("den"))
        // 10 seconds at 254,016,000,000 ticks per second.
        assertEquals(2_540_160_000_000L, comp.getLong("duration"))
        assertEquals(3, comp.getJSONArray("layers").length())
        assertEquals(0.1, comp.getJSONArray("background").getDouble(0), 0.0)
    }

    @Test
    fun textLayerHasTransformWithPositionKeyframesInTicks() {
        val doc = converted()
        val items = doc.getJSONObject("items")
        val comp = items.getJSONObject("1").getJSONObject("kind")
        val layer = comp.getJSONArray("layers").getJSONObject(0)

        assertEquals("Title", layer.getString("name"))
        assertEquals("Text", layer.getJSONObject("source").getString("type"))
        val props = layer.getJSONObject("props")
        val transform = props.getJSONArray("children").let { children ->
            (0 until children.length())
                .map { children.getJSONObject(it) }
                .first { it.getString("match") == "transform" }
        }
        val position = (0 until transform.getJSONArray("children").length())
            .map { transform.getJSONArray("children").getJSONObject(it) }
            .first { it.getString("match") == "position" }
        val keys = position.getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(0L, keys.getJSONObject(0).getLong("time"))
        // 2 seconds in ticks.
        assertEquals(508_032_000_000L, keys.getJSONObject(1).getLong("time"))
        assertEquals(300.0, keys.getJSONObject(1).getJSONObject("value").getJSONArray("v").getDouble(1), 0.0)

        val opacity = (0 until transform.getJSONArray("children").length())
            .map { transform.getJSONArray("children").getJSONObject(it) }
            .first { it.getString("match") == "opacity" }
        assertEquals(2, opacity.getJSONArray("keys").length())
        assertEquals(0.0, opacity.getJSONArray("keys").getJSONObject(1).getJSONObject("value").getDouble("v"), 0.0)
    }

    @Test
    fun textLayerCarriesSourceText() {
        val doc = converted()
        val items = doc.getJSONObject("items")
        val comp = items.getJSONObject("1").getJSONObject("kind")
        val props = comp.getJSONArray("layers").getJSONObject(0).getJSONObject("props")
        val textGroup = (0 until props.getJSONArray("children").length())
            .map { props.getJSONArray("children").getJSONObject(it) }
            .first { it.getString("match") == "text" }
        val sourceText = (0 until textGroup.getJSONArray("children").length())
            .map { textGroup.getJSONArray("children").getJSONObject(it) }
            .first { it.getString("match") == "sourceText" }
        val textDoc = sourceText.getJSONObject("value").getJSONObject("v")
        assertEquals("Hello", textDoc.getString("text"))
        assertEquals("Arial", textDoc.getString("font"))
        assertEquals(72.0, textDoc.getDouble("size"), 0.0)
    }

    @Test
    fun footageLayerPointsAtResolvedMediaPath() {
        val doc = converted()
        val items = doc.getJSONObject("items")
        val comp = items.getJSONObject("1").getJSONObject("kind")
        val layer = comp.getJSONArray("layers").getJSONObject(2)
        val footageItemId = layer.getJSONObject("source").getInt("item")
        val footage = items.getJSONObject(footageItemId.toString()).getJSONObject("kind")

        assertEquals("Footage", footage.getString("type"))
        assertEquals("/imported/media/Clip.mp4", footage.getString("path"))
        assertEquals("Video", footage.getString("kind"))
        assertEquals(1280, footage.getInt("width"))
        assertEquals(false, footage.getBoolean("missing"))
        // Layer in/out points are the interchange seconds in ticks.
        assertEquals(254_016_000_000L, layer.getLong("in_point"))
        assertEquals(2_286_144_000_000L, layer.getLong("out_point"))
    }

    @Test
    fun missingMediaIsMarkedMissingInsteadOfFailing() {
        val project = AeInterchange.parse(interchangeJson)
        val json = AeInterchangeConverter.toCanonicalJson(project) { null }
        val items = JSONObject(json).getJSONObject("items")
        val comp = items.getJSONObject("1").getJSONObject("kind")
        val layer = comp.getJSONArray("layers").getJSONObject(2)
        val footageItemId = layer.getJSONObject("source").getInt("item")
        val footage = items.getJSONObject(footageItemId.toString()).getJSONObject("kind")

        assertEquals(true, footage.getBoolean("missing"))
        assertEquals("media/Clip.mp4", footage.getString("path"))
    }

    @Test
    fun frameRate2997Becomes30000Over1001() {
        val json = """
            {"format": "effectcraft-ae-interchange", "version": 1,
             "comps": [{"name": "C", "width": 640, "height": 360,
                        "frameRate": 29.97, "duration": 4.0, "layers": []}]}
        """.trimIndent()
        val doc = JSONObject(AeInterchangeConverter.toCanonicalJson(AeInterchange.parse(json)) { null })
        val rate = doc.getJSONObject("items").getJSONObject("1").getJSONObject("kind").getJSONObject("frame_rate")
        assertEquals(30000, rate.getInt("num"))
        assertEquals(1001, rate.getInt("den"))
    }
}
