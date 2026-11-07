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

    @Test fun mismatchedAspectRatioLetterboxesInsteadOfStretching() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            val frame = Yuv420FrameConverter.fromBitmap(bitmap, 4, 4)
            assertEquals(4, frame.width)
            assertEquals(4, frame.height)
            // 8:4 source fits 4:4 output as 4:2: opaque black bars occupy
            // rows 0 and 3; the two center rows preserve the red source.
            assertArrayEquals(
                byteArrayOf(
                    16, 16, 16, 16,
                    82, 82, 82, 82,
                    82, 82, 82, 82,
                    16, 16, 16, 16
                ),
                frame.i420.copyOfRange(0, 16)
            )
            // Each 2x2 chroma block contains two black and two red pixels.
            assertArrayEquals(
                byteArrayOf(109, 109, 109, 109, 184.toByte(), 184.toByte(), 184.toByte(), 184.toByte()),
                frame.i420.copyOfRange(16, 24)
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
            assertArrayEquals(byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()), frame.i420)
        } finally {
            bitmap.recycle()
        }
    }
}
