package com.codeair.effectcraft.data.aep

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end `.aep` import on the real fixture (synthetic.aep, MIT OR Apache-2.0,
 * made by the official EffectCraft contributors in After Effects 2026). */
class AepImporterTest {

    private fun syntheticBytes(): ByteArray =
        AepContainerTest.resourceBytes("/aep/synthetic.aep")

    @Test
    fun analyzeCountsWhatWasImported() {
        val analysis = AepImporter.analyze(syntheticBytes())
        val imported = analysis.report.imported
        assertEquals(2, imported.layers)
        assertEquals(1, imported.footage)
        assertEquals(1, imported.solids)
        assertEquals(0, imported.keyframes)
        assertEquals(0, imported.expressions)
        assertEquals(1, imported.compositions)
    }

    @Test
    fun analyzeReportsOnlyRealLosses() {
        val analysis = AepImporter.analyze(syntheticBytes())
        val losses = analysis.report.losses.associate { it.category to it.count }
        // The two effects, the two folders, and the unread project settings.
        // No false positives: the default rotations, orientation, separated
        // dimensions, empty layer styles and always-present layer groups lose nothing.
        assertEquals(
            mapOf(
                AepImportReport.LossCategory.Effects to 2,
                AepImportReport.LossCategory.UnsupportedItems to 2,
                AepImportReport.LossCategory.ProjectSettings to 1
            ),
            losses
        )
        val effectDetails = analysis.report.losses
            .first { it.category == AepImportReport.LossCategory.Effects }.details
        assertTrue(effectDetails.any { it.contains("ADBE Easy Levels2") })
        assertTrue(effectDetails.any { it.contains("ADBE Glo2") })
        assertEquals(
            listOf("E:/Projekte/ae-corpus/scratch/synth/seq"),
            analysis.report.missingFootage
        )
        assertEquals("26.2.1x2", analysis.report.aeVersion.fileVersion)
        assertTrue(analysis.report.aeVersion.tested)
        assertTrue(analysis.report.limitations.isNotEmpty())
    }

    @Test
    fun importRefusesWithoutLossAllowance() {
        val result = AepImporter.import(syntheticBytes(), allowLosses = false)
        assertTrue(result is AepImporter.ImportResult.Refused)
    }

    @Test
    fun importWithLossesProducesCanonicalSchema2() {
        val result = AepImporter.import(syntheticBytes(), allowLosses = true)
        assertTrue(result is AepImporter.ImportResult.Imported)
        val json = (result as AepImporter.ImportResult.Imported).canonicalJson
        val root = JSONObject(json)
        assertEquals(2, root.getInt("schema"))
        val items = root.getJSONObject("items")
        var comp: JSONObject? = null
        var footage: JSONObject? = null
        var solid: JSONObject? = null
        val keys = items.keys()
        while (keys.hasNext()) {
            val item = items.getJSONObject(keys.next())
            val kind = item.getJSONObject("kind")
            when (kind.optString("type")) {
                "Comp" -> comp = kind
                "Footage" -> footage = kind
                "Solid" -> solid = kind
            }
        }
        assertTrue(comp != null)
        assertTrue(footage != null)
        assertTrue(solid != null)
        assertTrue(footage!!.getBoolean("missing"))
        assertEquals(64, solid!!.getInt("width"))
        assertEquals(48, solid.getInt("height"))
        val layers = comp!!.getJSONArray("layers")
        assertEquals(2, layers.length())
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            assertTrue(layer.has("stretch"))
            assertTrue(layer.has("blend_mode"))
            assertTrue(layer.has("switches"))
            assertEquals("Normal", layer.getString("blend_mode"))
        }
        // The patched transform props: rotationX/Y and orientation at their defaults.
        val firstLayer = layers.getJSONObject(0)
        val rotationX = findTransformProp(firstLayer, "rotationX")
        assertEquals("Scalar", rotationX.getJSONObject("value").getString("t"))
        assertEquals(0.0, rotationX.getJSONObject("value").getDouble("v"), 0.0)
        val orientation = findTransformProp(firstLayer, "orientation")
        assertEquals("Vec3", orientation.getJSONObject("value").getString("t"))
        val v = orientation.getJSONObject("value").getJSONArray("v")
        assertEquals(0.0, v.getDouble(0), 0.0)
        assertEquals(0.0, v.getDouble(1), 0.0)
        assertEquals(0.0, v.getDouble(2), 0.0)
    }

    @Test
    fun analyzeRejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            AepImporter.analyze(byteArrayOf(1, 2, 3, 4, 5))
        }
    }

    @Test
    fun analyzeRejectsAProjectWithoutAComposition() {
        val aepx = """<AfterEffectsProject><abcd bdata="01020304"/></AfterEffectsProject>"""
            .toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { AepImporter.analyze(aepx) }
    }

    private fun findTransformProp(layer: JSONObject, match: String): JSONObject {
        val props = layer.getJSONObject("props")
        val children = props.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") != "transform") continue
            val tchildren = child.getJSONArray("children")
            for (j in 0 until tchildren.length()) {
                val prop = tchildren.getJSONObject(j)
                if (prop.optString("match") == match) return prop
            }
        }
        throw AssertionError("no transform prop $match in layer ${layer.optString("name")}")
    }
}
