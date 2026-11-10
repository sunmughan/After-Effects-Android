package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Layer masks: path shapes, modes, feather/opacity/expansion, path keyframes. */
class AepMasksTest {

    // ------------------------------------------------------------------ builders

    private fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).putInt(v.toInt())
        return b
    }

    private fun f32(v: Float): ByteArray {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).putFloat(v)
        return b
    }

    private fun f64(v: Double): ByteArray {
        val b = ByteArray(8)
        ByteBuffer.wrap(b).putDouble(v)
        return b
    }

    /** A 124-byte `tdb4`: byte 3 is the component count, byte 7 the spatial flag,
     * bytes 12..15 the time scale. */
    private fun tdb4(dims: Int, spatial: Boolean, timeScale: Long): ByteArray {
        val b = ByteArray(124)
        b[3] = dims.toByte()
        b[7] = if (spatial) 1 else 0
        u32(timeScale).copyInto(b, 12)
        return b
    }

    /** A 52-byte `lhd3`: magic at word 0, count at word 2, record size at word 4. */
    private fun lhd3(count: Long, recordSize: Long): ByteArray {
        val b = ByteArray(52)
        u32(0x00d00beeL).copyInto(b, 0)
        u32(count).copyInto(b, 8)
        u32(recordSize).copyInto(b, 16)
        return b
    }

    private fun tdbs(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdbs", children.toList())

    private fun tdgp(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdgp", children.toList())

    private fun tdmn(name: String): AepContainer.Chunk =
        AepContainer.Chunk.data("tdmn", name.toByteArray(Charsets.US_ASCII))

    private fun tdsn(name: String): AepContainer.Chunk =
        AepContainer.Chunk(
            "tdsn",
            AepContainer.ChunkBody.Chunks(
                listOf(AepContainer.Chunk.data("Utf8", name.toByteArray(Charsets.UTF_8)))
            ),
            null
        )

    private fun keyList(header: ByteArray, ldat: ByteArray): AepContainer.Chunk =
        AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", header),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )

    /** A static property: `tdb4` + `cdat` with the given `f64` values. */
    private fun staticProp(dims: Int, values: List<Double>): AepContainer.Chunk {
        val cdat = ByteArray(values.size * 8)
        for ((i, v) in values.withIndex()) f64(v).copyInto(cdat, i * 8)
        return tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(dims, false, 25)),
            AepContainer.Chunk.data("cdat", cdat)
        )
    }

    /** A plain 1-component keyframe record (48 bytes). */
    private fun plainRecord(
        time: Int, inInterp: Int, outInterp: Int, flags: Int, value: Double,
        inSpeed: Double = 0.0, inInfluence: Double = 0.0,
        outSpeed: Double = 0.0, outInfluence: Double = 0.0
    ): ByteArray {
        val b = ByteArray(48)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = inInterp.toByte()
        b[5] = outInterp.toByte()
        b[6] = (flags shr 8).toByte()
        b[7] = flags.toByte()
        f64(value).copyInto(b, 8)
        f64(inSpeed).copyInto(b, 16)
        f64(inInfluence).copyInto(b, 24)
        f64(outSpeed).copyInto(b, 32)
        f64(outInfluence).copyInto(b, 40)
        return b
    }

    /** A 64-byte mask path keyframe record. */
    private fun pathRecord(time: Int, inInterp: Int, outInterp: Int): ByteArray {
        val b = ByteArray(64)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = inInterp.toByte()
        b[5] = outInterp.toByte()
        return b
    }

    /** A `LIST shap`: box + normalised points (three per vertex, last = in-handle of vertex 0). */
    private fun shap(box: List<Float>, points: List<Pair<Float, Float>>, open: Boolean = false): AepContainer.Chunk {
        val shph = ByteArray(21)
        shph[3] = if (open) 0x09 else 0x01
        for ((i, v) in box.withIndex()) f32(v).copyInto(shph, 4 + i * 4)
        shph[20] = 1
        val ldat = ByteArray(points.size * 8)
        for ((i, p) in points.withIndex()) {
            f32(p.first).copyInto(ldat, i * 8)
            f32(p.second).copyInto(ldat, i * 8 + 4)
        }
        return AepContainer.Chunk.list(
            "shap",
            listOf(
                AepContainer.Chunk.data("shph", shph),
                keyList(lhd3(points.size.toLong(), 8), ldat)
            )
        )
    }

    /** The four vertices of a rectangle as normalised points (no tangents). */
    private fun rectPoints(): List<Pair<Float, Float>> = listOf(
        0f to 0f, 0f to 0f, 0f to 1f,
        0f to 1f, 0f to 1f, 1f to 1f,
        1f to 1f, 1f to 1f, 1f to 0f,
        1f to 0f, 1f to 0f, 0f to 0f
    )

    /** The `om-s` mask-shape chunk: path property + `omks` shapes. */
    private fun maskShape(path: AepContainer.Chunk?, shapes: List<AepContainer.Chunk>): AepContainer.Chunk {
        val children = ArrayList<AepContainer.Chunk>()
        if (path != null) children.add(path)
        children.add(AepContainer.Chunk.list("omks", shapes))
        return AepContainer.Chunk.list("om-s", children)
    }

    /** One mask atom: `tdmn` + `mkif` + `tdgp` atom, as they sit in the parade. */
    private fun maskEntry(
        name: String?,
        mode: Long,
        inverted: Boolean,
        counter: Long,
        atomChildren: List<AepContainer.Chunk>
    ): List<AepContainer.Chunk> {
        val mkif = ByteArray(12)
        mkif[0] = if (inverted) 1 else 0
        u32(mode).copyInto(mkif, 4)
        u32(counter).copyInto(mkif, 8)
        val atomKids = ArrayList<AepContainer.Chunk>()
        if (name != null) atomKids.add(tdsn(name))
        atomKids.addAll(atomChildren)
        return listOf(
            tdmn("ADBE Mask Atom"),
            AepContainer.Chunk.data("mkif", mkif),
            tdgp(*atomKids.toTypedArray())
        )
    }

    private fun rootWithParade(paradeGroup: AepContainer.Chunk): AepTypedView.PropGroup =
        AepTypedView.PropGroup.fromChunk(
            tdgp(tdmn("ADBE Mask Parade"), paradeGroup)
        )!!

    private fun importRoot(
        root: AepTypedView.PropGroup,
        size: Pair<Double, Double> = 100.0 to 100.0,
        hasSource: Boolean = true
    ): Pair<List<org.json.JSONObject>, AepImportReport.Collector> {
        val col = AepImportReport.Collector()
        var nextUid = 1L
        val groups = AepMasks.importMasks(
            root, "Comp", "Solid", size, hasSource,
            { n -> val base = nextUid; nextUid += n; base },
            25, col
        )
        return groups to col
    }

    private fun propOf(group: org.json.JSONObject, match: String): org.json.JSONObject {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no prop $match in ${group.optString("match")}")
    }

    private fun pathVertices(group: org.json.JSONObject): List<List<Double>> {
        val v = propOf(group, "path").getJSONObject("value").getJSONObject("v")
            .getJSONArray("vertices")
        return (0 until v.length()).map { i ->
            val p = v.getJSONArray(i)
            listOf(p.getDouble(0), p.getDouble(1))
        }
    }

    /** Vertices compared with a delta: the stored points are `f32`, so exact
     * equality is float noise. */
    private fun assertVertices(expected: List<List<Double>>, actual: List<List<Double>>) {
        assertEquals(expected.size, actual.size)
        for ((i, e) in expected.withIndex()) {
            assertEquals(e[0], actual[i][0], 1e-4)
            assertEquals(e[1], actual[i][1], 1e-4)
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun staticMaskDecodesToLayerPixels() {
        val shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25))),
            listOf(shap(listOf(0.2f, 0.3f, 0.8f, 0.7f), rectPoints()))
        )
        val root = rootWithParade(
            tdgp(
                *maskEntry(
                    "Mask 1", 1, false, 1,
                    listOf(tdmn("ADBE Mask Shape"), shape)
                ).toTypedArray()
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(1, col.imported.masks)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
        val g = groups[0]
        assertEquals("mask", g.getString("match"))
        assertEquals("Mask 1", g.getString("name"))
        val kind = g.getJSONObject("kind")
        assertEquals("Mask", kind.getString("kind"))
        assertEquals("Add", kind.getString("mode"))
        assertFalse(kind.getBoolean("inverted"))
        assertEquals(177, kind.getJSONArray("color").getInt(0))
        assertVertices(
            listOf(listOf(20.0, 30.0), listOf(20.0, 70.0), listOf(80.0, 70.0), listOf(80.0, 30.0)),
            pathVertices(g)
        )
        assertTrue(propOf(g, "path").getJSONObject("value").getJSONObject("v").getBoolean("closed"))
        assertEquals(0.0, propOf(g, "feather").getJSONObject("value").getJSONArray("v").getDouble(0), 0.0)
        assertEquals(100.0, propOf(g, "opacity").getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(0.0, propOf(g, "expansion").getJSONObject("value").getDouble("v"), 0.0)
    }

    @Test
    fun storedMaskValuesAreImported() {
        val shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25))),
            listOf(shap(listOf(0f, 0f, 1f, 1f), rectPoints()))
        )
        val root = rootWithParade(
            tdgp(
                *maskEntry(
                    "Vignette", 2, true, 3,
                    listOf(
                        tdmn("ADBE Mask Shape"), shape,
                        tdmn("ADBE Mask Feather"), staticProp(2, listOf(5.0, 10.0)),
                        tdmn("ADBE Mask Opacity"), staticProp(1, listOf(0.5)),
                        tdmn("ADBE Mask Offset"), staticProp(1, listOf(2.0))
                    )
                ).toTypedArray()
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
        val g = groups[0]
        assertEquals("Subtract", g.getJSONObject("kind").getString("mode"))
        assertTrue(g.getJSONObject("kind").getBoolean("inverted"))
        val feather = propOf(g, "feather").getJSONObject("value").getJSONArray("v")
        assertEquals(5.0, feather.getDouble(0), 0.0)
        assertEquals(10.0, feather.getDouble(1), 0.0)
        assertEquals(50.0, propOf(g, "opacity").getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(2.0, propOf(g, "expansion").getJSONObject("value").getDouble("v"), 0.0)
    }

    @Test
    fun animatedPathAndOpacityKeysImport() {
        val pathProp = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(2, false, 25)),
            keyList(
                lhd3(2, 64),
                pathRecord(0, 1, 2) + pathRecord(25, 2, 3)
            )
        )
        val shape = maskShape(
            pathProp,
            listOf(
                shap(listOf(0f, 0f, 0.5f, 0.5f), rectPoints()),
                shap(listOf(0.5f, 0.5f, 1f, 1f), rectPoints())
            )
        )
        val opacityProp = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(1, false, 25)),
            keyList(
                lhd3(2, 48),
                plainRecord(0, 1, 1, 0, 0.5) + plainRecord(25, 1, 1, 0, 0.25)
            )
        )
        val root = rootWithParade(
            tdgp(
                *maskEntry(
                    "Mask 1", 1, false, 1,
                    listOf(
                        tdmn("ADBE Mask Shape"), shape,
                        tdmn("ADBE Mask Opacity"), opacityProp
                    )
                ).toTypedArray()
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
        val g = groups[0]
        val path = propOf(g, "path")
        val keys = path.getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(0L, keys.getJSONObject(0).getLong("time"))
        assertEquals(254016000000L, keys.getJSONObject(1).getLong("time"))
        // First key's path is the small rectangle (0..50), second key's is (50..100).
        val first = keys.getJSONObject(0).getJSONObject("value").getJSONObject("v")
            .getJSONArray("vertices")
        assertEquals(0.0, first.getJSONArray(0).getDouble(0), 0.0)
        val second = keys.getJSONObject(1).getJSONObject("value").getJSONObject("v")
            .getJSONArray("vertices")
        assertEquals(50.0, second.getJSONArray(0).getDouble(0), 0.0)
        // The static value is the first key's path.
        assertEquals(0.0, pathVertices(g)[0][0], 0.0)
        val opacityKeys = propOf(g, "opacity").getJSONArray("keys")
        assertEquals(2, opacityKeys.length())
        assertEquals(50.0, opacityKeys.getJSONObject(0).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(25.0, opacityKeys.getJSONObject(1).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(4, col.imported.keyframes)
    }

    @Test
    fun unknownModeFallsBackToAdd() {
        val shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25))),
            listOf(shap(listOf(0f, 0f, 1f, 1f), rectPoints()))
        )
        val root = rootWithParade(
            tdgp(*maskEntry("Mask 1", 99, false, 1, listOf(tdmn("ADBE Mask Shape"), shape)).toTypedArray())
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals("Add", groups[0].getJSONObject("kind").getString("mode"))
        val report = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
        assertEquals(1, report.losses.sumOf { it.count })
        assertTrue(report.losses.first().details[0].contains("mode code 99"))
    }

    @Test
    fun maskWithoutPathUsesSourceRectangle() {
        val root = rootWithParade(
            tdgp(*maskEntry("Mask 1", 1, false, 1, emptyList()).toTypedArray())
        )
        val (groups, col) = importRoot(root, size = 1920.0 to 1080.0, hasSource = true)
        assertEquals(1, groups.size)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
        assertVertices(
            listOf(listOf(0.0, 0.0), listOf(0.0, 1080.0), listOf(1920.0, 1080.0), listOf(1920.0, 0.0)),
            pathVertices(groups[0])
        )
    }

    @Test
    fun maskWithoutPathOrSourceIsALoss() {
        val root = rootWithParade(
            tdgp(*maskEntry("Mask 1", 1, false, 1, emptyList()).toTypedArray())
        )
        val (groups, col) = importRoot(root, hasSource = false)
        assertTrue(groups.isEmpty())
        assertEquals(0, col.imported.masks)
        val report = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
        assertEquals(1, report.losses.sumOf { it.count })
        assertEquals(AepImportReport.LossCategory.Masks, report.losses.first().category)
    }

    @Test
    fun openPathIsNotClosed() {
        val shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25))),
            listOf(shap(listOf(0f, 0f, 1f, 1f), rectPoints(), open = true))
        )
        val root = rootWithParade(
            tdgp(*maskEntry("Mask 1", 1, false, 1, listOf(tdmn("ADBE Mask Shape"), shape)).toTypedArray())
        )
        val (groups, _) = importRoot(root)
        assertEquals(1, groups.size)
        assertFalse(propOf(groups[0], "path").getJSONObject("value").getJSONObject("v").getBoolean("closed"))
    }

    @Test
    fun secondMaskGetsSecondColour() {
        val shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25))),
            listOf(shap(listOf(0f, 0f, 1f, 1f), rectPoints()))
        )
        val root = rootWithParade(
            tdgp(
                *(maskEntry("Mask 1", 1, false, 1, listOf(tdmn("ADBE Mask Shape"), shape)) +
                    maskEntry(null, 1, false, 2, listOf(tdmn("ADBE Mask Shape"), shape))).toTypedArray()
            )
        )
        val (groups, _) = importRoot(root)
        assertEquals(2, groups.size)
        assertEquals("Mask 2", groups[1].getString("name"))
        assertEquals(79, groups[1].getJSONObject("kind").getJSONArray("color").getInt(0))
    }
}
