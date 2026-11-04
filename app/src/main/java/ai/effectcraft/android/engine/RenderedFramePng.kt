package ai.effectcraft.android.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import java.io.OutputStream

/**
 * Encodes a native RGBA frame envelope as PNG. Both the source and requested
 * output dimensions are bounded before bitmap allocation.
 */
object RenderedFramePng {
    data class Dimensions(val width: Int, val height: Int)

    const val MAX_SIDE = 8192
    /** Single authority for this value is RenderBudget; kept here so call sites do not diverge. */
    const val MAX_PIXELS = RenderBudget.MAX_PIXELS_PER_FRAME
    /** Square native render cap below MAX_PIXELS; output may be scaled up within its own budget. */
    const val MAX_NATIVE_RENDER_SIDE = 2896

    @Throws(IOException::class, IllegalArgumentException::class)
    fun write(frame: ByteArray, output: OutputStream): Dimensions {
        val bitmap = decode(frame)
        try {
            encode(bitmap, output)
            return Dimensions(bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Resamples a bounded native frame to the requested output dimensions.
     * NativeSession should render with maxSide <= MAX_NATIVE_RENDER_SIDE so
     * the source Bitmap also stays within the memory budget.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun writeScaled(frame: ByteArray, output: OutputStream, targetWidth: Int, targetHeight: Int): Dimensions {
        require(targetWidth in 1..MAX_SIDE && targetHeight in 1..MAX_SIDE) {
            "Requested PNG dimensions are outside the supported range"
        }
        require(targetWidth.toLong() * targetHeight.toLong() <= MAX_PIXELS) {
            "Requested PNG exceeds the pixel budget"
        }
        val source = decode(frame)
        val scaled = if (source.width == targetWidth && source.height == targetHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
        try {
            encode(scaled, output)
            return Dimensions(targetWidth, targetHeight)
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    private fun decode(frame: ByteArray): Bitmap {
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
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun encode(bitmap: Bitmap, output: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IOException("Android PNG encoder returned false")
        }
        output.flush()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
