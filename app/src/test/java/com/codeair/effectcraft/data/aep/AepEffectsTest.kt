package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effect container view (AepEffects) on the real fixture and on hand-built chunks. */
class AepEffectsTest {

    private fun syntheticBytes(): ByteArray =
        AepContainerTest.resourceBytes("/aep/synthetic.aep")

    private fun syntheticEffects(): List<AepEffects.Effect> {
        val file = AepxReader.parseAny(syntheticBytes())
        val project = AepTypedView.Project.of(file)!!
        val comp = project.allItems().first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        val layers = comp.layers()
        val effects = layers.flatMap { AepEffects.effectsOf(it) }
        assertEquals(2, layers.size)
        return effects
    }

    @Test
    fun fixtureHasLevelsAndGlowInStackOrder() {
        val effects = syntheticEffects()
        assertEquals(listOf("ADBE Easy Levels2", "ADBE Glo2"), effects.map { it.matchName })
        assertEquals("Levels", effects[0].displayName())
        assertEquals("Glow", effects[1].displayName())
        assertEquals(true, effects[0].enabled())
        assertEquals(true, effects[1].enabled())
        assertTrue(effects.all { it.hasOwnDefinitions() })
    }

    @Test
    fun glowParametersDecode() {
        val glow = syntheticEffects()[1]
        val params = glow.params()
        assertEquals(16, params.size)
        assertEquals("ADBE Glo2-0000", params[0].matchName)
        assertEquals(AepEffects.ParamKind.LAYER, params[0].kind)
        assertEquals("Glow Based On", params[1].name)
        assertEquals(AepEffects.ParamKind.POPUP, params[1].kind)
        assertEquals(listOf("Alpha Channel", "Color Channels"), params[1].items)
        assertEquals(AepEffects.ParamValue.Index(2), params[1].pardDefault())
        assertEquals(2, params[1].popupItemCount())
        val threshold = params[2]
        assertEquals("Glow Threshold", threshold.name)
        assertEquals(AepEffects.ParamKind.FIXED_SLIDER, threshold.kind)
        assertEquals(AepEffects.ParamValue.Number(153.0), threshold.pardDefault())
        assertEquals(255.0, threshold.validMax()!!, 0.0)
        val midpoint = params[11]
        assertEquals("A & B Midpoint", midpoint.name)
        assertEquals(0.9900054931640625, midpoint.validMax()!!, 1e-9)
        assertEquals(AepEffects.ParamValue.Number(0.5), midpoint.recordedValue())
        assertEquals(AepEffects.ParamValue.Number(0.5), midpoint.storedValue())
        assertEquals("0.25", midpoint.stored?.expression())
        val colorA = params[12]
        assertEquals(AepEffects.ParamKind.COLOR, colorA.kind)
        assertEquals(AepEffects.ParamValue.Argb(1.0, 1.0, 1.0, 1.0), colorA.pardDefault())
        val colorB = params[13]
        assertEquals(AepEffects.ParamValue.Argb(1.0, 0.0, 0.0, 0.0), colorB.pardDefault())
    }

    @Test
    fun levelsParametersDecode() {
        val levels = syntheticEffects()[0]
        val params = levels.params()
        assertEquals(11, params.size)
        assertEquals(AepEffects.ParamKind.LAYER, params[0].kind)
        assertEquals(AepEffects.ParamKind.POPUP, params[1].kind)
        assertEquals(listOf("RGB", "Red", "Green", "Blue", "Alpha"), params[1].items)
        assertEquals(AepEffects.ParamKind.ARBITRARY_DATA, params[2].kind)
        assertEquals("Histogram", params[2].name)
        assertEquals(AepEffects.ParamKind.FLOAT_SLIDER, params[3].kind)
        assertEquals("Input Black", params[3].name)
        assertEquals(AepEffects.ParamKind.NO_DATA, params[10].kind)
        assertEquals("ADBE Effect Built In Params", params[10].matchName)
        // The histogram data follows the value record of parameter 2.
        val data = levels.arbitraryData("ADBE Easy Levels2-0002")
        assertEquals(108, data!!.size)
    }

    @Test
    fun projectDefinitionsIndexFindsBothTypes() {
        val file = AepxReader.parseAny(syntheticBytes())
        val project = AepTypedView.Project.of(file)!!
        val defs = AepEffects.EffectDefinitions.of(project)
        val comp = project.allItems().first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        for (layer in comp.layers()) {
            for (fx in AepEffects.effectsOf(layer)) {
                assertEquals(AepEffects.DefinitionSource.INSTANCE, defs.source(fx))
                assertEquals(fx.params().size, defs.params(fx).size)
            }
        }
    }

    // -- hand-built chunks -------------------------------------------------

    /** A 148-byte `pard`: kind at 12, name at 16 (32 bytes), default bytes at 56. */
    private fun pard(kind: Long, name: String, default: ByteArray = byteArrayOf()): ByteArray {
        val bytes = ByteArray(148)
        fun putU32(at: Int, v: Long) {
            for (i in 0 until 4) bytes[at + i] = (v ushr (24 - 8 * i)).toByte()
        }
        putU32(12, kind)
        name.toByteArray(Charsets.US_ASCII).copyInto(bytes, 16)
        default.copyInto(bytes, 56)
        return bytes
    }

    private fun tdmn(name: String): AepContainer.Chunk {
        val body = ByteArray(40)
        name.toByteArray(Charsets.US_ASCII).copyInto(body)
        return AepContainer.Chunk.data("tdmn", body)
    }

    /** A `tdbs` property with the given flags, dims and static f64 values. */
    private fun tdbs(flags: Long, dims: Int, values: List<Double>): AepContainer.Chunk {
        val tdsb = ByteArray(4)
        for (i in 0 until 4) tdsb[i] = (flags ushr (24 - 8 * i)).toByte()
        val header = ByteArray(124)
        header[3] = dims.toByte()
        val cdat = ByteArray(values.size * 8)
        for ((i, v) in values.withIndex()) {
            val bits = v.toBits()
            for (j in 0 until 8) cdat[i * 8 + j] = (bits ushr (56 - 8 * j)).toByte()
        }
        return AepContainer.Chunk.list(
            "tdbs",
            listOf(
                AepContainer.Chunk.data("tdsb", tdsb),
                AepContainer.Chunk.data("tdb4", header),
                AepContainer.Chunk.data("cdat", cdat)
            )
        )
    }

    @Test
    fun colorControlParamDecodes() {
        // A Color Control instance: parT (input layer + color), tdgp with a stored color.
        val colorDefault = byteArrayOf(255.toByte(), 255.toByte(), 0, 0) // opaque red
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Color Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Color Control-0001"),
                AepContainer.Chunk.data("pard", pard(5, "Color", colorDefault))
            )
        )
        val values = AepContainer.Chunk.list(
            "tdgp",
            listOf(
                tdmn("ADBE Color Control-0001"),
                tdbs(1, 4, listOf(255.0, 0.0, 128.0, 255.0))
            )
        )
        val sspc = AepContainer.Chunk.list("sspc", listOf(part, values))
        val fx = AepEffects.Effect("ADBE Color Control", sspc)
        assertTrue(fx.hasOwnDefinitions())
        val params = fx.params()
        assertEquals(2, params.size)
        assertEquals(AepEffects.ParamKind.LAYER, params[0].kind)
        assertEquals("Color", params[1].name)
        assertEquals(AepEffects.ParamKind.COLOR, params[1].kind)
        assertEquals(AepEffects.ParamValue.Argb(1.0, 1.0, 0.0, 0.0), params[1].pardDefault())
        assertEquals(AepEffects.ParamValue.Argb(1.0, 0.0, 128.0 / 255.0, 1.0), params[1].recordedValue())
        assertEquals(AepEffects.ParamValue.Argb(1.0, 0.0, 128.0 / 255.0, 1.0), params[1].value())
        assertNull(params[1].stored?.expression())
    }

    @Test
    fun checkboxWithoutRecordIsOff() {
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Checkbox Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Checkbox Control-0001"),
                AepContainer.Chunk.data("pard", pard(4, "Checkbox", byteArrayOf(0, 0, 0, 0)))
            )
        )
        val values = AepContainer.Chunk.list("tdgp", emptyList())
        val fx = AepEffects.Effect("ADBE Checkbox Control", AepContainer.Chunk.list("sspc", listOf(part, values)))
        val param = fx.params()[1]
        assertEquals(AepEffects.ParamValue.Bool(false), param.value())
        assertEquals(AepEffects.ParamValue.Bool(false), param.pardDefault())
    }

    @Test
    fun pointDefaultPercentIsDetected() {
        // A point default stored as percent (50, 50) reads back as the fraction (0.5, 0.5).
        val def = ByteArray(8)
        fun putI32(at: Int, v: Int) {
            for (i in 0 until 4) def[at + i] = (v ushr (24 - 8 * i)).toByte()
        }
        putI32(0, 50 * 65536)
        putI32(4, 50 * 65536)
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Point Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Point Control-0001"),
                AepContainer.Chunk.data("pard", pard(6, "Point", def))
            )
        )
        val fx = AepEffects.Effect(
            "ADBE Point Control",
            AepContainer.Chunk.list("sspc", listOf(part, AepContainer.Chunk.list("tdgp", emptyList())))
        )
        assertEquals(AepEffects.ParamValue.Point(0.5, 0.5), fx.params()[1].value())
    }

    @Test
    fun otherKindWithThreeComponentsDecodesAsVec3() {
        // A 3D Point Control instance: the 3D Point is kind 8 (OTHER) with a tdbs
        // record of three f64s; it decodes as a Vec3, not as an unreadable value.
        val part = AepContainer.Chunk.list(
            "parT",
            listOf(
                tdmn("ADBE Point3D Control-0000"), AepContainer.Chunk.data("pard", pard(0, "")),
                tdmn("ADBE Point3D Control-0001"),
                AepContainer.Chunk.data("pard", pard(8, "3D Point"))
            )
        )
        val values = AepContainer.Chunk.list(
            "tdgp",
            listOf(
                tdmn("ADBE Point3D Control-0001"),
                tdbs(1, 3, listOf(0.359, 0.7, 0.0))
            )
        )
        val fx = AepEffects.Effect(
            "ADBE Point3D Control",
            AepContainer.Chunk.list("sspc", listOf(part, values))
        )
        val param = fx.params()[1]
        assertEquals(AepEffects.ParamKind.OTHER, param.kind)
        assertEquals(AepEffects.ParamValue.Vec3(0.359, 0.7, 0.0), param.recordedValue())
        assertEquals(AepEffects.ParamValue.Vec3(0.359, 0.7, 0.0), param.storedValue())
        assertEquals(AepEffects.ParamValue.Vec3(0.359, 0.7, 0.0), param.value())
    }
}
