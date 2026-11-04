package com.codeair.effectcraft.engine

/**
 * Converts the native frame envelope (big-endian width/height followed by RGBA8)
 * to tightly packed planar YUV 4:2:0 (I420: Y, U, V planes).
 *
 * Video encoders do not preserve alpha. Pixels are composited over black before
 * conversion; chroma is averaged over each 2×2 block. This is a pure, bounded
 * conversion so its color math can be tested without a device codec.
 */
object Yuv420FrameConverter {
    data class Frame(val width: Int, val height: Int, val i420: ByteArray) {
        init {
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
            require(i420.size == width * height * 3 / 2)
        }
    }

    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 8_388_608L

    fun fromNativeRgba(envelope: ByteArray): Frame {
        require(envelope.size >= 8) { "Native frame is missing its dimension header" }
        val width = readInt(envelope, 0)
        val height = readInt(envelope, 4)
        require(width in 2..MAX_SIDE && height in 2..MAX_SIDE && width % 2 == 0 && height % 2 == 0) {
            "YUV420 dimensions must be even and between 2 and $MAX_SIDE"
        }
        val pixels = width.toLong() * height
        require(pixels <= MAX_PIXELS) { "Native frame exceeds the YUV420 pixel budget" }
        require(envelope.size.toLong() == 8L + pixels * 4L) {
            "Native RGBA payload length is inconsistent with its dimensions"
        }

        val ySize = pixels.toInt()
        val chromaSize = ySize / 4
        val output = ByteArray(ySize + chromaSize * 2)
        val uOffset = ySize
        val vOffset = ySize + chromaSize
        var chromaIndex = 0

        for (blockY in 0 until height step 2) {
            for (blockX in 0 until width step 2) {
                var sumU = 0
                var sumV = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = blockX + dx
                        val y = blockY + dy
                        val offset = 8 + (y * width + x) * 4
                        val alpha = envelope[offset + 3].toInt() and 0xff
                        val red = ((envelope[offset].toInt() and 0xff) * alpha + 127) / 255
                        val green = ((envelope[offset + 1].toInt() and 0xff) * alpha + 127) / 255
                        val blue = ((envelope[offset + 2].toInt() and 0xff) * alpha + 127) / 255

                        output[y * width + x] = clamp(16 + ((66 * red + 129 * green + 25 * blue + 128) shr 8)).toByte()
                        sumU += clamp(128 + ((-38 * red - 74 * green + 112 * blue + 128) shr 8))
                        sumV += clamp(128 + ((112 * red - 94 * green - 18 * blue + 128) shr 8))
                    }
                }
                output[uOffset + chromaIndex] = (sumU / 4).toByte()
                output[vOffset + chromaIndex] = (sumV / 4).toByte()
                chromaIndex++
            }
        }
        return Frame(width, height, output)
    }

    /**
     * Scales an ARGB bitmap to the encoder's requested size and converts it
     * directly to I420. Alpha is composited over black. The bitmap is not
     * recycled here; ownership stays with the caller.
     */
    fun fromBitmap(bitmap: android.graphics.Bitmap, targetWidth: Int, targetHeight: Int): Frame {
        require(targetWidth in 2..MAX_SIDE && targetHeight in 2..MAX_SIDE &&
            targetWidth % 2 == 0 && targetHeight % 2 == 0) {
            "YUV420 output dimensions must be even and between 2 and $MAX_SIDE"
        }
        val pixelCount = targetWidth.toLong() * targetHeight
        require(pixelCount <= MAX_PIXELS) { "Bitmap exceeds the YUV420 pixel budget" }
        val scaled = if (bitmap.width == targetWidth && bitmap.height == targetHeight) bitmap
            else android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        try {
            val argb = IntArray(pixelCount.toInt())
            scaled.getPixels(argb, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            val ySize = argb.size
            val chromaSize = ySize / 4
            val output = ByteArray(ySize + chromaSize * 2)
            var chromaIndex = 0
            for (blockY in 0 until targetHeight step 2) {
                for (blockX in 0 until targetWidth step 2) {
                    var sumU = 0
                    var sumV = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val color = argb[(blockY + dy) * targetWidth + blockX + dx]
                        val alpha = (color ushr 24) and 0xff
                        val red = (((color ushr 16) and 0xff) * alpha + 127) / 255
                        val green = (((color ushr 8) and 0xff) * alpha + 127) / 255
                        val blue = ((color and 0xff) * alpha + 127) / 255
                        val index = (blockY + dy) * targetWidth + blockX + dx
                        output[index] = clamp(16 + ((66 * red + 129 * green + 25 * blue + 128) shr 8)).toByte()
                        sumU += clamp(128 + ((-38 * red - 74 * green + 112 * blue + 128) shr 8))
                        sumV += clamp(128 + ((112 * red - 94 * green - 18 * blue + 128) shr 8))
                    }
                    output[ySize + chromaIndex] = (sumU / 4).toByte()
                    output[ySize + chromaSize + chromaIndex] = (sumV / 4).toByte()
                    chromaIndex++
                }
            }
            return Frame(targetWidth, targetHeight, output)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Converts I420 planes to NV12 (Y followed by interleaved U,V chroma). */
    fun toNv12(frame: Frame): ByteArray {
        val ySize = frame.width * frame.height
        val chromaSize = ySize / 4
        val output = ByteArray(ySize + chromaSize * 2)
        frame.i420.copyInto(output, 0, 0, ySize)
        for (index in 0 until chromaSize) {
            output[ySize + index * 2] = frame.i420[ySize + index]
            output[ySize + index * 2 + 1] = frame.i420[ySize + chromaSize + index]
        }
        return output
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
