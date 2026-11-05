package com.codeair.effectcraft.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Yuv420FrameConverterTest {
    private fun envelope(width: Int, height: Int, rgba: List<Int>): ByteArray {
        // Size by the payload actually supplied, not by the dimensions:
        // sizing by dimensions padded a short payload with zeros, so the
        // malformed-payload case below never failed the length check.
        val bytes = ByteArray(8 + rgba.size)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        putInt(0, width); putInt(4, height)
        rgba.forEachIndexed { index, channel -> bytes[8 + index] = channel.toByte() }
        return bytes
    }

    @Test fun containFitPreservesAspectAndCentersLetterbox() {
        val wide = Yuv420FrameConverter.fitContainRect(1920, 1080, 1280, 960)
        assertEquals(0.0, wide.left, 0.001)
        assertEquals(120.0, wide.top, 0.001)
        assertEquals(1280.0, wide.right, 0.001)
        assertEquals(840.0, wide.bottom, 0.001)
        assertEquals(1280.0, wide.width, 0.001)
        assertEquals(720.0, wide.height, 0.001)

        val tall = Yuv420FrameConverter.fitContainRect(640, 480, 1280, 720)
        assertEquals(160.0, tall.left, 0.001)
        assertEquals(0.0, tall.top, 0.001)
        assertEquals(1120.0, tall.right, 0.001)
        assertEquals(720.0, tall.bottom, 0.001)
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420FrameConverter.fitContainRect(0, 1, 2, 2)
        }
    }

    @Test fun solidRedConvertsToBt601LimitedI420() {
        val red = List(4) { listOf(255, 0, 0, 255) }.flatten()
        val frame = Yuv420FrameConverter.fromNativeRgba(envelope(2, 2, red))
        assertEquals(2, frame.width); assertEquals(2, frame.height)
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90.toByte(), 240.toByte()), frame.i420)
    }

    @Test fun transparentPixelsCompositeOverBlackAndWhiteHasNeutralChroma() {
        val pixels = listOf(
            255, 0, 0, 0,  // transparent red -> black
            255, 255, 255, 255,
            255, 255, 255, 255,
            255, 255, 255, 255
        )
        val frame = Yuv420FrameConverter.fromNativeRgba(envelope(2, 2, pixels))
        assertEquals(16, frame.i420[0].toInt() and 0xff)
        assertEquals(235, frame.i420[1].toInt() and 0xff)
        assertEquals(235, frame.i420[2].toInt() and 0xff)
        assertEquals(235, frame.i420[3].toInt() and 0xff)
        assertEquals(128, frame.i420[4].toInt() and 0xff)
        assertEquals(128, frame.i420[5].toInt() and 0xff)
    }

    @Test fun nv12InterleavesChromaWithoutChangingLuma() {
        val rgba = List(4) { listOf(255, 0, 0, 255) }.flatten()
        val i420 = Yuv420FrameConverter.fromNativeRgba(envelope(2, 2, rgba))
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90.toByte(), 240.toByte()), Yuv420FrameConverter.toNv12(i420))
    }

    @Test fun rejectsOddDimensionsMalformedPayloadAndOversizedFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420FrameConverter.fromNativeRgba(envelope(3, 2, List(6) { listOf(0, 0, 0, 255) }.flatten()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420FrameConverter.fromNativeRgba(envelope(2, 2, listOf(0, 0, 0, 255)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420FrameConverter.fromNativeRgba(envelope(4098, 2, List(8196) { listOf(0, 0, 0, 255) }.flatten()))
        }
    }
}
