package ai.effectcraft.android.data

/**
 * Converts an RGBA frame to YUV420 (BT.601 limited range) for MediaCodec
 * ByteBuffer input. Pure Kotlin: no Android dependency, so the exact luma
 * and chroma values are JVM-testable. Width and height must be even, which
 * [Mp4ExportRequirements] enforces for every MP4 plan.
 */
object RgbaToYuv420 {
    enum class Layout { PLANAR_I420, SEMIPLANAR_NV12 }

    fun convert(rgba: ByteArray, width: Int, height: Int, layout: Layout): ByteArray {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(width % 2 == 0 && height % 2 == 0) { "YUV420 requires even frame dimensions" }
        require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) {
            "RGBA payload length does not match frame dimensions"
        }
        val ySize = width * height
        val chromaSize = ySize / 4
        val out = ByteArray(ySize + chromaSize * 2)
        // Luma per pixel, then one averaged chroma pair per 2x2 block.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val r = rgba[offset].toInt() and 0xff
                val g = rgba[offset + 1].toInt() and 0xff
                val b = rgba[offset + 2].toInt() and 0xff
                out[y * width + x] = yOf(r, g, b).toByte()
            }
        }
        for (blockY in 0 until height / 2) {
            for (blockX in 0 until width / 2) {
                var uSum = 0
                var vSum = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val offset = ((blockY * 2 + dy) * width + (blockX * 2 + dx)) * 4
                        val r = rgba[offset].toInt() and 0xff
                        val g = rgba[offset + 1].toInt() and 0xff
                        val b = rgba[offset + 2].toInt() and 0xff
                        uSum += uOf(r, g, b)
                        vSum += vOf(r, g, b)
                    }
                }
                val u = (uSum / 4).toByte()
                val v = (vSum / 4).toByte()
                val blockIndex = blockY * (width / 2) + blockX
                when (layout) {
                    Layout.PLANAR_I420 -> {
                        out[ySize + blockIndex] = u
                        out[ySize + chromaSize + blockIndex] = v
                    }
                    Layout.SEMIPLANAR_NV12 -> {
                        out[ySize + blockIndex * 2] = u
                        out[ySize + blockIndex * 2 + 1] = v
                    }
                }
            }
        }
        return out
    }

    private fun yOf(r: Int, g: Int, b: Int): Int =
        ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16

    private fun uOf(r: Int, g: Int, b: Int): Int =
        ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128

    private fun vOf(r: Int, g: Int, b: Int): Int =
        ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
}
