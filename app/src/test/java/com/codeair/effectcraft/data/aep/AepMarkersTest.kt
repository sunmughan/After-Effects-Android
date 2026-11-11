package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Layer and composition markers, time remapping and orientation keyframes. */
class AepMarkersTest {

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

    private fun tdbs(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdbs", children.toList())

    private fun tdgp(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("tdgp", children.toList())

    private fun tdmn(name: String): AepContainer.Chunk =
        AepContainer.Chunk.data("tdmn", name.toByteArray(Charsets.US_ASCII))

    private fun tdsb(flags: Long): AepContainer.Chunk =
        AepContainer.Chunk.data("tdsb", u32(flags))

    private fun keyList(header: ByteArray, ldat: ByteArray): AepContainer.Chunk =
        AepContainer.Chunk.list(
            "list",
            listOf(
                AepContainer.Chunk.data("lhd3", header),
                AepContainer.Chunk.data("ldat", ldat)
            )
        )

    /** A 16-byte marker record: time in units at byte 0. */
    private fun markerRecord(time: Int): ByteArray {
        val b = ByteArray(16)
        u32(time.toLong()).copyInto(b, 0)
        return b
    }

    /** A `LIST Nmrd`: the five strings, cue parameter pairs, then the `NmHd` header. */
    private fun nmrd(
        comment: String,
        chapter: String = "",
        url: String = "",
        frameTarget: String = "",
        cueName: String = "",
        cueType: Long = 4,
        pairs: List<Pair<String, String>> = emptyList(),
        duration: Long = 0,
        perSecond: Long = 600,
        label: Int = 0
    ): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        for (s in listOf(comment, chapter, url, frameTarget, cueName)) {
            kids.add(AepContainer.Chunk.data("Utf8", s.toByteArray(Charsets.UTF_8)))
        }
        for ((k, v) in pairs) {
            kids.add(AepContainer.Chunk.data("Utf8", k.toByteArray(Charsets.UTF_8)))
            kids.add(AepContainer.Chunk.data("Utf8", v.toByteArray(Charsets.UTF_8)))
        }
        val header = ByteArray(20)
        u32(cueType).copyInto(header, 0)
        u32(pairs.size.toLong()).copyInto(header, 4)
        u32(duration).copyInto(header, 8)
        u32(perSecond).copyInto(header, 12)
        header[16] = label.toByte()
        kids.add(AepContainer.Chunk.data("NmHd", header))
        return AepContainer.Chunk.list("Nmrd", kids)
    }

    /** A holder chunk (a layer, or a composition's `SecL`) with an `mrst` list. */
    private fun holderWithMarkers(
        times: List<Int>,
        nmrds: List<AepContainer.Chunk>,
        timeScale: Long = 25
    ): AepContainer.Chunk {
        val ldat = ByteArray(times.size * 16)
        for ((i, t) in times.withIndex()) markerRecord(t).copyInto(ldat, i * 16)
        val prop = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(1, false, timeScale)),
            keyList(lhd3(times.size.toLong(), 16), ldat)
        )
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(prop)
        kids.addAll(nmrds)
        return AepContainer.Chunk.list("mrst", kids)
    }

    private fun holder(vararg children: AepContainer.Chunk): AepContainer.Chunk =
        AepContainer.Chunk.list("Lyr ", children.toList())

    // ------------------------------------------------------------------ markers

    @Test
    fun markersDecodeWithStringsAndCuePoint() {
        val mrst = holderWithMarkers(
            listOf(0, 50),
            listOf(
                nmrd("intro", label = 1),
                nmrd(
                    "beat", chapter = "Ch 2", url = "https://example.com",
                    frameTarget = "_blank", cueName = "hit", cueType = 5,
                    pairs = listOf("speed" to "fast"), duration = 300, label = 8
                )
            )
        )
        val markers = AepMarkers.markersIn(holder(mrst))
        assertEquals(2, markers.size)
        val first = markers[0]
        assertEquals(0, first.timeUnits)
        assertEquals("intro", first.comment)
        assertEquals(1, first.label)
        assertNull(first.cueType.let { if (it is AepMarkers.CueType.Other) it.code else null })
        val second = markers[1]
        assertEquals(50, second.timeUnits)
        assertEquals("beat", second.comment)
        assertEquals("Ch 2", second.chapter)
        assertEquals("https://example.com", second.url)
        assertEquals("_blank", second.frameTarget)
        assertEquals("hit", second.cuePointName)
        assertEquals(AepMarkers.CueType.Navigation, second.cueType)
        assertEquals(listOf("speed" to "fast"), second.cueParams)
        assertEquals(0.5, second.durationSeconds!!, 0.0)
        assertEquals(8, second.label)
    }

    @Test
    fun markersImportToCanonicalJson() {
        val mrst = holderWithMarkers(
            listOf(25, 0),
            listOf(nmrd("later", label = 2), nmrd("first"))
        )
        val col = AepImportReport.Collector()
        val list = AepMarkers.markersIn(holder(mrst))
        val json = AepMarkers.importMarkers(list, holder(mrst), "Comp: 'Layer'", 25, col)
        assertEquals(2, col.imported.markers)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
        // Sorted by time: the 0-unit marker first.
        assertEquals(2, json.length())
        val first = json.getJSONObject(0)
        assertEquals(0L, first.getLong("time"))
        assertEquals("first", first.getString("comment"))
        assertEquals("None", first.getString("label"))
        assertFalse(first.has("cue_point"))
        val second = json.getJSONObject(1)
        assertEquals(254016000000L, second.getLong("time"))
        assertEquals("later", second.getString("comment"))
        assertEquals("Yellow", second.getString("label"))
    }

    @Test
    fun unknownCueTypeIsALoss() {
        val mrst = holderWithMarkers(
            listOf(0),
            listOf(nmrd("x", cueType = 9))
        )
        val col = AepImportReport.Collector()
        val json = AepMarkers.importMarkers(AepMarkers.markersIn(holder(mrst)), holder(mrst), "Comp: 'Layer'", 25, col)
        assertEquals(1, json.length())
        // No cue name or parameters, so no cue point object — the unknown type is
        // still reported.
        assertFalse(json.getJSONObject(0).has("cue_point"))
        val report = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
        assertEquals(1, report.losses.sumOf { it.count })
        assertEquals(AepImportReport.LossCategory.Markers, report.losses.first().category)
        assertTrue(report.losses.first().details[0].contains("cue type 9"))
    }

    @Test
    fun cueTypeZeroIsAPlainMarker() {
        // The real file's 38 markers all carry cue type 0 with no cue point: a
        // zero-initialised header, not an unknown cue type. The marker imports whole
        // with no loss.
        val mrst = holderWithMarkers(
            listOf(0),
            listOf(nmrd("Media Example", cueType = 0))
        )
        val col = AepImportReport.Collector()
        val list = AepMarkers.markersIn(holder(mrst))
        assertEquals(1, list.size)
        assertNull(list[0].cueType)
        val json = AepMarkers.importMarkers(list, holder(mrst), "Comp: 'Layer'", 25, col)
        assertEquals(1, json.length())
        assertEquals("Media Example", json.getJSONObject(0).getString("comment"))
        assertFalse(json.getJSONObject(0).has("cue_point"))
        val report = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
        assertTrue(report.losses.isEmpty())
    }

    @Test
    fun noMrstMeansNoMarkers() {
        assertTrue(AepMarkers.markersIn(holder()).isEmpty())
    }

    // ------------------------------------------------------------------ time remap

    private fun plainRecord(time: Int, value: Double): ByteArray {
        val b = ByteArray(48)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = 1
        b[5] = 1
        f64(value).copyInto(b, 8)
        return b
    }

    private fun remapRoot(animated: Boolean, values: List<Double> = listOf(0.0, 2.0)): AepTypedView.PropGroup {
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(tdsb(1))
        kids.add(AepContainer.Chunk.data("tdb4", tdb4(1, false, 25)))
        if (animated) {
            val ldat = ByteArray(values.size * 48)
            for ((i, v) in values.withIndex()) {
                plainRecord(i * 25, v).copyInto(ldat, i * 48)
            }
            kids.add(keyList(lhd3(values.size.toLong(), 48), ldat))
        } else {
            val cdat = ByteArray(8)
            f64(values[0]).copyInto(cdat, 0)
            kids.add(AepContainer.Chunk.data("cdat", cdat))
        }
        return AepTypedView.PropGroup.fromChunk(
            tdgp(tdmn("ADBE Time Remapping"), tdbs(*kids.toTypedArray()))
        )!!
    }

    @Test
    fun timeRemapImportsKeys() {
        val col = AepImportReport.Collector()
        var nextUid = 500L
        val prop = AepMarkers.importTimeRemap(remapRoot(true), "Layer", 25, { 500L }, col)
        assertTrue(prop != null)
        assertEquals("timeRemap", prop!!.getString("match"))
        assertEquals(500L, prop.getLong("uid"))
        val keys = prop.getJSONArray("keys")
        assertEquals(2, keys.length())
        assertEquals(0L, keys.getJSONObject(0).getLong("time"))
        assertEquals(254016000000L, keys.getJSONObject(1).getLong("time"))
        assertEquals(0.0, keys.getJSONObject(0).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(2.0, keys.getJSONObject(1).getJSONObject("value").getDouble("v"), 0.0)
        assertEquals(1, col.imported.timeRemaps)
        assertEquals(2, col.imported.keyframes)
    }

    @Test
    fun timeRemapOffIsNull() {
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(tdsb(3))
        kids.add(AepContainer.Chunk.data("tdb4", tdb4(1, false, 25)))
        val cdat = ByteArray(8)
        f64(0.0).copyInto(cdat, 0)
        kids.add(AepContainer.Chunk.data("cdat", cdat))
        val root = AepTypedView.PropGroup.fromChunk(
            tdgp(tdmn("ADBE Time Remapping"), tdbs(*kids.toTypedArray()))
        )!!
        val col = AepImportReport.Collector()
        assertNull(AepMarkers.importTimeRemap(root, "Layer", 25, { 1L }, col))
        assertEquals(0, col.imported.timeRemaps)
    }

    // ------------------------------------------------------------------ orientation

    /** An 80-byte orientation key record: time, interpolation sides, flags. */
    private fun orientationRecord(time: Int, inInterp: Int, outInterp: Int): ByteArray {
        val b = ByteArray(80)
        u32(time.toLong()).copyInto(b, 0)
        b[4] = inInterp.toByte()
        b[5] = outInterp.toByte()
        return b
    }

    private fun orientationLayer(): AepTypedView.Layer {
        val ldat = ByteArray(160)
        orientationRecord(0, 1, 1).copyInto(ldat, 0)
        orientationRecord(25, 1, 3).copyInto(ldat, 80)
        val tdbsProp = tdbs(
            AepContainer.Chunk.data("tdb4", tdb4(3, false, 25)),
            keyList(lhd3(2, 80), ldat)
        )
        fun otda(x: Double, y: Double, z: Double): AepContainer.Chunk {
            val b = ByteArray(24)
            f64(x).copyInto(b, 0)
            f64(y).copyInto(b, 8)
            f64(z).copyInto(b, 16)
            return AepContainer.Chunk.data("otda", b)
        }
        val otst = AepContainer.Chunk.list(
            "otst",
            listOf(
                tdbsProp,
                AepContainer.Chunk.list("otky", listOf(otda(10.0, 20.0, 30.0), otda(40.0, 50.0, 60.0)))
            )
        )
        val transform = tdgp(tdmn("ADBE Orientation"), otst)
        val root = tdgp(tdmn("ADBE Transform Group"), transform)
        return AepTypedView.Layer(AepContainer.Chunk.list("Lyr ", listOf(root)))
    }

    @Test
    fun orientationKeysDecode() {
        val layer = orientationLayer()
        val keys = layer.orientationKeys()
        assertTrue(keys != null)
        assertEquals(2, keys!!.size)
        assertEquals(0, keys[0].timeUnits)
        assertEquals(listOf(10.0, 20.0, 30.0), keys[0].degrees)
        assertEquals(25, keys[1].timeUnits)
        assertEquals(listOf(40.0, 50.0, 60.0), keys[1].degrees)
        assertEquals(25L, layer.orientationTimeScale())
    }

    @Test
    fun orientationKeysImportAsRichKeys() {
        val col = AepImportReport.Collector()
        val keys = AepMarkers.importOrientationKeys(orientationLayer(), "Layer", 25, col)
        assertTrue(keys != null)
        assertEquals(2, keys!!.size)
        assertEquals(0L, keys[0].timeTicks)
        assertEquals(listOf(10.0, 20.0, 30.0), keys[0].value)
        assertEquals("Linear", keys[0].inInterp)
        assertEquals(254016000000L, keys[1].timeTicks)
        assertEquals(listOf(40.0, 50.0, 60.0), keys[1].value)
        assertEquals("Hold", keys[1].outInterp)
        assertEquals(2, col.imported.keyframes)
        assertTrue(col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses.isEmpty())
    }
}
