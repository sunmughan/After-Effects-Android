package com.codeair.effectcraft.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Yuv420BitmapConversionTest {
    @Test fun bitmapConversionMatchesNativeEnvelopeColorMath() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            val frame = Yuv420FrameConverter.fromBitmap(bitmap, 2, 2)
            assertArrayEquals(
                Yuv420FrameConverter.fromNativeRgba(
                    byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2) +
                        ByteArray(16).also { bytes ->
                            for (i in 0 until 4) {
                                bytes[i * 4] = 255.toByte()
                                bytes[i * 4 + 3] = 255.toByte()
                            }
                        }
                ).i420,
                frame.i420
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun bitmapIsScaledToRequestedEvenDimensions() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val frame = Yuv420FrameConverter.fromBitmap(bitmap, 2, 2)
            assertEquals(2, frame.width)
            assertEquals(2, frame.height)
            assertArrayEquals(byteArrayOf(235, 235, 235, 235, 128.toByte(), 128.toByte()), frame.i420)
        } finally {
            bitmap.recycle()
        }
    }
}
