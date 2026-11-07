package com.codeair.effectcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface/Texture presentation state machine, tested without Android: the
 * AndroidView wrapper is a thin shell over this machine, so every
 * lifecycle transition here is the behaviour the editor surface gets.
 */
class SurfacePresentationTest {

    @Test fun detachedInitiallyAndNeedsNoRender() {
        val surface = SurfacePresentation()
        assertFalse(surface.isAttached)
        assertFalse(surface.takeNeedsRender())
        assertEquals(0, surface.surfaceWidth)
        assertEquals(0, surface.surfaceHeight)
    }

    @Test fun attachRequestsARenderAndDestroyDetaches() {
        val surface = SurfacePresentation()
        surface.onSurfaceAvailable(320, 180)
        assertTrue(surface.isAttached)
        assertEquals(320, surface.surfaceWidth)
        assertTrue(surface.takeNeedsRender())
        assertFalse(surface.takeNeedsRender())
        surface.onSurfaceDestroyed()
        assertFalse(surface.isAttached)
        assertFalse(surface.takeNeedsRender())
    }

    @Test fun resizeToNewSizeRequestsARenderButSameSizeDoesNot() {
        val surface = SurfacePresentation()
        surface.onSurfaceAvailable(320, 180)
        surface.takeNeedsRender()
        surface.onSurfaceResized(320, 180)
        assertFalse(surface.takeNeedsRender())
        surface.onSurfaceResized(640, 360)
        assertTrue(surface.takeNeedsRender())
        assertEquals(640, surface.surfaceWidth)
    }

    @Test fun sessionLossWhileAttachedMarksLostAndRecoveryReattachesAndRenders() {
        val surface = SurfacePresentation()
        surface.onSurfaceAvailable(320, 180)
        surface.takeNeedsRender()
        surface.onSessionLost()
        assertTrue(surface.isLost)
        assertFalse(surface.isAttached)
        surface.onSessionRecovered()
        assertTrue(surface.isAttached)
        assertFalse(surface.isLost)
        assertTrue(surface.takeNeedsRender())
        assertEquals(320, surface.surfaceWidth)
    }

    @Test fun sessionLossWhileDetachedIsANoOpAndDestroyAfterLossClearsLost() {
        val surface = SurfacePresentation()
        surface.onSessionLost()
        assertFalse(surface.isLost)
        surface.onSurfaceAvailable(160, 90)
        surface.onSessionLost()
        surface.onSurfaceDestroyed()
        assertFalse(surface.isLost)
        assertFalse(surface.isAttached)
    }

    @Test fun frameEnvelopeParsesDimensionsAndRejectsBadPayloads() {
        val bytes = ByteArray(8 + 2 * 3 * 4)
        bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 2
        bytes[4] = 0; bytes[5] = 0; bytes[6] = 0; bytes[7] = 3
        val dims = RenderedFrameEnvelope.parse(bytes)
        assertEquals(2, dims!!.width)
        assertEquals(3, dims.height)
        assertNull(RenderedFrameEnvelope.parse(byteArrayOf(0, 0)))
        assertNull(RenderedFrameEnvelope.parse(ByteArray(8 + 2 * 3 * 4 - 1)))
        val zeroWidth = ByteArray(8)
        assertNull(RenderedFrameEnvelope.parse(zeroWidth))
        // Width 2049 with a 1-row payload: over the 2048 preview cap and the
        // pixel count does not match the header either — both reject.
        val oversized = ByteArray(8)
        oversized[2] = 8
        oversized[3] = 1
        oversized[7] = 1
        assertNull(RenderedFrameEnvelope.parse(oversized))
    }
}
