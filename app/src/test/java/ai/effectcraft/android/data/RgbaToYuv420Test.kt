package ai.effectcraft.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RgbaToYuv420Test {
    /** Solid 2x2 RGBA block: every pixel identical, so subsampled chroma equals the pixel chroma. */
    private fun solid(r: Int, g: Int, b: Int, width: Int = 2, height: Int = 2): ByteArray {
        val out = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            out[i * 4] = r.toByte()
            out[i * 4 + 1] = g.toByte()
            out[i * 4 + 2] = b.toByte()
            out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    @Test
    fun convertsBlackWhiteAndRedToBt601LimitedRangeYuv() {
        // BT.601 limited range, hand-computed: black -> Y16 U128 V128;
        // white -> Y235 U128 V128; red -> Y82 U90 V240.
        val black = RgbaToYuv420.convert(solid(0, 0, 0), 2, 2, RgbaToYuv420.Layout.PLANAR_I420)
        assertArrayEquals(byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()), black)
        val white = RgbaToYuv420.convert(solid(255, 255, 255), 2, 2, RgbaToYuv420.Layout.PLANAR_I420)
        assertArrayEquals(byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()), white)
        val red = RgbaToYuv420.convert(solid(255, 0, 0), 2, 2, RgbaToYuv420.Layout.PLANAR_I420)
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90, 240.toByte()), red)
    }

    @Test
    fun semiplanarLayoutInterleavesChromaAfterLuma() {
        val red = RgbaToYuv420.convert(solid(255, 0, 0), 2, 2, RgbaToYuv420.Layout.SEMIPLANAR_NV12)
        // NV12: Y plane, then one interleaved U,V pair per 2x2 block.
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90, 240.toByte()), red)
    }

    @Test
    fun averagesChromaOverEachTwoByTwoBlock() {
        // Top-left red, top-right green, bottom-left blue, bottom-right white.
        val rgba = ByteArray(16)
        val pixels = arrayOf(
            intArrayOf(255, 0, 0), intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255), intArrayOf(255, 255, 255)
        )
        for (i in pixels.indices) {
            rgba[i * 4] = pixels[i][0].toByte()
            rgba[i * 4 + 1] = pixels[i][1].toByte()
            rgba[i * 4 + 2] = pixels[i][2].toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }
        val yuv = RgbaToYuv420.convert(rgba, 2, 2, RgbaToYuv420.Layout.PLANAR_I420)
        assertEquals(6, yuv.size)
        // Luma per pixel: red 82, green 144, blue 41, white 235 (BT.601 limited).
        assertArrayEquals(byteArrayOf(82, 144.toByte(), 41, 235.toByte(), 128.toByte(), 128.toByte()), yuv)
    }

    @Test
    fun rejectsOddDimensionsAndMismatchedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaToYuv420.convert(solid(0, 0, 0, 3, 2), 3, 2, RgbaToYuv420.Layout.PLANAR_I420)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaToYuv420.convert(ByteArray(8), 2, 2, RgbaToYuv420.Layout.PLANAR_I420)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaToYuv420.convert(solid(0, 0, 0), 0, 2, RgbaToYuv420.Layout.PLANAR_I420)
        }
    }
}
