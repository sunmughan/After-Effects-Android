package com.codeair.effectcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RenderedFrameCacheTest {
    @Before fun reset() = RenderedFrameCache.clear()

    @Test fun cacheKeyIncludesProjectCompositionFrameAndResolution() {
        val source = """{"version":3}"""
        val key = RenderedFrameCache.key(source, 1, 12, 256)
        assertEquals(key, RenderedFrameCache.key(source, 1, 12, 256))
        org.junit.Assert.assertNotEquals(key, RenderedFrameCache.key(source, 2, 12, 256))
        org.junit.Assert.assertNotEquals(key, RenderedFrameCache.key(source, 1, 13, 256))
        org.junit.Assert.assertNotEquals(key, RenderedFrameCache.key(source, 1, 12, 512))
        org.junit.Assert.assertNotEquals(key, RenderedFrameCache.key("""{"version":4}""", 1, 12, 256))
    }

    @Test fun cachedFramesAreDefensiveCopies() {
        val original = byteArrayOf(1, 2, 3, 4)
        RenderedFrameCache.put("frame", original)
        original[0] = 99
        val first = RenderedFrameCache.get("frame")!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
        first[1] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), RenderedFrameCache.get("frame"))
    }

    @Test fun byteBudgetEvictsLeastRecentlyUsedEntries() {
        val chunk = ByteArray(1024 * 1024)
        for (index in 0 until 12) RenderedFrameCache.put("f$index", chunk)
        RenderedFrameCache.get("f1") // promote f1 before inserting
        RenderedFrameCache.put("new", chunk)
        assertNull(RenderedFrameCache.get("f0"))
        assertNotNull(RenderedFrameCache.get("f1"))
        val (entries, bytes) = RenderedFrameCache.stats()
        assertEquals(12, entries)
        assertEquals(12 * 1024 * 1024, bytes)
    }

    @Test fun concurrentIdenticalRequestsRenderOnlyOnce() {
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val renders = AtomicInteger()
        try {
            val futures = (0 until 8).map {
                pool.submit<ByteArray?> {
                    ready.countDown()
                    start.await()
                    RenderedFrameCache.getOrRender("same-frame") {
                        renders.incrementAndGet()
                        Thread.sleep(25)
                        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 42)
                    }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 42), it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, renders.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun oversizedFramesAreNotRetainedAndClearResetsBudget() {
        RenderedFrameCache.put("large", ByteArray(12 * 1024 * 1024 + 1))
        assertNull(RenderedFrameCache.get("large"))
        assertEquals(0 to 0, RenderedFrameCache.stats())
        RenderedFrameCache.put("small", byteArrayOf(7))
        RenderedFrameCache.clear()
        assertNull(RenderedFrameCache.get("small"))
        assertEquals(0 to 0, RenderedFrameCache.stats())
    }
}
