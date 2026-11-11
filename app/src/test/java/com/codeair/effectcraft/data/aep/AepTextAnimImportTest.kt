package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text animator import (AepTextAnimImport) on hand-built animator groups. */
class AepTextAnimImportTest {

    private fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (v ushr (24 - 8 * i)).toByte()
        return b
    }

    private fun f64(v: Double): ByteArray {
        val b = ByteArray(8)
        java.nio.ByteBuffer.wrap(b).putDouble(v)
        return b
    }

    private fun tdb4(dims: Int, timeScale: Long): ByteArray {
        val b = ByteArray(124)
        b[3] = dims.toByte()
        u32(timeScale).copyInto(b, 12)
        return b
    }

    private fun lhd3(count: Long, recordSize: Long): ByteArray {
        val b = ByteArray(52)
        u32(0x00d00beeL).copyInto(b, 0)
        u32(count).copyInto(b, 8)
        u32(recordSize).copyInto(b, 16)
        return b
    }

    private fun tdmn(name: String): AepContainer.Chunk =
        AepContainer.Chunk.data("tdmn", name.toByteArray(Charsets.US_ASCII))

    private fun tdsb(flags: Long): AepContainer.Chunk =
        AepContainer.Chunk.data("tdsb", u32(flags))

    private fun tdbs(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdbs", children.toList())

    private fun tdgp(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdgp", children.toList())

    private fun keyList(header: ByteArray, ldat: ByteArray): AepContainer.Chunk =
        AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", header),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )

    /** A 48-byte plain key record: time in units at 0, value f64 at 8. */
    private fun plainRecord(time: Int, value: Double): ByteArray {
        val b = ByteArray(48)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = 1
        b[5] = 1
        f64(value).copyInto(b, 8)
        return b
    }

    private fun staticProp(name: String, values: List<Double>, flags: Long = 1): AepContainer.Chunk {
        val cdat = ByteArray(values.size * 8)
        for ((i, v) in values.withIndex()) f64(v).copyInto(cdat, i * 8)
        return tdbs(tdsb(flags), AepContainer.Chunk.data("tdb4", tdb4(values.size, 25)), AepContainer.Chunk.data("cdat", cdat))
    }

    private fun animatedProp(name: String, keys: List<Pair<Int, Double>>, timeScale: Long = 25): AepContainer.Chunk {
        val ldat = ByteArray(keys.size * 48)
        for ((i, key) in keys.withIndex()) plainRecord(key.first, key.second).copyInto(ldat, i * 48)
        return tdbs(
            tdsb(1),
            AepContainer.Chunk.data("tdb4", tdb4(1, timeScale)),
            keyList(lhd3(keys.size.toLong(), 48), ldat)
        )
    }

    private fun propEntry(name: String, prop: AepContainer.Chunk): Array<AepContainer.Chunk> =
        arrayOf(tdmn(name), prop)

    private fun groupEntry(name: String, group: AepContainer.Chunk): Array<AepContainer.Chunk> =
        arrayOf(tdmn(name), group)

    private fun rangeSelector(
        offsetEntry: Array<AepContainer.Chunk>,
        advanced: List<AepContainer.Chunk> = emptyList()
    ): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += offsetEntry.toList()
        if (advanced.isNotEmpty()) {
            kids += groupEntry("ADBE Text Range Advanced", tdgp(*advanced.toTypedArray()))
        }
        return tdgp(*kids.toTypedArray())
    }

    private fun animator(
        props: List<AepContainer.Chunk>,
        selectors: List<AepContainer.Chunk>
    ): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += groupEntry("ADBE Text Animator Properties", tdgp(*props.toTypedArray()))
        kids += groupEntry("ADBE Text Selectors", tdgp(*selectors.toTypedArray()))
        return tdgp(*kids.toTypedArray())
    }

    private fun import(
        animators: AepTypedView.PropGroup?,
        col: AepImportReport.Collector = AepImportReport.Collector()
    ): Pair<List<org.json.JSONObject>, List<AepImportReport.Loss>> {
        var nextUid = 1L
        val out = AepTextAnimImport.importAnimators(
            animators, "text layer 'digital slides'", 25L, { n -> val base = nextUid; nextUid += n; base }, col
        )
        val losses = col.finish(
            AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)
        ).losses
        return out to losses
    }

    private fun animatorsGroup(vararg animators: AepContainer.Chunk): AepTypedView.PropGroup {
        val kids = ArrayList<AepContainer.Chunk>()
        for (a in animators) kids += groupEntry("ADBE Text Animator", a)
        return AepTypedView.PropGroup.fromChunk(tdgp(*kids.toTypedArray()))!!
    }

    private fun childOf(group: org.json.JSONObject, match: String): org.json.JSONObject {
        val children = group.optJSONArray("children") ?: JSONArray()
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no child $match in ${group.optString("match")}")
    }

    private fun propValue(group: org.json.JSONObject, match: String): org.json.JSONObject =
        childOf(group, match).getJSONObject("value")

    @Test
    fun opacityAndPositionImport() {
        val animators = animatorsGroup(
            animator(
                props = listOf(
                    *propEntry("ADBE Text Position 3D", staticProp("x", listOf(-30.0, 0.0, 0.0))),
                    *propEntry("ADBE Text Opacity", staticProp("x", listOf(0.0)))
                ),
                selectors = listOf(
                    *groupEntry(
                        "ADBE Text Selector",
                        rangeSelector(
                            propEntry("ADBE Text Percent Offset", staticProp("x", listOf(0.0))),
                            advanced = listOf(
                                *propEntry("ADBE Text Range Shape", staticProp("x", listOf(2.0))),
                                *propEntry("ADBE Text Selector Smoothness", staticProp("x", listOf(100.0), flags = 3)),
                                *propEntry("ADBE Text Randomize Order", staticProp("x", listOf(1.0))),
                                *propEntry("ADBE Text Random Seed", staticProp("x", listOf(0.0)))
                            )
                        )
                    )
                )
            )
        )
        val col = AepImportReport.Collector()
        val (out, losses) = import(animators, col)
        assertEquals(1, out.size)
        assertEquals(1, col.imported.textAnimators)
        assertTrue(losses.isEmpty())
        val animatorGroup = out[0]
        assertEquals("animator", animatorGroup.getString("match"))
        assertEquals("Animator 1", animatorGroup.getString("name"))
        assertEquals("Indexed", animatorGroup.getJSONObject("kind").getString("kind"))
        val properties = childOf(animatorGroup, "properties")
        val position = propValue(properties, "position")
        assertEquals("Vec3", position.getString("t"))
        assertEquals(listOf(-30.0, 0.0, 0.0), (0 until 3).map { position.getJSONArray("v").getDouble(it) })
        assertEquals(0.0, propValue(properties, "opacity").getDouble("v"), 0.0)
        val selectors = childOf(animatorGroup, "selectors")
        assertEquals(1, selectors.getJSONArray("children").length())
        val range = selectors.getJSONArray("children").getJSONObject(0)
        assertEquals("rangeSelector", range.getString("match"))
        assertEquals(0.0, propValue(range, "start").getDouble("v"), 0.0)
        assertEquals(100.0, propValue(range, "end").getDouble("v"), 0.0)
        assertEquals(0.0, propValue(range, "offset").getDouble("v"), 0.0)
        val advanced = childOf(range, "advanced")
        // Stored Shape 2.0 is the 1-based second item: Ramp Up (Enum 1).
        assertEquals(1L, propValue(advanced, "shape").getLong("v"))
        assertEquals(100.0, propValue(advanced, "smoothness").getDouble("v"), 0.0)
        assertTrue(propValue(advanced, "randomize").getBoolean("v"))
        assertEquals(0.0, propValue(advanced, "randomSeed").getDouble("v"), 0.0)
    }

    @Test
    fun animatedOffsetImportsKeys() {
        val animators = animatorsGroup(
            animator(
                props = listOf(*propEntry("ADBE Text Opacity", staticProp("x", listOf(0.0)))),
                selectors = listOf(
                    *groupEntry(
                        "ADBE Text Selector",
                        rangeSelector(
                            propEntry(
                                "ADBE Text Percent Offset",
                                animatedProp(
                                    "x",
                                    listOf(0 to -100.0, 51200 to 100.0),
                                    timeScale = 25600
                                )
                            )
                        )
                    )
                )
            )
        )
        val (out, losses) = import(animators)
        assertTrue(losses.isEmpty())
        val range = childOf(out[0], "selectors").getJSONArray("children").getJSONObject(0)
        val offset = childOf(range, "offset")
        val keys = offset.getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(-100.0, keys.getJSONObject(0).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(100.0, keys.getJSONObject(1).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(-100.0, offset.getJSONObject("value").getDouble("v"), 0.0)
    }

    @Test
    fun unmappedPropertyIsALoss() {
        val animators = animatorsGroup(
            animator(
                props = listOf(
                    *propEntry("ADBE Text Opacity", staticProp("x", listOf(0.0))),
                    *propEntry("ADBE Text Character Value", staticProp("x", listOf(65.0)))
                ),
                selectors = emptyList()
            )
        )
        val (out, losses) = import(animators)
        assertEquals(1, out.size)
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("Character Value"))
    }

    @Test
    fun wigglySelectorIsALoss() {
        val animators = animatorsGroup(
            animator(
                props = listOf(*propEntry("ADBE Text Opacity", staticProp("x", listOf(0.0)))),
                selectors = listOf(
                    *groupEntry("ADBE Text Wiggly Selector", tdgp())
                )
            )
        )
        val (out, losses) = import(animators)
        assertEquals(1, out.size)
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("Wiggly"))
        val selectors = childOf(out[0], "selectors")
        assertEquals(0, selectors.getJSONArray("children").length())
    }

    @Test
    fun indexUnitsUseIndexRange() {
        val animators = animatorsGroup(
            animator(
                props = listOf(*propEntry("ADBE Text Opacity", staticProp("x", listOf(0.0)))),
                selectors = listOf(
                    *groupEntry(
                        "ADBE Text Selector",
                        rangeSelector(
                            propEntry("ADBE Text Index Offset", staticProp("x", listOf(5.0))),
                            advanced = listOf(
                                *propEntry("ADBE Text Range Units", staticProp("x", listOf(2.0)))
                            )
                        )
                    )
                )
            )
        )
        val (out, losses) = import(animators)
        assertTrue(losses.isEmpty())
        val range = childOf(out[0], "selectors").getJSONArray("children").getJSONObject(0)
        assertEquals(5.0, propValue(range, "offset").getDouble("v"), 0.0)
        val advanced = childOf(range, "advanced")
        assertEquals(1L, propValue(advanced, "units").getLong("v"))
    }
}
