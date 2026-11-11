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
        // GroupKind is internally tagged in the canonical model, so
        // a prop group's kind is an object {"kind": "Plain"}, never
        // the bare string "Plain" (that shape fails native
        // inspection). Pin it here so a regression is caught locally.
        val rootKind = items.getJSONObject("1").getJSONObject("kind")
            .getJSONArray("layers").getJSONObject(0)
            .getJSONObject("props").getJSONObject("kind")
        assertEquals("Plain", rootKind.getString("kind"))
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

    @Test
    fun precompLayerPointsAtTheNestedComp() {
        val nested = AeComp(
            name = "Nested", width = 640, height = 360, frameRate = 25.0,
            durationSeconds = 2.0, background = listOf(0.0, 0.0, 0.0),
            layers = listOf(
                AeLayer(name = "Inner", type = AeLayerType.SOLID, color = listOf(1.0, 0.0, 0.0), width = 640, height = 360)
            )
        )
        val main = AeComp(
            name = "Main", width = 1920, height = 1080, frameRate = 25.0,
            durationSeconds = 5.0, background = listOf(0.0, 0.0, 0.0),
            layers = listOf(
                AeLayer(name = "Precomp", type = AeLayerType.COMP, compIndex = 1, width = 640, height = 360),
                AeLayer(name = "Title", type = AeLayerType.TEXT, text = "Hi")
            )
        )
        val project = AeProject(projectName = "Main", comps = listOf(main, nested), warnings = emptyList())
        val doc = JSONObject(AeInterchangeConverter.toCanonicalJson(project) { null })
        val items = doc.getJSONObject("items")
        // Composition ids are pre-allocated in project order, so the precomp
        // layer can point at a composition converted after it.
        val nestedId = items.keys().asSequence()
            .first { items.getJSONObject(it).optString("name") == "Nested" }
        val mainId = items.keys().asSequence()
            .first { items.getJSONObject(it).optString("name") == "Main" }
        val precompLayer = items.getJSONObject(mainId).getJSONObject("kind")
            .getJSONArray("layers").getJSONObject(0)
        assertEquals("Comp", precompLayer.getJSONObject("source").getString("type"))
        assertEquals(
            nestedId.toLong(),
            precompLayer.getJSONObject("source").getLong("item")
        )
        // The nested composition itself is converted, with its own layers.
        assertEquals(
            1,
            items.getJSONObject(nestedId).getJSONObject("kind").getJSONArray("layers").length()
        )
        // Uid invariant: every non-zero uid unique and below next_id.
        val nextId = doc.getLong("next_id")
        val uids = ArrayList<Long>()
        fun collect(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("uid")) uids.add(node.getLong("uid"))
                    val keys = node.keys()
                    while (keys.hasNext()) collect(node.get(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) collect(node.get(i))
            }
        }
        collect(doc)
        assertTrue(uids.all { it < nextId })
        val realUids = uids.filter { it != 0L }
        assertEquals(realUids.size, realUids.toSet().size)
    }

    @Test
    fun cameraLayerHasCameraSourceTransformAndOptions() {
        val comp = AeComp(
            name = "Main", width = 1920, height = 1080, frameRate = 25.0,
            durationSeconds = 6.0, background = listOf(0.0, 0.0, 0.0),
            layers = listOf(
                AeLayer(
                    name = "Camera 1", type = AeLayerType.CAMERA,
                    position = 0.0 to 0.0, anchorPoint = 0.0 to 0.0,
                    width = 1920, height = 1080
                )
            )
        )
        val project = AeProject(projectName = "Main", comps = listOf(comp), warnings = emptyList())
        val doc = JSONObject(AeInterchangeConverter.toCanonicalJson(project) { null })
        val layer = doc.getJSONObject("items").getJSONObject("1").getJSONObject("kind")
            .getJSONArray("layers").getJSONObject(0)
        assertEquals("Camera", layer.getJSONObject("source").getString("type"))
        assertEquals("Off", layer.getString("auto_orient"))
        val children = layer.getJSONObject("props").getJSONArray("children")
        // A camera has a transform and camera options, and neither masks nor
        // effects nor material options nor audio.
        assertEquals(2, children.length())
        val transform = children.getJSONObject(0)
        assertEquals("transform", transform.getString("match"))
        val tchildren = transform.getJSONArray("children")
        val matches = (0 until tchildren.length()).map { tchildren.getJSONObject(it).getString("match") }
        assertEquals(
            listOf("poi", "position", "orientation", "rotationX", "rotationY", "rotation"),
            matches
        )
        fun propAt(group: org.json.JSONArray, match: String): JSONObject {
            for (i in 0 until group.length()) {
                val p = group.getJSONObject(i)
                if (p.getString("match") == match) return p
            }
            throw AssertionError("no prop $match")
        }
        fun vec3Of(group: org.json.JSONArray, match: String): List<Double> {
            val v = propAt(group, match).getJSONObject("value").getJSONArray("v")
            return (0 until v.length()).map { v.getDouble(it) }
        }
        // The layer's stored position and point of interest, raw; the patch
        // step supplies the position's z from the file.
        assertEquals(listOf(0.0, 0.0, 0.0), vec3Of(tchildren, "poi"))
        assertEquals(listOf(0.0, 0.0, 0.0), vec3Of(tchildren, "position"))
        val options = children.getJSONObject(1)
        assertEquals("cameraOptions", options.getString("match"))
        val ochildren = options.getJSONArray("children")
        val omatches = (0 until ochildren.length()).map { ochildren.getJSONObject(it).getString("match") }
        assertEquals(
            listOf(
                "zoom", "dof", "focusDistance", "aperture", "blurLevel", "irisShape",
                "irisRotation", "irisRoundness", "irisAspectRatio", "irisDiffractionFringe",
                "highlightGain", "highlightThreshold", "highlightSaturation"
            ),
            omatches
        )
        // The 50 mm preset defaults for a 1920 px wide composition.
        fun scalarOf(group: org.json.JSONArray, match: String): Double =
            propAt(group, match).getJSONObject("value").getDouble("v")
        assertEquals(1920.0 * 50.0 / 36.0, scalarOf(ochildren, "zoom"), 1e-9)
        assertEquals(1920.0 * 50.0 / 36.0, scalarOf(ochildren, "focusDistance"), 1e-9)
        assertEquals(25.3, scalarOf(ochildren, "aperture"), 1e-9)
        assertEquals(100.0, scalarOf(ochildren, "blurLevel"), 0.0)
        assertEquals(255.0, scalarOf(ochildren, "highlightThreshold"), 0.0)
        assertEquals(1.0, scalarOf(ochildren, "irisAspectRatio"), 0.0)
    }
}
