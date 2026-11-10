package com.codeair.effectcraft.data.aep

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Shape layer contents: groups, rect, ellipse, polystar, bezier paths, fill, stroke,
 * gradient fill, trim, round corners and merge paths, with keyframes and expressions. */
class AepShapeImportTest {

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
     * bytes 12..15 the time scale, byte 120 the expression switch. */
    private fun tdb4(dims: Int, spatial: Boolean, timeScale: Long, expr: Boolean = false): ByteArray {
        val b = ByteArray(124)
        b[3] = dims.toByte()
        b[7] = if (spatial) 1 else 0
        u32(timeScale).copyInto(b, 12)
        if (expr) b[120] = 1
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

    private fun tdsb1(): AepContainer.Chunk =
        AepContainer.Chunk.data("tdsb", u32(1))

    private fun keyList(header: ByteArray, ldat: ByteArray): AepContainer.Chunk =
        AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", header),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )

    /** A stored property: `tdsb` 1 + `tdb4` + `cdat` with the given `f64` values,
     * plus an expression when one is given. */
    private fun staticProp(dims: Int, values: List<Double>, expr: String? = null): AepContainer.Chunk {
        val cdat = ByteArray(values.size * 8)
        for ((i, v) in values.withIndex()) f64(v).copyInto(cdat, i * 8)
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(tdsb1())
        kids.add(AepContainer.Chunk.data("tdb4", tdb4(dims, false, 25, expr != null)))
        kids.add(AepContainer.Chunk.data("cdat", cdat))
        if (expr != null) kids.add(AepContainer.Chunk.data("Utf8", expr.toByteArray(Charsets.UTF_8)))
        return tdbs(*kids.toTypedArray())
    }

    /** A plain 1-component keyframe record (48 bytes). */
    private fun plainRecord1(
        time: Int, inInterp: Int, outInterp: Int, value: Double
    ): ByteArray {
        val b = ByteArray(48)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = inInterp.toByte()
        b[5] = outInterp.toByte()
        f64(value).copyInto(b, 8)
        return b
    }

    /** A plain 2-component keyframe record (88 bytes): values at 8 and 16, ease slots at 24. */
    private fun plainRecord2(
        time: Int, inInterp: Int, outInterp: Int, v0: Double, v1: Double
    ): ByteArray {
        val b = ByteArray(88)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = inInterp.toByte()
        b[5] = outInterp.toByte()
        f64(v0).copyInto(b, 8)
        f64(v1).copyInto(b, 16)
        return b
    }

    /** A 64-byte path keyframe record. */
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

    private fun propEntry(name: String, prop: AepContainer.Chunk): List<AepContainer.Chunk> =
        listOf(tdmn(name), prop)

    private fun groupEntry(name: String, group: AepContainer.Chunk): List<AepContainer.Chunk> =
        listOf(tdmn(name), group)

    // ------------------------------------------------------------ shape items

    private fun rectItem(size: List<Double>, pos: List<Double>, roundness: Double): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Rect Size", staticProp(2, size))
        kids += propEntry("ADBE Vector Rect Position", staticProp(2, pos))
        kids += propEntry("ADBE Vector Rect Roundness", staticProp(1, listOf(roundness)))
        return groupEntry("ADBE Vector Shape - Rect", tdgp(*kids.toTypedArray()))
    }

    private fun keyedRectItem(): List<AepContainer.Chunk> {
        val ldat = ByteArray(88 * 2)
        plainRecord2(0, 1, 1, 100.0, 50.0).copyInto(ldat, 0)
        plainRecord2(25, 1, 1, 200.0, 150.0).copyInto(ldat, 88)
        val keys = tdbs(
            tdsb1(),
            AepContainer.Chunk.data("tdb4", tdb4(2, false, 25)),
            keyList(lhd3(2, 88), ldat)
        )
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Rect Size", keys)
        kids += propEntry("ADBE Vector Rect Position", staticProp(2, listOf(0.0, 0.0)))
        kids += propEntry("ADBE Vector Rect Roundness", staticProp(1, listOf(0.0)))
        return groupEntry("ADBE Vector Shape - Rect", tdgp(*kids.toTypedArray()))
    }

    private fun ellipseItem(size: List<Double>, pos: List<Double>): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Ellipse Size", staticProp(2, size))
        kids += propEntry("ADBE Vector Ellipse Position", staticProp(2, pos))
        return groupEntry("ADBE Vector Shape - Ellipse", tdgp(*kids.toTypedArray()))
    }

    private fun fillItem(argb: List<Double>, opacity: Double, blend: Double? = null): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Fill Color", staticProp(4, argb))
        kids += propEntry("ADBE Vector Fill Opacity", staticProp(1, listOf(opacity)))
        kids += propEntry("ADBE Vector Fill Rule", staticProp(1, listOf(1.0)))
        if (blend != null) kids += propEntry("ADBE Vector Blend Mode", staticProp(1, listOf(blend)))
        return groupEntry("ADBE Vector Graphic - Fill", tdgp(*kids.toTypedArray()))
    }

    private fun strokeItem(argb: List<Double>, width: Double, widthExpr: String? = null): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Stroke Color", staticProp(4, argb))
        kids += propEntry("ADBE Vector Stroke Opacity", staticProp(1, listOf(100.0)))
        kids += propEntry("ADBE Vector Stroke Width", staticProp(1, listOf(width), widthExpr))
        return groupEntry("ADBE Vector Graphic - Stroke", tdgp(*kids.toTypedArray()))
    }

    private fun gradFillItem(): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Grad Type", staticProp(1, listOf(1.0)))
        kids += propEntry("ADBE Vector Grad Start Pt", staticProp(2, listOf(0.0, 0.0)))
        kids += propEntry("ADBE Vector Grad End Pt", staticProp(2, listOf(100.0, 0.0)))
        // The colour stops themselves: present, so the loss is reported.
        kids += propEntry("ADBE Vector Grad Colors", staticProp(1, listOf(0.0)))
        return groupEntry("ADBE Vector Graphic - G-Fill", tdgp(*kids.toTypedArray()))
    }

    private fun trimItem(start: Double, end: Double): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Trim Start", staticProp(1, listOf(start)))
        kids += propEntry("ADBE Vector Trim End", staticProp(1, listOf(end)))
        return groupEntry("ADBE Vector Filter - Trim", tdgp(*kids.toTypedArray()))
    }

    private fun roundItem(radius: Double): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector RoundCorner Radius", staticProp(1, listOf(radius)))
        return groupEntry("ADBE Vector Filter - RC", tdgp(*kids.toTypedArray()))
    }

    private fun starItem(type: Double, points: Double, pos: List<Double>, outer: Double, inner: Double): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        kids += propEntry("ADBE Vector Star Type", staticProp(1, listOf(type)))
        kids += propEntry("ADBE Vector Star Points", staticProp(1, listOf(points)))
        kids += propEntry("ADBE Vector Star Position", staticProp(2, pos))
        kids += propEntry("ADBE Vector Star Outer Radius", staticProp(1, listOf(outer)))
        kids += propEntry("ADBE Vector Star Inner Radius", staticProp(1, listOf(inner)))
        return groupEntry("ADBE Vector Shape - Star", tdgp(*kids.toTypedArray()))
    }

    /** A bezier path item: `om-s` holding the path property and the `omks` shapes. */
    private fun pathItem(
        shapes: List<AepContainer.Chunk>,
        keyed: Boolean = false
    ): List<AepContainer.Chunk> {
        val pathProp = if (!keyed) {
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, 25)))
        } else {
            val ldat = ByteArray(64 * 2)
            pathRecord(0, 1, 1).copyInto(ldat, 0)
            pathRecord(25, 1, 1).copyInto(ldat, 64)
            tdbs(
                tdsb1(),
                AepContainer.Chunk.data("tdb4", tdb4(2, false, 25)),
                keyList(lhd3(2, 64), ldat)
            )
        }
        val oms = AepContainer.Chunk.list(
            "om-s",
            listOf(pathProp, AepContainer.Chunk.list("omks", shapes))
        )
        val kids = listOf(tdmn("ADBE Vector Shape"), oms)
        return groupEntry("ADBE Vector Shape - Group", tdgp(*kids.toTypedArray()))
    }

    private fun vectorGroup(
        name: String?,
        contents: List<List<AepContainer.Chunk>>,
        transform: List<List<AepContainer.Chunk>>? = null
    ): List<AepContainer.Chunk> {
        val kids = ArrayList<AepContainer.Chunk>()
        if (name != null) kids.add(tdsn(name))
        val contentKids = ArrayList<AepContainer.Chunk>()
        for (item in contents) contentKids.addAll(item)
        kids += groupEntry("ADBE Vectors Group", tdgp(*contentKids.toTypedArray()))
        if (transform != null) {
            val tKids = ArrayList<AepContainer.Chunk>()
            for (entry in transform) tKids.addAll(entry)
            kids += groupEntry("ADBE Vector Transform Group", tdgp(*tKids.toTypedArray()))
        }
        return groupEntry("ADBE Vector Group", tdgp(*kids.toTypedArray()))
    }

    private fun unknownItem(): List<AepContainer.Chunk> =
        groupEntry("ADBE Vector Filter - Wiggle", tdgp())

    private fun rootWith(items: List<List<AepContainer.Chunk>>): AepTypedView.PropGroup {
        val kids = ArrayList<AepContainer.Chunk>()
        for (item in items) kids.addAll(item)
        return AepTypedView.PropGroup.fromChunk(
            tdgp(tdmn("ADBE Root Vectors Group"), tdgp(*kids.toTypedArray()))
        )!!
    }

    private fun importRoot(
        root: AepTypedView.PropGroup
    ): Pair<List<JSONObject>, AepImportReport.Collector> {
        val col = AepImportReport.Collector()
        var nextUid = 1L
        val groups = AepShapeImport.importShapes(
            root, "shape layer 'Test'", 25,
            { n -> val base = nextUid; nextUid += n; base },
            col
        )
        return groups to col
    }


    private fun lossCount(col: AepImportReport.Collector): Int =
        col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
            .losses.sumOf { it.count }

    private fun firstLossDetail(col: AepImportReport.Collector): String =
        col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
            .losses.first().details[0]

    private fun propOf(group: JSONObject, match: String): JSONObject {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no prop $match in ${group.optString("match")}")
    }

    private fun groupOf(group: JSONObject, match: String): JSONObject {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no group $match in ${group.optString("match")}")
    }

    private fun vec2(prop: JSONObject): List<Double> {
        val v = prop.getJSONObject("value").getJSONArray("v")
        return listOf(v.getDouble(0), v.getDouble(1))
    }

    private fun scalar(prop: JSONObject): Double =
        prop.getJSONObject("value").getDouble("v")

    private fun color(prop: JSONObject): List<Double> {
        val v = prop.getJSONObject("value").getJSONArray("v")
        return (0 until v.length()).map { v.getDouble(it) }
    }

    private fun pathVertices(group: JSONObject): List<List<Double>> {
        val v = propOf(group, "path").getJSONObject("value").getJSONObject("v")
            .getJSONArray("vertices")
        return (0 until v.length()).map { i ->
            val p = v.getJSONArray(i)
            listOf(p.getDouble(0), p.getDouble(1))
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun rectFillStrokeImportStaticValues() {
        val root = rootWith(
            listOf(
                vectorGroup(
                    "My Shape",
                    listOf(
                        rectItem(listOf(200.0, 100.0), listOf(10.0, 20.0), 5.0),
                        fillItem(listOf(255.0, 255.0, 0.0, 0.0), 80.0),
                        strokeItem(listOf(255.0, 0.0, 0.0, 255.0), 3.0)
                    )
                )
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        assertEquals(4, col.imported.shapeItems)
        val group = groups[0]
        assertEquals("group", group.getString("match"))
        assertEquals("My Shape", group.getString("name"))
        val contents = groupOf(group, "contents")
        assertEquals(3, contents.getJSONArray("children").length())
        val rect = contents.getJSONArray("children").getJSONObject(0)
        assertEquals("rect", rect.getString("match"))
        assertEquals(listOf(200.0, 100.0), vec2(propOf(rect, "size")))
        assertEquals(listOf(10.0, 20.0), vec2(propOf(rect, "position")))
        assertEquals(5.0, scalar(propOf(rect, "roundness")), 0.0)
        // Per-corner radii are built from the uniform Roundness at build time (0.0 here);
        // the import only sets the uniform roundness, as the official importer does.
        assertEquals(0.0, scalar(propOf(rect, "topLeftRoundness")), 0.0)
        val fill = contents.getJSONArray("children").getJSONObject(1)
        assertEquals("fill", fill.getString("match"))
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), color(propOf(fill, "color")))
        assertEquals(80.0, scalar(propOf(fill, "opacity")), 0.0)
        val stroke = contents.getJSONArray("children").getJSONObject(2)
        assertEquals("stroke", stroke.getString("match"))
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0), color(propOf(stroke, "color")))
        assertEquals(3.0, scalar(propOf(stroke, "width")), 0.0)
    }

    @Test
    fun ellipseImports() {
        val root = rootWith(
            listOf(vectorGroup(null, listOf(ellipseItem(listOf(60.0, 40.0), listOf(5.0, 5.0)))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        val ellipse = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        assertEquals("ellipse", ellipse.getString("match"))
        assertEquals(listOf(60.0, 40.0), vec2(propOf(ellipse, "size")))
        assertEquals(listOf(5.0, 5.0), vec2(propOf(ellipse, "position")))
    }

    @Test
    fun keyedRectSizeImportsKeys() {
        val root = rootWith(listOf(vectorGroup(null, listOf(keyedRectItem()))))
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        assertEquals(2, col.imported.keyframes)
        val rect = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        val size = propOf(rect, "size")
        assertEquals(listOf(100.0, 50.0), vec2(size))
        val keys = size.getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(0L, keys.getJSONObject(0).getLong("time"))
        assertEquals(254016000000L, keys.getJSONObject(1).getLong("time"))
        val keyValue = keys.getJSONObject(1).getJSONObject("value").getJSONArray("v")
        assertEquals(200.0, keyValue.getDouble(0), 0.0)
        assertEquals(150.0, keyValue.getDouble(1), 0.0)
    }

    @Test
    fun bezierPathImportsStatic() {
        val box = listOf(10f, 20f, 110f, 120f)
        val root = rootWith(
            listOf(vectorGroup(null, listOf(pathItem(listOf(shap(box, rectPoints()))))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        val path = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        assertEquals("path", path.getString("match"))
        val verts = pathVertices(path)
        assertEquals(4, verts.size)
        assertEquals(10.0, verts[0][0], 1e-4)
        assertEquals(20.0, verts[0][1], 1e-4)
        assertEquals(10.0, verts[1][0], 1e-4)
        assertEquals(120.0, verts[1][1], 1e-4)
        assertEquals(110.0, verts[2][0], 1e-4)
        assertEquals(120.0, verts[2][1], 1e-4)
        assertEquals(110.0, verts[3][0], 1e-4)
        assertEquals(20.0, verts[3][1], 1e-4)
        assertTrue(propOf(path, "path").getJSONObject("value").getJSONObject("v").getBoolean("closed"))
    }

    @Test
    fun pathKeysImport() {
        val box = listOf(10f, 20f, 110f, 120f)
        val box2 = listOf(15f, 25f, 115f, 125f)
        val root = rootWith(
            listOf(
                vectorGroup(
                    null,
                    listOf(
                        pathItem(
                            listOf(shap(box, rectPoints()), shap(box2, rectPoints())),
                            keyed = true
                        )
                    )
                )
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        assertEquals(2, col.imported.keyframes)
        val path = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        val keys = propOf(path, "path").getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(254016000000L, keys.getJSONObject(1).getLong("time"))
    }

    @Test
    fun gradientFillUsesDefaultGradientAndReportsStops() {
        val root = rootWith(listOf(vectorGroup(null, listOf(gradFillItem()))))
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(1, lossCount(col))
        assertTrue(firstLossDetail(col).contains("colour stops"))
        val gfill = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        assertEquals("gfill", gfill.getString("match"))
        val colors = propOf(gfill, "colors").getJSONObject("value")
        assertEquals("Gradient", colors.getString("t"))
        val stops = colors.getJSONObject("v").getJSONArray("colors")
        assertEquals(2, stops.length())
        assertEquals(0.0, stops.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(1.0, stops.getJSONArray(1).getDouble(0), 0.0)
    }

    @Test
    fun unknownItemIsALoss() {
        val root = rootWith(listOf(vectorGroup(null, listOf(unknownItem()))))
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(1, lossCount(col))
        assertTrue(firstLossDetail(col).contains("Wiggle"))
        val contents = groupOf(groups[0], "contents")
        assertEquals(0, contents.getJSONArray("children").length())
    }

    @Test
    fun nestedGroupWithTransform() {
        val root = rootWith(
            listOf(
                vectorGroup(
                    "Outer",
                    listOf(
                        vectorGroup(
                            "Inner",
                            listOf(rectItem(listOf(50.0, 50.0), listOf(0.0, 0.0), 0.0))
                        )
                    ),
                    transform = listOf(
                        propEntry("ADBE Vector Position", staticProp(2, listOf(5.0, 5.0))),
                        propEntry("ADBE Vector Scale", staticProp(2, listOf(50.0, 50.0)))
                    )
                )
            )
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        val outer = groups[0]
        assertEquals("Outer", outer.getString("name"))
        val transform = groupOf(outer, "transform")
        assertEquals(listOf(5.0, 5.0), vec2(propOf(transform, "position")))
        assertEquals(listOf(50.0, 50.0), vec2(propOf(transform, "scale")))
        val inner = groupOf(outer, "contents").getJSONArray("children").getJSONObject(0)
        assertEquals("group", inner.getString("match"))
        assertEquals("Inner", inner.getString("name"))
    }

    @Test
    fun noRootVectorsGroupIsEmpty() {
        val root = AepTypedView.PropGroup.fromChunk(tdgp(tdmn("ADBE Transform Group"), tdgp()))!!
        val (groups, col) = importRoot(root)
        assertEquals(0, groups.size)
        assertEquals(0, lossCount(col))
    }

    @Test
    fun trimAndRoundCornersImport() {
        val root = rootWith(
            listOf(vectorGroup(null, listOf(trimItem(10.0, 90.0), roundItem(12.0))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        val children = groupOf(groups[0], "contents").getJSONArray("children")
        val trim = children.getJSONObject(0)
        assertEquals("trim", trim.getString("match"))
        assertEquals(10.0, scalar(propOf(trim, "start")), 0.0)
        assertEquals(90.0, scalar(propOf(trim, "end")), 0.0)
        val round = children.getJSONObject(1)
        assertEquals("round", round.getString("match"))
        assertEquals(12.0, scalar(propOf(round, "radius")), 0.0)
    }

    @Test
    fun starImports() {
        val root = rootWith(
            listOf(vectorGroup(null, listOf(starItem(1.0, 5.0, listOf(0.0, 0.0), 100.0, 50.0))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        val star = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        assertEquals("star", star.getString("match"))
        assertEquals(0, propOf(star, "type").getJSONObject("value").getInt("v"))
        assertEquals(5.0, scalar(propOf(star, "points")), 0.0)
        assertEquals(100.0, scalar(propOf(star, "outerRadius")), 0.0)
        assertEquals(50.0, scalar(propOf(star, "innerRadius")), 0.0)
    }

    @Test
    fun expressionOnStrokeWidthAttaches() {
        val root = rootWith(
            listOf(vectorGroup(null, listOf(strokeItem(listOf(255.0, 0.0, 0.0, 0.0), 2.0, "time * 2"))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(0, lossCount(col))
        assertEquals(1, col.imported.expressions)
        val stroke = groupOf(groups[0], "contents").getJSONArray("children").getJSONObject(0)
        val width = propOf(stroke, "width")
        assertEquals("time * 2", width.getJSONObject("expr").getString("text"))
        assertTrue(width.getJSONObject("expr").getBoolean("enabled"))
    }

    @Test
    fun nonDefaultFillBlendModeIsALoss() {
        val root = rootWith(
            listOf(vectorGroup(null, listOf(fillItem(listOf(255.0, 255.0, 255.0, 255.0), 100.0, blend = 2.0))))
        )
        val (groups, col) = importRoot(root)
        assertEquals(1, groups.size)
        assertEquals(1, lossCount(col))
        assertTrue(firstLossDetail(col).contains("paint blend mode"))
    }
}
