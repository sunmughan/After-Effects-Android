package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The project settings import (AepProjectSettings) on hand-built top-level chunks. */
class AepProjectSettingsTest {

    private fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (v ushr (24 - 8 * i)).toByte()
        return b
    }

    private fun nhed(
        depthCode: Int? = null,
        timeMode: Int? = null,
        feetFlag: Int? = null,
        framesPerFoot: Int? = null,
        frameStartCode: Int? = null
    ): AepContainer.Chunk {
        val b = ByteArray(16)
        if (timeMode != null) b[8] = timeMode.toByte()
        if (feetFlag != null) b[11] = feetFlag.toByte()
        if (framesPerFoot != null) b[13] = framesPerFoot.toByte()
        if (frameStartCode != null) b[14] = frameStartCode.toByte()
        if (depthCode != null) b[15] = depthCode.toByte()
        return AepContainer.Chunk.data("nhed", b)
    }

    private fun file(vararg chunks: AepContainer.Chunk): AepContainer.AepFile =
        AepContainer.AepFile(chunks.toList(), ByteArray(0))

    private fun read(f: AepContainer.AepFile): Pair<AepProjectSettings.Patch, List<AepImportReport.Loss>> {
        val col = AepImportReport.Collector()
        val patch = AepProjectSettings.read(f, col)
        val losses = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses
        return patch to losses
    }

    @Test
    fun readsSixteenBitTimecodeFromZero() {
        // The real file's settings: 16-bit, timecode, frame count from 0, gamma 2.2.
        val (patch, losses) = read(file(nhed(depthCode = 1, timeMode = 0, frameStartCode = 2), AepContainer.Chunk.data("dwga", u32(0))))
        assertTrue(losses.isEmpty())
        assertEquals("Bpc16", patch.bitDepth)
        assertEquals("Timecode", patch.timeDisplay)
        assertEquals(0L, patch.frameStart)
        assertEquals(2.2, patch.workingGamma!!, 0.0)
        assertEquals(false, patch.linearize)
        assertEquals(false, patch.blendLinear)
        assertNull(patch.workingSpace)
        assertNull(patch.outputSpace)
    }

    @Test
    fun readsThirtyTwoBitFeetAndGamma24() {
        val (patch, losses) = read(
            file(
                nhed(depthCode = 2, timeMode = 1, feetFlag = 1, framesPerFoot = 40, frameStartCode = 1),
                AepContainer.Chunk.data("dwga", u32(1)),
                AepContainer.Chunk.data("lnrp", byteArrayOf(1)),
                AepContainer.Chunk.data("lnrb", byteArrayOf(1))
            )
        )
        assertTrue(losses.isEmpty())
        assertEquals("Bpc32", patch.bitDepth)
        assertEquals("Feet16", patch.timeDisplay)
        assertEquals(1L, patch.frameStart)
        assertEquals(2.4, patch.workingGamma!!, 0.0)
        assertEquals(true, patch.linearize)
        assertEquals(true, patch.blendLinear)
    }

    @Test
    fun feetThirtyFiveIsFeet35() {
        val (patch, losses) = read(file(nhed(timeMode = 1, feetFlag = 1, framesPerFoot = 16, frameStartCode = 2)))
        assertTrue(losses.isEmpty())
        assertEquals("Feet35", patch.timeDisplay)
    }

    @Test
    fun unknownDepthCodeIsALoss() {
        val (patch, losses) = read(file(nhed(depthCode = 7, frameStartCode = 2)))
        assertNull(patch.bitDepth)
        assertEquals(1, losses.sumOf { it.count })
        assertEquals(AepImportReport.LossCategory.ProjectSettings, losses.single().category)
        assertTrue(losses.single().details.single().contains("7"))
    }

    @Test
    fun unknownFeetPerFootIsALoss() {
        val (patch, losses) = read(file(nhed(timeMode = 1, feetFlag = 1, framesPerFoot = 24, frameStartCode = 2)))
        assertNull(patch.timeDisplay)
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("24"))
    }

    @Test
    fun unknownFrameStartCodeIsALoss() {
        val (patch, losses) = read(file(nhed(frameStartCode = 9)))
        assertNull(patch.frameStart)
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("9"))
    }

    @Test
    fun rec709WorkingSpaceSetsOutputSpace() {
        val json = """{"colorProfileName": "Rec.709 Gamma 2.4"}""".toByteArray(Charsets.UTF_8)
        val (patch, losses) = read(
            file(
                AepContainer.Chunk.data("PwCs", ByteArray(0)),
                AepContainer.Chunk.data("Utf8", json)
            )
        )
        assertTrue(losses.isEmpty())
        assertEquals("Rec709", patch.workingSpace)
        assertEquals("Rec709", patch.outputSpace)
    }

    @Test
    fun srgbWorkingSpaceLeavesOutputSpaceUnset() {
        val json = """{"colorProfileName": "sRGB IEC61966-2.1"}""".toByteArray(Charsets.UTF_8)
        val (patch, losses) = read(
            file(
                AepContainer.Chunk.data("PwCs", ByteArray(0)),
                AepContainer.Chunk.data("Utf8", json)
            )
        )
        assertTrue(losses.isEmpty())
        assertEquals("Srgb", patch.workingSpace)
        assertNull(patch.outputSpace)
    }

    @Test
    fun emptyWorkingSpaceJsonIsNone() {
        val (patch, losses) = read(
            file(
                AepContainer.Chunk.data("PwCs", ByteArray(0)),
                AepContainer.Chunk.data("Utf8", "{}".toByteArray(Charsets.UTF_8))
            )
        )
        assertTrue(losses.isEmpty())
        assertNull(patch.workingSpace)
    }

    @Test
    fun unknownWorkingSpaceIsALoss() {
        val json = """{"colorProfileName": "ProPhoto RGB"}""".toByteArray(Charsets.UTF_8)
        val (patch, losses) = read(
            file(
                AepContainer.Chunk.data("PwCs", ByteArray(0)),
                AepContainer.Chunk.data("Utf8", json)
            )
        )
        assertNull(patch.workingSpace)
        assertEquals(1, losses.sumOf { it.count })
        assertTrue(losses.single().details.single().contains("ProPhoto RGB"))
    }
}
