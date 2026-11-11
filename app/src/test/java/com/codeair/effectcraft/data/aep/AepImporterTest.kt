package com.codeair.effectcraft.data.aep

import java.io.File
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
        assertEquals(2, imported.folders)
    }

    @Test
    fun analyzeReportsOnlyRealLosses() {
        val analysis = AepImporter.analyze(syntheticBytes())
        val losses = analysis.report.losses.associate { it.category to it.count }
        // The two effects. The two folders import now (see
        // analyzeCountsWhatWasImported), and the fixture's project settings
        // read cleanly.
        // No false positives: the default rotations, orientation, separated
        // dimensions, empty layer styles and always-present layer groups lose nothing.
        assertEquals(
            mapOf(
                AepImportReport.LossCategory.Effects to 2
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
    fun importWithFakeProviderImportsEffects() {
        // The native registry is absent on the JVM, so the fake provider stands in for
        // it: the fixture's Levels and Glow must land in the canonical document.
        val result = AepImporter.import(syntheticBytes(), allowLosses = true, FakeEffectSpecs())
        assertTrue(result is AepImporter.ImportResult.Imported)
        val imported = (result as AepImporter.ImportResult.Imported)
        assertEquals(2, imported.report.imported.effects)
        // The only effect losses left are the two converted expressions (a popup and a
        // scaled slider); the effects themselves are no longer lost.
        val losses = imported.report.losses.associate { it.category to it.count }
        assertEquals(2, losses[AepImportReport.LossCategory.Effects])
        val effectDetails = imported.report.losses
            .first { it.category == AepImportReport.LossCategory.Effects }.details
        assertTrue(effectDetails.all { it.contains("expression") })
        assertTrue(effectDetails.none { it.contains("not imported in this build") })

        val root = JSONObject(imported.canonicalJson)
        val layers = findComp(root).getJSONArray("layers")
        val effects = findEffectsGroup(layers.getJSONObject(0))
        assertEquals(2, effects.length())
        val levels = effects.getJSONObject(0)
        assertEquals("ec.color.levels", levels.getString("match"))
        val glow = effects.getJSONObject(1)
        assertEquals("ec.stylize.glow", glow.getString("match"))
        // Levels values come from the histogram arbitrary data, not the defaults.
        val bytes = syntheticBytes()
        val file = AepxReader.parseAny(bytes)
        val project = AepTypedView.Project.of(file)!!
        val comp = project.allItems().first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        val fx = AepEffects.effectsOf(comp.layers().first())[0]
        val histogram = AepArbitrary.LevelsData.parse(fx.arbitraryData("ADBE Easy Levels2-0002")!!)!!
        val rgb = histogram.channels[0]
        assertEquals(rgb[0].toDouble(), effectScalar(levels, "inBlack"), 1e-9)
        assertEquals(rgb[1].toDouble(), effectScalar(levels, "inWhite"), 1e-9)
        assertEquals(rgb[2].toDouble(), effectScalar(levels, "gamma"), 1e-9)
        assertEquals(50.0, effectScalar(glow, "abMidpoint"), 1e-9)
        assertEquals(60.0, effectScalar(glow, "threshold"), 1e-9)
        // Every uid in the document is below the root's next_id: the patch remap did
        // not collide with the converter's ids.
        val nextId = root.getLong("next_id")
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
        collect(root)
        assertTrue(uids.isNotEmpty())
        assertTrue(uids.all { it < nextId })
        // Uid 0 is the converter's unallocated sentinel (two nodes carry it in the
        // converter's own output); every real uid is unique.
        val realUids = uids.filter { it != 0L }
        assertEquals(realUids.size, realUids.toSet().size)
    }

    private fun findComp(root: JSONObject): JSONObject {
        val items = root.getJSONObject("items")
        val keys = items.keys()
        while (keys.hasNext()) {
            val item = items.getJSONObject(keys.next())
            val kind = item.getJSONObject("kind")
            if (kind.optString("type") == "Comp") return kind
        }
        throw AssertionError("no composition in canonical JSON")
    }

    private fun findEffectsGroup(layer: JSONObject): org.json.JSONArray {
        val children = layer.getJSONObject("props").getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == "effects") return child.getJSONArray("children")
        }
        throw AssertionError("no effects group in layer ${layer.optString("name")}")
    }

    private fun effectScalar(group: JSONObject, match: String): Double {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child.getJSONObject("value").getDouble("v")
        }
        throw AssertionError("no effect prop $match in ${group.optString("match")}")
    }

    @Test
    fun importWithFootageFolderResolvesMissingFootage() {
        // The fixture's footage is the sequence folder
        // "E:/Projekte/ae-corpus/scratch/synth/seq": with a folder resolver
        // pointing at a directory that holds a "seq" folder, the footage is
        // found and the canonical item is not missing.
        val dir = File.createTempFile("aep-footage-e2e-", "")
        assertTrue(dir.delete())
        assertTrue(dir.mkdir())
        val seq = File(dir, "seq")
        assertTrue(seq.mkdir())
        val resolver = AepFootageResolver.resolverFor(listOf(dir))
        val result = AepImporter.import(syntheticBytes(), allowLosses = true, FakeEffectSpecs(), resolver)
        assertTrue(result is AepImporter.ImportResult.Imported)
        val imported = result as AepImporter.ImportResult.Imported
        assertTrue(imported.report.missingFootage.isEmpty())
        val root = JSONObject(imported.canonicalJson)
        val items = root.getJSONObject("items")
        val keys = items.keys()
        var footage: JSONObject? = null
        while (keys.hasNext()) {
            val item = items.getJSONObject(keys.next())
            val kind = item.getJSONObject("kind")
            if (kind.optString("type") == "Footage") footage = kind
        }
        assertTrue(footage != null)
        assertEquals(false, footage!!.getBoolean("missing"))
        assertEquals(seq.absolutePath, footage.getString("path"))
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
