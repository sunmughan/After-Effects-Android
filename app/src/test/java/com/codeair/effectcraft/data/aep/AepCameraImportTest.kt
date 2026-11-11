package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera import (AepCameraImport) on hand-built option groups. */
class AepCameraImportTest {

    private fun collector(): AepImportReport.Collector = AepImportReport.Collector()

    private fun tdmn(name: String): AepContainer.Chunk {
        val body = ByteArray(40)
        name.toByteArray(Charsets.US_ASCII).copyInto(body)
        return AepContainer.Chunk.data("tdmn", body)
    }

    /**
     * A `tdbs` property with the given flags, dims, static f64 values and the
     * auto-state bytes (76 and 77 of the 124-byte header) set to 1.
     */
    private fun tdbs(
        flags: Long,
        dims: Int,
        values: List<Double>,
        auto: Boolean = false
    ): AepContainer.Chunk {
        val tdsb = ByteArray(4)
        for (i in 0 until 4) tdsb[i] = (flags ushr (24 - 8 * i)).toByte()
        val header = ByteArray(124)
        header[3] = dims.toByte()
        if (auto) {
            header[76] = 1
            header[77] = 1
        }
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

    private fun group(vararg children: AepContainer.Chunk): AepTypedView.PropGroup =
        AepTypedView.PropGroup(AepContainer.Chunk.list("tdgp", children.toList()))

    private fun import(
        options: AepTypedView.PropGroup?,
        position: AepCameraImport.CameraPosition? = null,
        col: AepImportReport.Collector = collector()
    ): Pair<Map<String, AepCameraImport.CameraOption>, List<AepImportReport.Loss>> {
        val out = AepCameraImport.importCameraOptions(
            options, position, "Main: 'Camera 1'", 1920.0, 25L, col
        )
        val losses = col.finish(
            AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)
        ).losses
        return out to losses
    }

    @Test
    fun storedOptionsImport() {
        val options = group(
            tdmn("ADBE Camera Zoom"), tdbs(1, 1, listOf(1280.0)),
            tdmn("ADBE Camera Focus Distance"), tdbs(1, 1, listOf(1860.0)),
            tdmn("ADBE Camera Aperture"), tdbs(1, 1, listOf(12.148481439820024)),
            tdmn("ADBE Camera Depth of Field"), tdbs(1, 1, listOf(1.0))
        )
        val (out, losses) = import(options)
        assertEquals(1280.0, out["zoom"]!!.value.getDouble("v"), 0.0)
        assertEquals(1860.0, out["focusDistance"]!!.value.getDouble("v"), 0.0)
        assertEquals(12.148481439820024, out["aperture"]!!.value.getDouble("v"), 0.0)
        assertTrue(out["dof"]!!.value.getBoolean("v"))
        assertTrue(losses.isEmpty())
    }

    @Test
    fun absentZoomFallsBackToAutoPosition() {
        val options = group()
        val position = AepCameraImport.CameraPosition(listOf(0.0, 0.0, -1605.0), autoState = true)
        val (out, losses) = import(options, position)
        // Zoom from -position.z, focus distance from the zoom, aperture from
        // the 50 mm preset; depth of field stays at the converter default.
        assertEquals(1605.0, out["zoom"]!!.value.getDouble("v"), 0.0)
        assertEquals(1605.0, out["focusDistance"]!!.value.getDouble("v"), 0.0)
        assertEquals(
            AepCameraImport.APERTURE_1920,
            out["aperture"]!!.value.getDouble("v"),
            1e-12
        )
        assertNull(out["dof"])
        assertTrue(losses.isEmpty())
    }

    @Test
    fun zoomWithoutAutoPositionStaysAtDefault() {
        val options = group()
        val position = AepCameraImport.CameraPosition(listOf(0.0, 0.0, -1605.0), autoState = false)
        val (out, losses) = import(options, position)
        assertNull(out["zoom"])
        assertNull(out["focusDistance"])
        assertTrue(losses.isEmpty())
    }

    @Test
    fun defaultBlurLevelIsSilentAndOtherIsALoss() {
        val options = group(
            tdmn("ADBE Camera Blur Level"), tdbs(1, 1, listOf(100.0))
        )
        val (out, losses) = import(options)
        assertNull(out["blurLevel"])
        assertTrue(losses.isEmpty())
        val other = group(
            tdmn("ADBE Camera Blur Level"), tdbs(1, 1, listOf(50.0))
        )
        val (out2, losses2) = import(other)
        assertNull(out2["blurLevel"])
        assertEquals(1, losses2.sumOf { it.count })
        assertTrue(losses2.single().details.single().contains("Blur Level"))
    }

    @Test
    fun unmappedStoredPropertyIsALoss() {
        val options = group(
            tdmn("ADBE Camera Iris Shape"), tdbs(1, 1, listOf(3.0))
        )
        val (out, losses) = import(options)
        assertNull(out["irisShape"])
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("Iris Shape"))
    }

    @Test
    fun cameraPositionReadsAutoStateFromHeader() {
        val transform = group(
            tdmn("ADBE Position"), tdbs(1, 3, listOf(0.0, 0.0, -1605.0), auto = true)
        )
        val position = AepCameraImport.cameraPosition(transform)
        assertEquals(listOf(0.0, 0.0, -1605.0), position!!.stored)
        assertTrue(position.autoState)
        val manual = group(
            tdmn("ADBE Position"), tdbs(1, 3, listOf(0.0, 0.0, -1605.0), auto = false)
        )
        val manualPosition = AepCameraImport.cameraPosition(manual)
        assertFalse(manualPosition!!.autoState)
        assertNull(AepCameraImport.cameraPosition(null))
        assertNull(AepCameraImport.cameraPosition(group()))
    }
}
