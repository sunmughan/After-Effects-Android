package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalKeyframeDragTest {
    @Test
    fun frameFromXMapsEdgesAndMidpointAndClamps() {
        assertEquals(0, CanonicalKeyframeDrag.frameFromX(0f, 300f, 91))
        assertEquals(90, CanonicalKeyframeDrag.frameFromX(300f, 300f, 91))
        assertEquals(45, CanonicalKeyframeDrag.frameFromX(150f, 300f, 91))
        assertEquals(0, CanonicalKeyframeDrag.frameFromX(-50f, 300f, 91))
        assertEquals(90, CanonicalKeyframeDrag.frameFromX(999f, 300f, 91))
        assertEquals(0, CanonicalKeyframeDrag.frameFromX(150f, 0f, 91))
        assertEquals(0, CanonicalKeyframeDrag.frameFromX(Float.NaN, 300f, 91))
    }

    @Test
    fun nearestKeyAndGrabRadius() {
        assertEquals(30, CanonicalKeyframeDrag.nearestKey(listOf(0, 30, 60), 28))
        assertNull(CanonicalKeyframeDrag.nearestKey(emptyList(), 28))
        // Marker at frame 30 on a 300px / 91-frame track sits at x=100px.
        assertEquals(30, CanonicalKeyframeDrag.grabsKey(listOf(0, 30, 60), 110f, 300f, 91))
        // x=250px is 50px from the nearest marker (frame 60 at x=200px): outside the grab radius.
        assertNull(CanonicalKeyframeDrag.grabsKey(listOf(0, 30, 60), 250f, 300f, 91))
    }

    @Test
    fun resolveMoveNeedsExactlyOneOwner() {
        assertEquals(7L to "position", CanonicalKeyframeDrag.resolveMove(listOf(7L to "position")))
        assertNull(CanonicalKeyframeDrag.resolveMove(emptyList()))
        assertNull(CanonicalKeyframeDrag.resolveMove(listOf(7L to "position", 7L to "scale")))
    }
}
