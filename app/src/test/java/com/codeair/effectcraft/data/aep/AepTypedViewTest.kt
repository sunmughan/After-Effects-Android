package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Typed view over the chunk tree: project items, properties and keyframes. */
class AepTypedViewTest {

    // ------------------------------------------------------------------ builders

    private fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).putInt(v.toInt())
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

    /** A `LIST tdbs` property chunk with the given children. */
    private fun tdbs(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdbs", children.toList())

    /** A `LIST list` keyframe list chunk. */
    private fun keyList(header: ByteArray, ldat: ByteArray): AepContainer.Chunk =
        AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", header),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )

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

    /** A spatial 3-component keyframe record (128 bytes). */
    private fun spatialRecord(
        time: Int, value: List<Double>, inTangent: List<Double>, outTangent: List<Double>
    ): ByteArray {
        val b = ByteArray(128)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = 1
        b[5] = 1
        u32(1).copyInto(b, 8)
        for (i in 0..2) f64(value[i]).copyInto(b, 56 + i * 8)
        for (i in 0..2) f64(inTangent[i]).copyInto(b, 80 + i * 8)
        for (i in 0..2) f64(outTangent[i]).copyInto(b, 104 + i * 8)
        return b
    }

    // ------------------------------------------------------------------ properties

    @Test
    fun staticPropertyReadsValue() {
        val cdat = ByteArray(16)
        f64(3.0).copyInto(cdat, 0)
        f64(4.0).copyInto(cdat, 8)
        val prop = AepTypedView.Property.fromChunk(
            tdbs(
                AepContainer.Chunk.data("tdb4", tdb4(dims = 2, spatial = false, timeScale = 25)),
                AepContainer.Chunk.data("cdat", cdat)
            )
        )!!
        assertFalse(prop.isAnimated())
        assertEquals(2, prop.dims())
        assertEquals(listOf(3.0, 4.0), prop.staticValue())
        assertNull(prop.keys())
    }

    @Test
    fun plainKeyframesDecode() {
        val ldat = plainRecord(
            time = 0, inInterp = 1, outInterp = 2, flags = 0, value = 50.0,
            inSpeed = 0.0, inInfluence = 50.0, outSpeed = 1.0, outInfluence = 75.0
        ) + plainRecord(
            time = 25, inInterp = 2, outInterp = 3,
            flags = 0x20 or (5 shl 6), value = 100.0
        )
        val prop = AepTypedView.Property.fromChunk(
            tdbs(
                AepContainer.Chunk.data("tdb4", tdb4(dims = 1, spatial = false, timeScale = 25)),
                keyList(lhd3(count = 2, recordSize = 48), ldat)
            )
        )!!
        assertTrue(prop.isAnimated())
        val keys = prop.keys()!!
        assertEquals(2, keys.len())
        val keyframes = keys.keyframes()
        val first = keyframes[0]
        assertEquals(AepKeys.KeyframeKind.Plain(1), first.kind())
        assertEquals(0, first.timeUnits())
        assertEquals(0.0, first.seconds(25)!!, 0.0)
        assertEquals(listOf(50.0), first.value())
        assertEquals(AepKeys.Interpolation.Linear, first.interpolationIn())
        assertEquals(AepKeys.Interpolation.Bezier, first.interpolationOut())
        val ease = first.ease()!!
        assertEquals(0.0, ease.inSpeed, 0.0)
        assertEquals(50.0, ease.inInfluence, 0.0)
        assertEquals(1.0, ease.outSpeed, 0.0)
        assertEquals(75.0, ease.outInfluence, 0.0)
        val second = keyframes[1]
        assertEquals(25, second.timeUnits())
        assertEquals(1.0, second.seconds(25)!!, 0.0)
        assertEquals(listOf(100.0), second.value())
        assertEquals(AepKeys.Interpolation.Bezier, second.interpolationIn())
        assertEquals(AepKeys.Interpolation.Hold, second.interpolationOut())
        assertEquals(true, second.isRoving())
        assertEquals(5, second.label())
        assertEquals(100.0, second.scalarValue()!!, 0.0)
    }

    @Test
    fun spatialKeyframesDecode() {
        val ldat = spatialRecord(
            time = 0,
            value = listOf(10.0, 20.0, 0.0),
            inTangent = listOf(1.0, 2.0, 0.0),
            outTangent = listOf(3.0, 4.0, 0.0)
        )
        val prop = AepTypedView.Property.fromChunk(
            tdbs(
                AepContainer.Chunk.data("tdb4", tdb4(dims = 3, spatial = true, timeScale = 25)),
                keyList(lhd3(count = 1, recordSize = 128), ldat)
            )
        )!!
        val key = prop.keys()!!.keyframes()[0]
        assertEquals(AepKeys.KeyframeKind.Spatial(3), key.kind())
        assertEquals(listOf(10.0, 20.0, 0.0), key.value())
        assertEquals(listOf(10.0, 20.0, 0.0), key.spatialValue()!!.toList())
        val spatial = key.spatial()!!
        assertEquals(listOf(1.0, 2.0, 0.0), spatial.inTangent.toList())
        assertEquals(listOf(3.0, 4.0, 0.0), spatial.outTangent.toList())
    }

    @Test
    fun markerListDecodesAsMarkers() {
        val ldat = ByteArray(32) // Two 16-byte marker records.
        val list = AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", lhd3(count = 2, recordSize = 16)),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )
        val keyList = AepTypedView.KeyList.fromChunk(list)!!
        assertEquals(2, keyList.len())
        assertEquals(AepKeys.KeyframeKind.Marker, keyList.keyframes()[0].kind())
    }

    @Test
    fun propertyFromChunkRejectsOtherLists() {
        val chunk = AepContainer.Chunk.list("tdgp", emptyList())
        assertNull(AepTypedView.Property.fromChunk(chunk))
    }

    // ------------------------------------------------------------------ synthetic.aep

    private fun syntheticProject(): AepTypedView.Project {
        val bytes = AepContainerTest.resourceBytes("/aep/synthetic.aep")
        val file = AepContainer.parse(bytes)
        return AepTypedView.Project.of(file)!!
    }

    @Test
    fun syntheticProjectItems() {
        val project = syntheticProject()
        val items = project.allItems()
        assertEquals(5, items.size)
        val names = items.map { it.name() }
        assertTrue(names.contains("C"))
        assertTrue(names.contains("Folder"))
        assertTrue(names.contains("Solids"))
        val comp = items.first { it.kind() == AepTypedView.ItemKind.Composition }
        assertEquals(2L, comp.id())
        val footage = items.first { it.id() == 1L }
        assertEquals(AepTypedView.ItemKind.Footage, footage.kind())
        val solid = items.first { it.id() == 15L }
        assertEquals(AepTypedView.ItemKind.Footage, solid.kind())
    }

    @Test
    fun syntheticCompHeader() {
        val project = syntheticProject()
        val comp = project.allItems()
            .first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        val header = comp.header()!!
        assertEquals(25.0, header.frameRate()!!, 0.0)
        assertEquals(64, header.width)
        assertEquals(48, header.height)
        assertEquals(1.0, header.durationSeconds()!!, 0.0)
        assertEquals(0.0, header.workAreaStartSeconds()!!, 0.0)
        assertEquals(1.0, header.workAreaEndSeconds()!!, 0.0)
        assertEquals(2, comp.layers().size)
    }

    @Test
    fun syntheticFootage() {
        val project = syntheticProject()
        val seq = project.allItems().first { it.id() == 1L }
        val seqFootage = seq.footage()!!
        assertEquals("png!", seqFootage.fileType)
        assertEquals(8, seqFootage.width)
        assertEquals(8, seqFootage.height)
        assertTrue(seqFootage.isSequence)
        assertFalse(seqFootage.isSolid)
        assertEquals("E:\\Projekte\\ae-corpus\\scratch\\synth\\seq", seqFootage.path)
        val solid = project.allItems().first { it.id() == 15L }.footage()!!
        assertTrue(solid.isSolid)
        assertEquals(64, solid.width)
        assertEquals(48, solid.height)
        assertNotNull(solid.solidArgb)
        assertEquals(listOf(1.0f, 0.2f, 0.4f, 0.6f), solid.solidArgb!!.toList())
    }

    @Test
    fun syntheticLayersHaveOrientationAtDefault() {
        val project = syntheticProject()
        val comp = project.allItems()
            .first { it.kind() == AepTypedView.ItemKind.Composition }.comp()!!
        for (layer in comp.layers()) {
            assertEquals(AepHeader.LayerType.Av, layer.header()?.layerType)
            val orientation = layer.orientation()!!
            assertEquals(listOf(0.0, 0.0, 0.0), orientation.degrees.toList())
            assertFalse(orientation.animated)
            assertFalse(orientation.hasExpression)
            val source = project.layerSource(layer)
            assertNotNull(source)
        }
    }
}
