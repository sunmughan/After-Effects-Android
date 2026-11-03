package ai.effectcraft.android.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import java.io.OutputStream

/**
 * Encodes the native renderer's [width,height,RGBA...] frame envelope as a
 * standards-compliant PNG. Dimensions and byte count are validated before
 * allocating a Bitmap so malformed JNI data cannot trigger an unbounded
 * allocation.
 */
object RenderedFramePng {
    data class Dimensions(val width: Int, val height: Int)

    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 8_388_608L

    @Throws(IOException::class, IllegalArgumentException::class)
    fun write(frame: ByteArray, output: OutputStream): Dimensions {
        require(frame.size >= 8) { "Rendered frame is missing its dimension header" }
        val width = readInt(frame, 0)
        val height = readInt(frame, 4)
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) {
            "Rendered frame dimensions are outside the supported range"
        }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= MAX_PIXELS) { "Rendered frame exceeds the PNG export pixel budget" }
        val expectedBytes = 8L + pixelCount * 4L
        require(frame.size.toLong() == expectedBytes) { "Rendered frame RGBA payload length is inconsistent" }

        val pixels = IntArray(pixelCount.toInt())
        var offset = 8
        for (index in pixels.indices) {
            val red = frame[offset].toInt() and 0xff
            val green = frame[offset + 1].toInt() and 0xff
            val blue = frame[offset + 2].toInt() and 0xff
            val alpha = frame[offset + 3].toInt() and 0xff
            pixels[index] = Color.argb(alpha, red, green, blue)
            offset += 4
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Android PNG encoder returned false")
            }
            output.flush()
        } finally {
            bitmap.recycle()
        }
        return Dimensions(width, height)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
