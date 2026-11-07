package com.codeair.effectcraft

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codeair.effectcraft.engine.RenderedFrameBitmapCache
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderedFrameBitmapCacheInstrumentedTest {
    @Test
    fun sameFrameReusesBitmapAndNewFrameRecyclesPrevious() {
        val cache = RenderedFrameBitmapCache()
        try {
            val firstBytes = frame(0xff0000ff.toInt())
            val first = cache.update(firstBytes)!!
            assertSame(first, cache.update(firstBytes))
            assertTrue(!first.isRecycled)

            val secondBytes = frame(0xff00ff00.toInt())
            val second = cache.update(secondBytes)!!
            assertNotSame(first, second)
            assertTrue("Superseded preview bitmap must be recycled", first.isRecycled)
            assertSame(second, cache.bitmap)
        } finally {
            cache.close()
        }
    }

    @Test
    fun invalidFrameReleasesPreviousBitmapAndCloseIsIdempotent() {
        val cache = RenderedFrameBitmapCache()
        val first = cache.update(frame(0xffff0000.toInt()))!!
        assertNull(cache.update(byteArrayOf(1, 2, 3)))
        assertTrue(first.isRecycled)
        assertNull(cache.bitmap)
        cache.close()
        cache.close()
    }

    @Test
    fun closeRecyclesCurrentBitmap() {
        val cache = RenderedFrameBitmapCache()
        val bitmap: Bitmap = cache.update(frame(0xff112233.toInt()))!!
        cache.close()
        assertTrue(bitmap.isRecycled)
        assertNull(cache.bitmap)
    }

    private fun frame(argb: Int): ByteArray {
        val bytes = ByteArray(8 + 2 * 2 * 4)
        bytes[3] = 2
        bytes[7] = 2
        val red = (argb ushr 16) and 0xff
        val green = (argb ushr 8) and 0xff
        val blue = argb and 0xff
        val alpha = (argb ushr 24) and 0xff
        for (i in 0 until 4) {
            val offset = 8 + i * 4
            bytes[offset] = red.toByte()
            bytes[offset + 1] = green.toByte()
            bytes[offset + 2] = blue.toByte()
            bytes[offset + 3] = alpha.toByte()
        }
        return bytes
    }
}
