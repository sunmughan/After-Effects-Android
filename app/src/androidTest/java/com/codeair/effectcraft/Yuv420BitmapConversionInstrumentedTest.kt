package com.codeair.effectcraft

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codeair.effectcraft.engine.Yuv420FrameConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Yuv420BitmapConversionInstrumentedTest {
    @Test fun bitmapConversionMatchesNativeEnvelopeColorMath() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            val frame = Yuv420FrameConverter.fromBitmap(bitmap, 2, 2)
            assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90.toByte(), 240.toByte()), frame.i420)
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
