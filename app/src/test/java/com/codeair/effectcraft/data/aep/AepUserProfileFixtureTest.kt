package com.codeair.effectcraft.data.aep

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's real-file profile, rebuilt as a synthetic `.aep`: a null "Control Layer"
 * carrying Color Control effects (one with keyframes, one static) plus a Slider
 * Control with an expression, a "Vignette" solid with two masks, layer markers,
 * keyed time remapping and an expression on Opacity, and a "Title" text layer
 * ("Hello World" in two runs). The fixture is the shipped
 * `synthetic.aep` cloned and mutated, so the binary path (RIFX parse, typed view,
 * converter, patch) is exercised end to end, and the loss count for exactly the
 * profile the user imported must drop to the non-content remainder.
 *
 * Comp: 64x48 at 25 fps (timescale 25600), 1 second. Solid: 64x48.
 */
class AepUserProfileFixtureTest {

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

    private fun beU32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    /** A 124-byte `tdb4`: byte 3 is the component count, byte 7 the spatial flag,
     * bytes 12..15 the time scale. */
    private fun tdb4(dims: Int, spatial: Boolean, timeScale: Long): ByteArray {
        val b = ByteArray(124)
        b[3] = dims.toByte()
        b[7] = if (spatial) 1 else 0
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

    private fun tdsb(flags: Long): AepContainer.Chunk =
        AepContainer.Chunk.data("tdsb", u32(flags))

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
            AepContainer.Chunk.data("tdb4", tdb4(dims, false, TIME_SCALE)),
            AepContainer.Chunk.data("cdat", cdat)
        )
    }

    /** A plain 1-component keyframe record (48 bytes). */
    private fun plainRecord(time: Int, value: Double): ByteArray {
        val b = ByteArray(48)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = 1 // linear in
        b[5] = 1 // linear out
        f64(value).copyInto(b, 8)
        return b
    }

    /** A plain 4-component (color) keyframe record: `8 + 40 * 4` bytes, the four
     * values as consecutive `f64` at bytes 8..40 (type-major), ease slots after.
     * `argb` is in ARGB order. */
    private fun colorRecord(time: Int, argb: List<Double>): ByteArray {
        val b = ByteArray(8 + 40 * 4)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = 1
        b[5] = 1
        for ((i, v) in argb.withIndex()) f64(v).copyInto(b, 8 + 8 * i)
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

    /** A 148-byte `pard`: kind at 12, name at 16 (32 bytes), default bytes at 56. */
    private fun pard(kind: Long, name: String, default: ByteArray = byteArrayOf()): ByteArray {
        val bytes = ByteArray(148)
        u32(kind).copyInto(bytes, 12)
        name.toByteArray(Charsets.US_ASCII).copyInto(bytes, 16)
        default.copyInto(bytes, 56)
        return bytes
    }

    // ------------------------------------------------------------------ effects

    /** A Color Control instance: `parT` (input layer + color) and a `tdgp` whose
     * color value is either keyed (two records) or static. */
    private fun colorControlSspc(instanceName: String, keyed: Boolean): AepContainer.Chunk {
        val redDefault = byteArrayOf(255.toByte(), 255.toByte(), 0, 0) // opaque red
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Color Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Color Control-0001"),
                AepContainer.Chunk.data("pard", pard(5, "Color", redDefault))
            )
        )
        val valueProp = if (keyed) {
            tdbs(
                tdsb(1),
                AepContainer.Chunk.data("tdb4", tdb4(4, false, TIME_SCALE)),
                keyList(
                    lhd3(2, 168),
                    colorRecord(0, listOf(255.0, 255.0, 0.0, 0.0)) +
                        colorRecord(TIME_SCALE.toInt(), listOf(255.0, 0.0, 0.0, 255.0))
                )
            )
        } else {
            // A stored value is only read when the `tdsb` flags say a value is
            // recorded (1 or 3); without it the value is unreadable. ARGB order:
            // opaque green.
            val cdat = ByteArray(4 * 8)
            for ((i, v) in listOf(255.0, 0.0, 255.0, 0.0).withIndex()) f64(v).copyInto(cdat, i * 8)
            tdbs(
                tdsb(1),
                AepContainer.Chunk.data("tdb4", tdb4(4, false, TIME_SCALE)),
                AepContainer.Chunk.data("cdat", cdat)
            )
        }
        // The value group's `tdsb` is the effect's fx switch (1 = on).
        val values = tdgp(tdsb(1), tdmn("ADBE Color Control-0001"), valueProp)
        return AepContainer.Chunk.list(
            "sspc",
            listOf(
                AepContainer.Chunk.data("fnam", instanceName.toByteArray(Charsets.US_ASCII)),
                part,
                values
            )
        )
    }

    /** A Slider Control instance with a static value and an expression. */
    private fun sliderControlSspc(instanceName: String): AepContainer.Chunk {
        val sliderPard = pard(10, "Slider", f64(0.0))
        // The slider's valid maximum is 1,000,000 (the ec.control.slider spec);
        // a file maximum that does not match is not guessed at.
        f32(1_000_000f).copyInto(sliderPard, 108)
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Slider Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Slider Control-0001"),
                AepContainer.Chunk.data("pard", sliderPard)
            )
        )
        val cdat = f64(50.0)
        val sliderHeader = tdb4(1, false, TIME_SCALE)
        sliderHeader[120] = 1 // the expression is enabled
        val valueProp = tdbs(
            tdsb(1),
            AepContainer.Chunk.data("tdb4", sliderHeader),
            AepContainer.Chunk.data("cdat", cdat),
            AepContainer.Chunk.data("Utf8", "time * 10".toByteArray(Charsets.UTF_8))
        )
        // The value group's `tdsb` is the effect's fx switch (1 = on).
        val values = tdgp(tdsb(1), tdmn("ADBE Slider Control-0001"), valueProp)
        return AepContainer.Chunk.list(
            "sspc",
            listOf(
                AepContainer.Chunk.data("fnam", instanceName.toByteArray(Charsets.US_ASCII)),
                part,
                values
            )
        )
    }

    /** An Effect Parade group holding the Control Layer's three effects. */
    private fun controlEffectParade(head: List<AepContainer.Chunk>): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        kids.addAll(head)
        kids.add(tdmn("ADBE Color Control"))
        kids.add(colorControlSspc("Color Control", keyed = true))
        kids.add(tdmn("ADBE Color Control"))
        kids.add(colorControlSspc("Color Control 2", keyed = false))
        kids.add(tdmn("ADBE Slider Control"))
        kids.add(sliderControlSspc("Slider Control"))
        kids.add(tdmn("ADBE Group End"))
        return tdgp(*kids.toTypedArray())
    }

    // ------------------------------------------------------------------ masks

    /** A `LIST shap`: box + normalised points (three per vertex, last = in-handle of vertex 0). */
    private fun shap(box: List<Float>, points: List<Pair<Float, Float>>): AepContainer.Chunk {
        val shph = ByteArray(21)
        shph[3] = 0x01
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
    private fun maskShape(path: AepContainer.Chunk, shapes: List<AepContainer.Chunk>): AepContainer.Chunk {
        val children = ArrayList<AepContainer.Chunk>()
        children.add(path)
        children.add(AepContainer.Chunk.list("omks", shapes))
        return AepContainer.Chunk.list("om-s", children)
    }

    /** One mask atom: `tdmn` + `mkif` + `tdgp` atom, as they sit in the parade. */
    private fun maskEntry(
        name: String,
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
        atomKids.add(tdsn(name))
        atomKids.addAll(atomChildren)
        return listOf(
            tdmn("ADBE Mask Atom"),
            AepContainer.Chunk.data("mkif", mkif),
            tdgp(*atomKids.toTypedArray())
        )
    }

    /** The Mask Parade for the Vignette layer: one static mask with stored
     * feather/opacity/expansion, one animated-path mask (Subtract, inverted). */
    private fun vignetteMaskParade(): AepContainer.Chunk {
        val mask1Shape = maskShape(
            tdbs(AepContainer.Chunk.data("tdb4", tdb4(2, false, TIME_SCALE))),
            listOf(shap(listOf(0.2f, 0.3f, 0.8f, 0.7f), rectPoints()))
        )
        val mask1 = maskEntry(
            "Mask 1", 1, false, 1,
            listOf(
                tdmn("ADBE Mask Shape"), mask1Shape,
                tdmn("ADBE Mask Feather"), staticProp(2, listOf(5.0, 10.0)),
                tdmn("ADBE Mask Opacity"), staticProp(1, listOf(0.5)),
                tdmn("ADBE Mask Offset"), staticProp(1, listOf(2.0))
            )
        )
        val pathProp = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(2, false, TIME_SCALE)),
            keyList(
                lhd3(2, 64),
                pathRecord(0, 1, 2) + pathRecord(TIME_SCALE.toInt(), 2, 3)
            )
        )
        val mask2Shape = maskShape(
            pathProp,
            listOf(
                shap(listOf(0f, 0f, 0.5f, 0.5f), rectPoints()),
                shap(listOf(0.5f, 0.5f, 1f, 1f), rectPoints())
            )
        )
        val mask2 = maskEntry(
            "Vignette", 2, true, 2,
            listOf(tdmn("ADBE Mask Shape"), mask2Shape)
        )
        return tdgp(*(mask1 + mask2).toTypedArray())
    }

    /** A PostScript string: `(` + BOM + UTF-16BE + `)`, with `\`, `(`, `)` escaped. */
    private fun utf16Str(s: String): ByteArray {
        val raw = ByteArray(2 + s.length * 2)
        raw[0] = 0xFE.toByte()
        raw[1] = 0xFF.toByte()
        s.forEachIndexed { i, c ->
            raw[2 + i * 2] = (c.code shr 8).toByte()
            raw[2 + i * 2 + 1] = c.code.toByte()
        }
        val out = ByteArrayOutputStream()
        out.write('('.code)
        for (b in raw) {
            if (b == '\\'.code.toByte() || b == '('.code.toByte() || b == ')'.code.toByte()) {
                out.write('\\'.code)
            }
            out.write(b.toInt())
        }
        out.write(')'.code)
        return out.toByteArray()
    }

    /** The `btdk` body for the Title layer: "Hello World" in two runs
     * ("Hello " red 72, "World" blue 48), one left-justified paragraph. */
    private fun titleBtdk(): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(bytes: ByteArray) = out.write(bytes)
        fun write(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        write("/0 << /1 << /0 [ << /0 << /0 << /0 ")
        write(utf16Str("MyriadPro-Regular"))
        write(" >> >> >> ] >>")
        write(" >> /1 << /1 [ << /0 << /0 ")
        write(utf16Str("Hello World\r"))
        write(" /5 << /0 [ << /0 << /0 << /5 << /0 0 >> >> >> /1 12 >> ] >>")
        write(" /6 << /0 [ ")
        write("<< /0 << /0 << /6 << /0 0 /1 72 /53 << /0 << /1 [ 1 1 0 0 ] >> >> >> >> >> /1 6 >> ")
        write("<< /0 << /0 << /6 << /0 0 /1 48 /53 << /0 << /1 [ 1 0 0 1 ] >> >> >> >> >> /1 6 >> ")
        write("] >> >> >> ] >>")
        return out.toByteArray()
    }

    /** The "ADBE Text Properties" group: the Source Text placeholder record
     * (`btds`, no keys, no expression) and the `btdk` text document. */
    private fun textPropertiesGroup(): AepContainer.Chunk {
        val sourceText = AepContainer.Chunk.list(
            "btds",
            listOf(
                tdbs(
                    tdsb(3),
                    AepContainer.Chunk.data("tdb4", tdb4(1, false, TIME_SCALE)),
                    AepContainer.Chunk.data("cdat", f64(0.0))
                )
            )
        )
        val body = titleBtdk()
        val btdk = AepContainer.Chunk(
            AepContainer.LIST,
            AepContainer.ChunkBody.OpaqueList("btdk", body),
            if ((4 + body.size) % 2 == 1) 0 else null
        )
        return tdgp(tdmn("ADBE Text Document"), sourceText, btdk)
    }

    // ------------------------------------------------------------------ markers

    /** The shape contents of the fixture's shape layer: one group with a rectangle,
     * a red fill and a blue stroke, plus a group transform position. */
    private fun shapeVectorsGroup(): AepContainer.Chunk {
        fun entry(name: String, prop: AepContainer.Chunk): List<AepContainer.Chunk> =
            listOf(tdmn(name), prop)
        val rectKids = ArrayList<AepContainer.Chunk>()
        rectKids += entry("ADBE Vector Rect Size", staticProp(2, listOf(200.0, 100.0)))
        rectKids += entry("ADBE Vector Rect Position", staticProp(2, listOf(10.0, 20.0)))
        rectKids += entry("ADBE Vector Rect Roundness", staticProp(1, listOf(5.0)))
        val fillKids = ArrayList<AepContainer.Chunk>()
        fillKids += entry("ADBE Vector Fill Color", staticProp(4, listOf(255.0, 255.0, 0.0, 0.0)))
        fillKids += entry("ADBE Vector Fill Opacity", staticProp(1, listOf(80.0)))
        fillKids += entry("ADBE Vector Fill Rule", staticProp(1, listOf(1.0)))
        val strokeKids = ArrayList<AepContainer.Chunk>()
        strokeKids += entry("ADBE Vector Stroke Color", staticProp(4, listOf(255.0, 0.0, 0.0, 255.0)))
        strokeKids += entry("ADBE Vector Stroke Opacity", staticProp(1, listOf(100.0)))
        strokeKids += entry("ADBE Vector Stroke Width", staticProp(1, listOf(3.0)))
        val contentsKids = ArrayList<AepContainer.Chunk>()
        contentsKids += listOf(tdmn("ADBE Vector Shape - Rect"), tdgp(*rectKids.toTypedArray()))
        contentsKids += listOf(tdmn("ADBE Vector Graphic - Fill"), tdgp(*fillKids.toTypedArray()))
        contentsKids += listOf(tdmn("ADBE Vector Graphic - Stroke"), tdgp(*strokeKids.toTypedArray()))
        val transformKids = ArrayList<AepContainer.Chunk>()
        transformKids += entry("ADBE Vector Position", staticProp(2, listOf(5.0, 5.0)))
        val groupKids = ArrayList<AepContainer.Chunk>()
        groupKids.add(tdsn("Shapes 1"))
        groupKids += listOf(tdmn("ADBE Vectors Group"), tdgp(*contentsKids.toTypedArray()))
        groupKids += listOf(tdmn("ADBE Vector Transform Group"), tdgp(*transformKids.toTypedArray()))
        return tdgp(
            tdmn("ADBE Vector Group"),
            tdgp(*groupKids.toTypedArray())
        )
    }

    private fun markerRecord(time: Int): ByteArray {
        val b = ByteArray(16)
        u32(time.toLong()).copyInto(b, 0)
        return b
    }

    /** A `LIST Nmrd`: the five strings, then the `NmHd` header. */
    private fun nmrd(comment: String, chapter: String = "", label: Int = 0): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        for (s in listOf(comment, chapter, "", "", "")) {
            kids.add(AepContainer.Chunk.data("Utf8", s.toByteArray(Charsets.UTF_8)))
        }
        val header = ByteArray(20)
        u32(4).copyInto(header, 0) // event cue
        u32(0).copyInto(header, 4) // no cue param pairs
        u32(0).copyInto(header, 8) // no duration
        u32(600).copyInto(header, 12)
        header[16] = label.toByte()
        kids.add(AepContainer.Chunk.data("NmHd", header))
        return AepContainer.Chunk.list("Nmrd", kids)
    }

    /** An `mrst` list: a `tdbs` with 16-byte key records + the `Nmrd` details. */
    private fun mrst(times: List<Int>, details: List<AepContainer.Chunk>): AepContainer.Chunk {
        val ldat = ByteArray(times.size * 16)
        for ((i, t) in times.withIndex()) markerRecord(t).copyInto(ldat, i * 16)
        val prop = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(1, false, TIME_SCALE)),
            keyList(lhd3(times.size.toLong(), 16), ldat)
        )
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(prop)
        kids.addAll(details)
        return AepContainer.Chunk.list("mrst", kids)
    }

    // ------------------------------------------------------------------ tree surgery

    private fun deepCopy(c: AepContainer.Chunk): AepContainer.Chunk = when (val b = c.body) {
        is AepContainer.ChunkBody.Data ->
            AepContainer.Chunk.data(c.id, b.bytes.copyOf())
        is AepContainer.ChunkBody.Chunks ->
            AepContainer.Chunk(c.id, AepContainer.ChunkBody.Chunks(b.children.map(::deepCopy)), c.pad)
        is AepContainer.ChunkBody.List ->
            AepContainer.Chunk.list(b.listType, b.children.map(::deepCopy))
        is AepContainer.ChunkBody.OpaqueList ->
            AepContainer.Chunk(c.id, AepContainer.ChunkBody.OpaqueList(b.listType, b.data.copyOf()), c.pad)
    }

    /** Rebuilds a list chunk with new children, preserving its id and list type. */
    private fun withChildren(c: AepContainer.Chunk, kids: List<AepContainer.Chunk>): AepContainer.Chunk =
        when (val b = c.body) {
            is AepContainer.ChunkBody.List -> AepContainer.Chunk.list(b.listType, kids)
            is AepContainer.ChunkBody.Chunks ->
                AepContainer.Chunk(c.id, AepContainer.ChunkBody.Chunks(kids), c.pad)
            else -> c
        }

    /** The layer id from its `ldta` (u32 at 0). */
    private fun layerId(layr: AepContainer.Chunk): Long =
        layr.child("ldta")?.bytes()?.let { beU32(it, 0) } ?: -1

    /** The root property group (`tdgp`) of a layer, with new children. */
    private fun withRootProps(
        layr: AepContainer.Chunk,
        edit: (List<AepContainer.Chunk>) -> List<AepContainer.Chunk>
    ): AepContainer.Chunk {
        val kids = layr.children().map { child ->
            if (child.isList("tdgp")) withChildren(child, edit(child.children())) else child
        }
        return withChildren(layr, kids)
    }

    /** The `tdmn` text (a 40-byte NUL-padded name, or a bare name in a built chunk). */
    private fun tdmnText(chunk: AepContainer.Chunk): String =
        chunk.bytes()?.let { String(it, Charsets.US_ASCII).trimEnd('\u0000') } ?: ""

    /** A keyed Time Remapping `tdbs`: 0 s at t=0, 1 s at t=1 s. */
    private fun keyedRemapTdbs(): AepContainer.Chunk =
        tdbs(
            tdsb(1),
            AepContainer.Chunk.data("tdb4", tdb4(1, false, TIME_SCALE)),
            keyList(
                lhd3(2, 48),
                plainRecord(0, 0.0) + plainRecord(TIME_SCALE.toInt(), 1.0)
            )
        )

    /** Adds a `wiggle(1, 10)` expression to the Opacity property of a transform
     * group, appending the Opacity entry itself when the group has none (this
     * fixture's transform group holds only the 3D entries). The expression only
     * counts when `tdb4` byte 120 marks it enabled. */
    private fun withOpacityExpression(transform: AepContainer.Chunk): AepContainer.Chunk {
        val kids = transform.children()
        val idx = kids.indexOfFirst { it.`is`("tdmn") && tdmnText(it) == "ADBE Opacity" }
        if (idx >= 0 && idx + 1 < kids.size) {
            val prop = kids[idx + 1]
            val propKids = prop.children().map { propChild ->
                if (propChild.`is`("tdb4")) {
                    val header = propChild.bytes()!!.copyOf()
                    header[120] = 1
                    AepContainer.Chunk.data("tdb4", header)
                } else propChild
            }
            val withExpr = withChildren(
                prop,
                propKids + AepContainer.Chunk.data(
                    "Utf8", "wiggle(1, 10)".toByteArray(Charsets.UTF_8)
                )
            )
            val out = kids.toMutableList()
            out[idx + 1] = withExpr
            return withChildren(transform, out)
        }
        val header = tdb4(1, false, TIME_SCALE)
        header[120] = 1
        val opacityProp = tdbs(
            tdsb(1),
            AepContainer.Chunk.data("tdb4", header),
            AepContainer.Chunk.data("cdat", f64(100.0)),
            AepContainer.Chunk.data("Utf8", "wiggle(1, 10)".toByteArray(Charsets.UTF_8))
        )
        val out = kids.toMutableList()
        val endIdx = out.indexOfFirst { it.`is`("tdmn") && tdmnText(it) == "ADBE Group End" }
        val entry = listOf(tdmn("ADBE Opacity"), opacityProp)
        if (endIdx >= 0) out.addAll(endIdx, entry) else out.addAll(entry)
        return withChildren(transform, out)
    }

    /** The user-profile `.aep` bytes: `synthetic.aep` cloned and mutated. */
    internal fun userProfileBytes(): ByteArray {
        val file = AepContainer.parse(AepContainerTest.resourceBytes("/aep/synthetic.aep"))
        val chunks = file.chunks.map(::deepCopy)
        val fold = chunks.first { it.isList("Fold") }
        val compItem = fold.children().first { it.isList("Item") && it.child("cdta") != null }
        val layers = compItem.children().filter { it.isList("Layr") }
        val solidLayer = layers.first { layerId(it) == 16L }

        // --- Vignette: the solid layer, renamed, with two masks, a layer marker,
        // keyed time remapping and an expression on Opacity. ---
        var vignette = withChildren(
            solidLayer,
            solidLayer.children().map { child ->
                when {
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Vignette".toByteArray(Charsets.UTF_8))
                    child.`is`("ldta") -> {
                        val bytes = child.bytes()!!.copyOf()
                        "Vignette".toByteArray(Charsets.US_ASCII).copyInto(bytes, 64)
                        AepContainer.Chunk.data("ldta", bytes)
                    }
                    else -> child
                }
            }
        )
        vignette = withRootProps(vignette) { rootKids ->
            val out = ArrayList<AepContainer.Chunk>()
            var i = 0
            while (i < rootKids.size) {
                val kid = rootKids[i]
                if (kid.`is`("tdmn") && tdmnText(kid) == "ADBE Time Remapping") {
                    out.add(kid)
                    out.add(keyedRemapTdbs())
                    i += 2 // skip the old default-state tdbs
                    continue
                }
                if (kid.`is`("tdmn") && tdmnText(kid) == "ADBE Transform Group") {
                    out.add(kid)
                    out.add(withOpacityExpression(rootKids[i + 1]))
                    i += 2
                    continue
                }
                out.add(kid)
                i++
            }
            out.add(tdmn("ADBE Mask Parade"))
            out.add(vignetteMaskParade())
            out
        }
        vignette = withChildren(
            vignette,
            vignette.children() + mrst(listOf(0), listOf(nmrd("Vignette start")))
        )

        // --- Control Layer: a null layer (ldta null flag, no source) carrying two
        // Color Controls (one keyed, one static) and a Slider Control with an
        // expression. Its property tree is the solid layer's, with the Effect
        // Parade replaced. ---
        val controlLdta = solidLayer.child("ldta")!!.bytes()!!.copyOf()
        u32(18).copyInto(controlLdta, 0) // fresh layer id
        u32(0).copyInto(controlLdta, 40) // no source item
        controlLdta[38] = (controlLdta[38].toInt() or 0x80).toByte() // null object
        "Control Layer".toByteArray(Charsets.US_ASCII).copyInto(controlLdta, 64)
        val controlLayer = withChildren(
            solidLayer,
            solidLayer.children().map { child ->
                when {
                    child.`is`("ldta") -> AepContainer.Chunk.data("ldta", controlLdta)
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Control Layer".toByteArray(Charsets.UTF_8))
                    child.isList("tdgp") -> withChildren(child, editControlRoot(child.children()))
                    else -> child
                }
            }
        )

        // --- Title: a text layer ("Hello World", two runs) whose property tree
        // is the solid layer's, with a Text Properties group appended before
        // the group end. ---
        val titleLdta = solidLayer.child("ldta")!!.bytes()!!.copyOf()
        u32(19).copyInto(titleLdta, 0) // fresh layer id
        u32(0).copyInto(titleLdta, 40) // no source item
        u32(3).copyInto(titleLdta, 128) // Text layer type
        "Title".toByteArray(Charsets.US_ASCII).copyInto(titleLdta, 64)
        var titleLayer = withChildren(
            solidLayer,
            solidLayer.children().map { child ->
                when {
                    child.`is`("ldta") -> AepContainer.Chunk.data("ldta", titleLdta)
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Title".toByteArray(Charsets.UTF_8))
                    else -> child
                }
            }
        )
        titleLayer = withRootProps(titleLayer) { rootKids ->
            val out = rootKids.toMutableList()
            val endIdx = out.indexOfFirst { it.`is`("tdmn") && tdmnText(it) == "ADBE Group End" }
            val entry = listOf(tdmn("ADBE Text Properties"), textPropertiesGroup())
            if (endIdx >= 0) out.addAll(endIdx, entry) else out.addAll(entry)
            out
        }

        // --- Shapes: a shape layer (one group: rectangle + red fill + blue stroke)
        // whose property tree is the solid layer's, with a Root Vectors Group
        // appended before the group end. ---
        val shapesLdta = solidLayer.child("ldta")!!.bytes()!!.copyOf()
        u32(20).copyInto(shapesLdta, 0) // fresh layer id
        u32(0).copyInto(shapesLdta, 40) // no source item
        u32(4).copyInto(shapesLdta, 128) // Shape layer type
        "Shapes".toByteArray(Charsets.US_ASCII).copyInto(shapesLdta, 64)
        var shapesLayer = withChildren(
            solidLayer,
            solidLayer.children().map { child ->
                when {
                    child.`is`("ldta") -> AepContainer.Chunk.data("ldta", shapesLdta)
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Shapes".toByteArray(Charsets.UTF_8))
                    else -> child
                }
            }
        )
        shapesLayer = withRootProps(shapesLayer) { rootKids ->
            val out = rootKids.toMutableList()
            val endIdx = out.indexOfFirst { it.`is`("tdmn") && tdmnText(it) == "ADBE Group End" }
            val entry = listOf(tdmn("ADBE Root Vectors Group"), shapeVectorsGroup())
            if (endIdx >= 0) out.addAll(endIdx, entry) else out.addAll(entry)
            out
        }

        // --- Nested Comp: a precomp layer pointing at a second composition. The
        // layer is the solid layer's copy (an AV layer, no type change needed)
        // with the Effect Parade stripped, so it tests only the comp link and
        // adds no effects of its own. ---
        val precompLdta = solidLayer.child("ldta")!!.bytes()!!.copyOf()
        u32(23).copyInto(precompLdta, 0) // fresh layer id
        u32(21).copyInto(precompLdta, 40) // source: the nested composition
        "Nested Comp".toByteArray(Charsets.US_ASCII).copyInto(precompLdta, 64)
        var precompLayer = withChildren(
            solidLayer,
            solidLayer.children().map { child ->
                when {
                    child.`is`("ldta") -> AepContainer.Chunk.data("ldta", precompLdta)
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Nested Comp".toByteArray(Charsets.UTF_8))
                    else -> child
                }
            }
        )
        precompLayer = withRootProps(precompLayer) { rootKids ->
            val out = ArrayList<AepContainer.Chunk>()
            var i = 0
            while (i < rootKids.size) {
                val kid = rootKids[i]
                if (kid.`is`("tdmn") && tdmnText(kid) == "ADBE Effect Parade") {
                    i += 2 // skip the parade name + its group
                    continue
                }
                out.add(kid)
                i++
            }
            out
        }

        // --- Nested: a second composition, cloned from the original (its two
        // layers: the footage and the solid, with their Levels and Glow), with
        // a fresh item id and name. ---
        val nestedBase = deepCopy(compItem)
        val nestedCompItem = withChildren(
            nestedBase,
            nestedBase.children().map { child ->
                when {
                    child.`is`("idta") -> {
                        val bytes = child.bytes()!!.copyOf()
                        u32(21).copyInto(bytes, 16)
                        AepContainer.Chunk.data("idta", bytes)
                    }
                    child.`is`("Utf8") ->
                        AepContainer.Chunk.data("Utf8", "Nested".toByteArray(Charsets.UTF_8))
                    else -> child
                }
            }
        )

        // --- Composition markers: the existing SecL list is the "Markers" view
        // layer, and that is where After Effects stores composition markers, so
        // the mrst goes inside it (a second SecL would be shadowed: childList
        // returns the first). The new layer is appended after it. ---
        val compMrst = mrst(
            listOf(0, TIME_SCALE.toInt()),
            listOf(nmrd("Intro", label = 1), nmrd("Beat", chapter = "Chorus"))
        )
        var sawSecL = false
        val newCompItem = withChildren(
            compItem,
            compItem.children().map { child ->
                when {
                    child === solidLayer -> vignette
                    child.isList("SecL") -> {
                        sawSecL = true
                        withChildren(child, child.children() + compMrst)
                    }
                    else -> child
                }
            }.let { if (sawSecL) it else it + AepContainer.Chunk.list("SecL", listOf(compMrst)) } +
                controlLayer + titleLayer + shapesLayer + precompLayer
        )
        val newFold = withChildren(
            fold,
            fold.children().map { if (it === compItem) newCompItem else it } + nestedCompItem
        )
        val newChunks = chunks.map { if (it === fold) newFold else it }
        return AepContainer.write(AepContainer.AepFile(newChunks, file.trailer))
    }

    /** The Control Layer's root property children: the Effect Parade's group is
     * replaced with the three control effects (kept tdsb/tdsn head when present). */
    private fun editControlRoot(rootKids: List<AepContainer.Chunk>): List<AepContainer.Chunk> {
        val out = ArrayList<AepContainer.Chunk>()
        var i = 0
        var replaced = false
        while (i < rootKids.size) {
            val kid = rootKids[i]
            if (kid.`is`("tdmn") && tdmnText(kid) == "ADBE Effect Parade") {
                out.add(kid)
                out.add(controlEffectParade(rootKids[i + 1].children().take(2)))
                replaced = true
                i += 2
                continue
            }
            out.add(kid)
            i++
        }
        if (!replaced) {
            out.add(tdmn("ADBE Effect Parade"))
            out.add(controlEffectParade(listOf(tdsb(0), tdsn("ADBE Effect Parade"))))
        }
        return out
    }

    // ------------------------------------------------------------------ test

    @Test
    fun userProfileLossesAreNearZero() {
        val result = AepImporter.import(userProfileBytes(), allowLosses = true, FakeEffectSpecs())
        assertTrue(result is AepImporter.ImportResult.Imported)
        val imported = result as AepImporter.ImportResult.Imported
        val counts = imported.report.imported
        assertEquals(2, counts.compositions)
        assertEquals(8, counts.layers)
        assertEquals(2, counts.footage)
        assertEquals(2, counts.solids)
        assertEquals(1, counts.precompLayers)
        assertEquals(1, counts.textLayers)
        assertEquals(1, counts.shapeLayers)
        assertEquals(4, counts.shapeItems)
        // 3 on the Control Layer + 2 on Vignette + 2 on the nested comp's solid
        // (the clone keeps its Levels and Glow).
        assertEquals(7, counts.effects)
        assertEquals(2, counts.masks)
        assertEquals(3, counts.markers)
        assertEquals(1, counts.timeRemaps)
        assertEquals(6, counts.keyframes)
        assertEquals(2, counts.expressions)
        // The only losses left are non-content: the converted expressions on
        // Levels/Glow (a popup and a scaled slider cannot carry an expression),
        // on the main solid and again on the nested comp's solid, the two
        // folders, and one unread project setting. Nothing in the user's
        // screenshot profile (effects, masks, markers, time remap) is lost.
        val losses = imported.report.losses.associate { it.category to it.count }
        assertEquals(
            mapOf(
                AepImportReport.LossCategory.Effects to 4,
                AepImportReport.LossCategory.UnsupportedItems to 2,
                AepImportReport.LossCategory.ProjectSettings to 1
            ),
            losses
        )
        val effectDetails = imported.report.losses
            .first { it.category == AepImportReport.LossCategory.Effects }.details
        assertTrue(effectDetails.all { it.contains("expression") })
        assertTrue(effectDetails.none { it.contains("not imported in this build") })

        val root = JSONObject(imported.canonicalJson)
        val comp = findComp(root)
        val layers = comp.getJSONArray("layers")
        assertEquals(6, layers.length())

        // Nested Comp: the precomp layer points at the nested composition, which
        // carries its two original layers.
        val precomp = layers.getJSONObject(5)
        assertEquals("Nested Comp", precomp.getString("name"))
        val nestedItem = compItemByName(root, "Nested")
        assertEquals("Comp", precomp.getJSONObject("source").getString("type"))
        assertEquals(nestedItem.getLong("id"), precomp.getJSONObject("source").getLong("item"))
        val nestedLayers = nestedItem.getJSONObject("kind").getJSONArray("layers")
        assertEquals(2, nestedLayers.length())

        // Vignette: two masks, layer marker, time remap first, Opacity expression.
        val vignette = layers.getJSONObject(1)
        val masks = childGroup(vignette, "masks").getJSONArray("children")
        assertEquals(2, masks.length())
        val mask1 = masks.getJSONObject(0)
        assertEquals("Mask 1", mask1.getString("name"))
        assertEquals("Add", mask1.getJSONObject("kind").getString("mode"))
        assertVertices(
            listOf(
                listOf(12.8, 14.4), listOf(12.8, 33.6),
                listOf(51.2, 33.6), listOf(51.2, 14.4)
            ),
            pathVertices(mask1)
        )
        assertEquals(5.0, propOf(mask1, "feather").getJSONObject("value").getJSONArray("v").getDouble(0), 0.0)
        assertEquals(10.0, propOf(mask1, "feather").getJSONObject("value").getJSONArray("v").getDouble(1), 0.0)
        assertEquals(50.0, propOf(mask1, "opacity").getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(2.0, propOf(mask1, "expansion").getJSONObject("value").getDouble("v"), 0.0)
        val mask2 = masks.getJSONObject(1)
        assertEquals("Subtract", mask2.getJSONObject("kind").getString("mode"))
        assertTrue(mask2.getJSONObject("kind").getBoolean("inverted"))
        assertEquals(2, propOf(mask2, "path").getJSONArray("keys").length())
        assertEquals(1, vignette.getJSONArray("markers").length())
        assertEquals("Vignette start", vignette.getJSONArray("markers").getJSONObject(0).getString("comment"))
        val propsChildren = vignette.getJSONObject("props").getJSONArray("children")
        assertEquals("timeRemap", propsChildren.getJSONObject(0).getString("match"))
        val opacity = findTransformProp(vignette, "opacity")
        assertEquals("wiggle(1, 10)", opacity.getJSONObject("expr").getString("text"))

        // Control Layer: three effects, keyed color red -> blue, slider expression.
        val control = layers.getJSONObject(2)
        val effects = childGroup(control, "effects").getJSONArray("children")
        assertEquals(3, effects.length())
        assertEquals("ec.control.color", effects.getJSONObject(0).getString("match"))
        val colorProp = propOf(effects.getJSONObject(0), "color")
        val colorKeys = colorProp.getJSONArray("keys")
        assertEquals(2, colorKeys.length())
        assertEquals(0L, colorKeys.getJSONObject(0).getLong("time"))
        assertEquals(254016000000L, colorKeys.getJSONObject(1).getLong("time"))
        val firstColor = colorKeys.getJSONObject(0).getJSONObject("value").getJSONArray("v")
        assertEquals(1.0, firstColor.getDouble(0), 1e-9)
        assertEquals(0.0, firstColor.getDouble(1), 1e-9)
        assertEquals(0.0, firstColor.getDouble(2), 1e-9)
        val secondColor = colorKeys.getJSONObject(1).getJSONObject("value").getJSONArray("v")
        assertEquals(0.0, secondColor.getDouble(0), 1e-9)
        assertEquals(0.0, secondColor.getDouble(1), 1e-9)
        assertEquals(1.0, secondColor.getDouble(2), 1e-9)
        assertEquals("ec.control.color", effects.getJSONObject(1).getString("match"))
        assertEquals("ec.control.slider", effects.getJSONObject(2).getString("match"))
        val sliderProp = propOf(effects.getJSONObject(2), "slider")
        assertEquals(50.0, sliderProp.getJSONObject("value").getDouble("v"), 0.0)
        assertEquals("time * 10", sliderProp.getJSONObject("expr").getString("text"))

        // Title: the text imports with its runs, not as an empty layer.
        val title = layers.getJSONObject(3)
        assertEquals("Title", title.getString("name"))
        assertEquals("Text", title.getJSONObject("source").getString("type"))
        val sourceText = propOf(childGroup(title, "text"), "sourceText")
            .getJSONObject("value").getJSONObject("v")
        assertEquals("Hello World", sourceText.getString("text"))
        assertEquals("MyriadPro-Regular", sourceText.getString("font"))
        assertEquals(72.0, sourceText.getDouble("size"), 0.0)
        assertEquals("[1,0,0,1]", sourceText.getJSONArray("fill").toString())
        val textRuns = sourceText.getJSONArray("runs")
        assertEquals(2, textRuns.length())
        assertEquals(6, textRuns.getJSONObject(0).getInt("len"))
        assertEquals(48.0, textRuns.getJSONObject(1).getJSONObject("style").getDouble("size"), 0.0)

        // Shapes: the group imports with its rectangle, fill and stroke.
        val shapes = layers.getJSONObject(4)
        assertEquals("Shapes", shapes.getString("name"))
        assertEquals("Shape", shapes.getJSONObject("source").getString("type"))
        val shapeContents = childGroup(shapes, "contents").getJSONArray("children")
        assertEquals(1, shapeContents.length())
        val shapeGroup = shapeContents.getJSONObject(0)
        assertEquals("group", shapeGroup.getString("match"))
        assertEquals("Shapes 1", shapeGroup.getString("name"))
        val shapeTransform = propOf(shapeGroup, "transform")
        assertEquals(5.0, propOf(shapeTransform, "position").getJSONObject("value").getJSONArray("v").getDouble(0), 0.0)
        assertEquals(5.0, propOf(shapeTransform, "position").getJSONObject("value").getJSONArray("v").getDouble(1), 0.0)
        val shapeItems = propOf(shapeGroup, "contents").getJSONArray("children")
        assertEquals(3, shapeItems.length())
        val shapeRect = shapeItems.getJSONObject(0)
        assertEquals("rect", shapeRect.getString("match"))
        assertEquals(200.0, propOf(shapeRect, "size").getJSONObject("value").getJSONArray("v").getDouble(0), 0.0)
        assertEquals(100.0, propOf(shapeRect, "size").getJSONObject("value").getJSONArray("v").getDouble(1), 0.0)
        assertEquals(5.0, propOf(shapeRect, "roundness").getJSONObject("value").getDouble("v"), 0.0)
        val shapeFill = shapeItems.getJSONObject(1)
        assertEquals("fill", shapeFill.getString("match"))
        assertEquals("[1,0,0,1]", propOf(shapeFill, "color").getJSONObject("value").getJSONArray("v").toString())
        assertEquals(80.0, propOf(shapeFill, "opacity").getJSONObject("value").getDouble("v"), 0.0)
        val shapeStroke = shapeItems.getJSONObject(2)
        assertEquals("stroke", shapeStroke.getString("match"))
        assertEquals(3.0, propOf(shapeStroke, "width").getJSONObject("value").getDouble("v"), 0.0)

        // Composition markers.
        assertEquals(2, comp.getJSONArray("markers").length())
        assertEquals("Intro", comp.getJSONArray("markers").getJSONObject(0).getString("comment"))
        assertEquals("Red", comp.getJSONArray("markers").getJSONObject(0).getString("label"))

        // Uid invariant: every non-zero uid unique and below next_id.
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
        val realUids = uids.filter { it != 0L }
        assertEquals(realUids.size, realUids.toSet().size)
    }

    // ------------------------------------------------------------------ JSON helpers

    /** The main composition's kind: the comp whose item is not the nested one. */
    private fun findComp(root: JSONObject): JSONObject =
        mainCompItem(root).getJSONObject("kind")

    /** The composition item with the given name (its `id` is the precomp target). */
    private fun compItemByName(root: JSONObject, name: String): JSONObject {
        val items = root.getJSONObject("items")
        val keys = items.keys()
        while (keys.hasNext()) {
            val item = items.getJSONObject(keys.next())
            if (item.optJSONObject("kind")?.optString("type") == "Comp" &&
                item.optString("name") == name
            ) {
                return item
            }
        }
        throw AssertionError("no composition named $name in canonical JSON")
    }

    private fun mainCompItem(root: JSONObject): JSONObject {
        val items = root.getJSONObject("items")
        val keys = items.keys()
        while (keys.hasNext()) {
            val item = items.getJSONObject(keys.next())
            if (item.optJSONObject("kind")?.optString("type") == "Comp" &&
                item.optString("name") != "Nested"
            ) {
                return item
            }
        }
        throw AssertionError("no main composition in canonical JSON")
    }

    private fun childGroup(layer: JSONObject, match: String): JSONObject {
        val children = layer.getJSONObject("props").getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no $match group in layer")
    }

    private fun propOf(group: JSONObject, match: String): JSONObject {
        val children = group.getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") == match) return child
        }
        throw AssertionError("no prop $match in ${group.optString("match")}")
    }

    private fun findTransformProp(layer: JSONObject, match: String): JSONObject {
        val children = layer.getJSONObject("props").getJSONArray("children")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("match") != "transform") continue
            val tchildren = child.getJSONArray("children")
            for (t in 0 until tchildren.length()) {
                val prop = tchildren.getJSONObject(t)
                if (prop.optString("match") == match) return prop
            }
        }
        throw AssertionError("no transform prop $match")
    }

    private fun pathVertices(group: JSONObject): List<List<Double>> {
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

    companion object {
        /** The composition time scale (25600 units per second, 25 fps). */
        const val TIME_SCALE = 25600L
    }
}
