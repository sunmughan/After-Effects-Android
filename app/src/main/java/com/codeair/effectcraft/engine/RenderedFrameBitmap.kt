package com.codeair.effectcraft.engine

import android.graphics.Bitmap

/**
 * Decodes a native RGBA frame envelope into an ARGB_8888 Bitmap, or null
 * when the envelope is malformed. Shared by the Bitmap preview and the
 * Surface preview so both present exactly the same pixels.
 */
object RenderedFrameBitmap {
    fun decode(bytes: ByteArray?): Bitmap? {
        val dims = RenderedFrameEnvelope.parse(bytes) ?: return null
        val pixels = IntArray(dims.width * dims.height)
        for (index in pixels.indices) {
            val offset = 8 + index * 4
            val red = bytes!![offset].toInt() and 0xff
            val green = bytes[offset + 1].toInt() and 0xff
            val blue = bytes[offset + 2].toInt() and 0xff
            val alpha = bytes[offset + 3].toInt() and 0xff
            pixels[index] = android.graphics.Color.argb(alpha, red, green, blue)
        }
        return Bitmap.createBitmap(pixels, dims.width, dims.height, Bitmap.Config.ARGB_8888)
    }
}
