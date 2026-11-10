package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effect import (AepEffectImport) on the real fixture, with a fake spec provider. */
class AepEffectImportTest {

    /** A fake provider that returns the group skeleton the native engine would: the
     * parameters with their defaults and UI, uids numbered from 1. */
    private fun fixtureLayer(): Triple<AepTypedView.Layer, AepEffects.EffectDefinitions, AepImportReport.Collector> {
        val bytes = AepContainerTest.resourceBytes("/aep/synthetic.aep")
        val file = AepxReader.parseAny(bytes)
        val project = AepTypedView.Project.of(file)!!
        val comp = project.allItems().first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        val layer = comp.layers().first()
        return Triple(layer, AepEffects.EffectDefinitions.of(project), AepImportReport.Collector())
    }

    private fun propOf(group: JSONObject, match: String): JSONObject {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no prop $match in ${group.optString("match")}")
    }

    private fun scalarOf(group: JSONObject, match: String): Double =
        propOf(group, match).getJSONObject("value").getDouble("v")

    private fun enumOf(group: JSONObject, match: String): Long =
        propOf(group, match).getJSONObject("value").getLong("v")

    @Test
    fun fixtureLevelsAndGlowImport() {
        val (layer, defs, col) = fixtureLayer()
        var nextUid = 1000L
        val result = AepEffectImport.importEffects(
            layer = layer,
            compName = "Comp",
            layerName = "Footage",
            layerIndex = 0,
            aepLayerId = 17,
            size = 1920.0 to 1080.0,
            defs = defs,
            provider = FakeEffectSpecs(),
            allocBlock = { n -> val base = nextUid; nextUid += n; base },
            timescale = 600,
            col = col
        )
        assertEquals(2, result.groups.size)
        assertEquals(2, col.imported.effects)
        assertTrue(result.refs.isEmpty())

        // Levels: channel has no record (default RGB), the values come from the histogram.
        val levels = result.groups[0]
        assertEquals("ec.color.levels", levels.getString("match"))
        assertEquals(0L, enumOf(levels, "channel"))
        val fx = AepEffects.effectsOf(layer)[0]
        val histogram = AepArbitrary.LevelsData.parse(fx.arbitraryData("ADBE Easy Levels2-0002")!!)!!
        val rgb = histogram.channels[0]
        assertEquals(rgb[0].toDouble(), scalarOf(levels, "inBlack"), 1e-9)
        assertEquals(rgb[1].toDouble(), scalarOf(levels, "inWhite"), 1e-9)
        assertEquals(rgb[2].toDouble(), scalarOf(levels, "gamma"), 1e-9)
        assertEquals(rgb[3].toDouble(), scalarOf(levels, "outBlack"), 1e-9)
        assertEquals(rgb[4].toDouble(), scalarOf(levels, "outWhite"), 1e-9)
        // Clip To Output Black is stored as 3.0 (item 3); White has no record (default 3).
        assertEquals(2L, enumOf(levels, "clipToOutputBlack"))
        assertEquals(2L, enumOf(levels, "clipToOutputWhite"))

        // Glow: midpoint is stored as 0.5 and converted by 100 to 50; its expression is
        // converted too, so it is reported instead of imported.
        val glow = result.groups[1]
        assertEquals("ec.stylize.glow", glow.getString("match"))
        assertEquals(50.0, scalarOf(glow, "abMidpoint"), 1e-9)
        assertEquals(60.0, scalarOf(glow, "threshold"), 1e-9)
        assertEquals(1L, enumOf(glow, "based"))
        val colorA = propOf(glow, "colorA").getJSONObject("value").getJSONArray("v")
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), (0 until 4).map { colorA.getDouble(it) })
        val colorB = propOf(glow, "colorB").getJSONObject("value").getJSONArray("v")
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0), (0 until 4).map { colorB.getDouble(it) })

        // The two losses are the converted expressions: Levels' Clip To Output Black
        // (a popup) and Glow's A & B Midpoint (a scaled slider) both carry one.
        val report = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
        assertEquals(2, report.losses.sumOf { it.count })
        val loss = report.losses.first()
        assertEquals(AepImportReport.LossCategory.Effects, loss.category)
        assertEquals(2, loss.details.size)
        assertTrue(loss.details[0].contains("Clip To Output Black"))
        assertTrue(loss.details[1].contains("A & B Midpoint"))
        assertTrue(loss.details.all { it.contains("expression") })
    }

    @Test
    fun unknownEffectIsALoss() {
        val (layer, defs, col) = fixtureLayer()
        // A provider that knows nothing: both effects are lost, none imported.
        val none = AepEffectImport.EffectSpecProvider { _, _, _, _ -> null }
        val result = AepEffectImport.importEffects(
            layer, "Comp", "Footage", 0, 17, 1920.0 to 1080.0, defs, none,
            { n -> 1L }, 600, col
        )
        assertTrue(result.groups.isEmpty())
        assertEquals(0, col.imported.effects)
        assertEquals(2, col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.sumOf { it.count })
    }

    @Test
    fun groupUidsAreRemappedIntoPatchSpace() {
        val (layer, defs, col) = fixtureLayer()
        var nextUid = 5000L
        val result = AepEffectImport.importEffects(
            layer, "Comp", "Footage", 0, 17, 1920.0 to 1080.0, defs, FakeEffectSpecs(),
            { n -> val base = nextUid; nextUid += n; base }, 600, col
        )
        val uids = ArrayList<Long>()
        fun collect(node: JSONObject) {
            uids.add(node.getLong("uid"))
            val kids = node.optJSONArray("children") ?: return
            for (i in 0 until kids.length()) collect(kids.getJSONObject(i))
        }
        for (group in result.groups) collect(group)
        assertTrue(uids.all { it >= 5000L })
        assertEquals(uids.size, uids.toSet().size)
    }
}
