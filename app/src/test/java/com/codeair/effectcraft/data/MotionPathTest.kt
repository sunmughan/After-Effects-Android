package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion-path sampling for spatial (position/anchor) tracks. The sampler
 * mirrors upstream `auto_spatial` (Catmull-Rom tangents, a sixth of the way
 * from the previous key to the next) and the cubic Bezier segments the
 * renderer evaluates, so the overlay draws the path the renderer follows.
 */
class MotionPathTest {

    private fun positionKey(frame: Int, x: Double, y: Double, roving: Boolean = false,
                            spatialIn: List<Double> = listOf(0.0, 0.0, 0.0),
                            spatialOut: List<Double> = listOf(0.0, 0.0, 0.0),
                            spatialAuto: Boolean = true) =
        CanonicalCurveKey(
            frame = frame, value = x, components = listOf(x, y, 0.0),
            roving = roving, spatialIn = spatialIn, spatialOut = spatialOut, spatialAuto = spatialAuto
        )

    @Test
    fun autoTangentsMirrorUpstreamCatmullRom() {
        val keys = listOf(
            positionKey(0, 0.0, 0.0),
            positionKey(15, 100.0, 100.0),
            positionKey(30, 200.0, 0.0)
        )
        // Middle key: out tangent is (next - prev) / 6, in tangent its mirror.
        val (tin, tout) = MotionPath.effectiveTangents(keys, 1)
        assertEquals(-200.0 / 6.0, tin[0], 0.000001)
        assertEquals(0.0, tin[1], 0.000001)
        assertEquals(0.0, tin[2], 0.000001)
        assertEquals(200.0 / 6.0, tout[0], 0.000001)
        assertEquals(0.0, tout[1], 0.000001)
        assertEquals(0.0, tout[2], 0.000001)
        // End keys use their one neighbour.
        val (_, firstOut) = MotionPath.effectiveTangents(keys, 0)
        assertEquals(100.0 / 6.0, firstOut[0], 0.000001)
        assertEquals(100.0 / 6.0, firstOut[1], 0.000001)
        assertEquals(0.0, firstOut[2], 0.000001)
    }

    @Test
    fun storedTangentsWinWhenAutoIsOff() {
        val keys = listOf(
            positionKey(0, 0.0, 0.0),
            positionKey(15, 100.0, 0.0,
                spatialIn = listOf(0.0, -60.0, 0.0), spatialOut = listOf(0.0, -60.0, 0.0),
                spatialAuto = false),
            positionKey(30, 200.0, 0.0)
        )
        val (tin, tout) = MotionPath.effectiveTangents(keys, 1)
        assertEquals(listOf(0.0, -60.0, 0.0), tin)
        assertEquals(listOf(0.0, -60.0, 0.0), tout)
    }

    @Test
    fun sampledPathBendsOffTheChord() {
        val keys = listOf(
            positionKey(0, 0.0, 0.0),
            positionKey(15, 100.0, 0.0,
                spatialIn = listOf(0.0, -60.0, 0.0), spatialOut = listOf(0.0, -60.0, 0.0),
                spatialAuto = false),
            positionKey(30, 200.0, 0.0)
        )
        val path = MotionPath.samplePath(keys, stepsPerSegment = 8)
        assertEquals(0.0, path.first().x, 0.001)
        assertEquals(200.0, path.last().x, 0.001)
        // Both legs bend upward: the lowest point sits well above the y=0 chord.
        val minY = path.minOf { it.y }
        assertTrue("bent path lowest y $minY must sit well above the chord", minY < -10.0)
        // And the second leg specifically bends: some point between the
        // middle and last keys sits well above the chord.
        assertTrue(
            "bent second leg must dip below the chord",
            path.any { it.x > 100.0 && it.x < 200.0 && it.y < -10.0 }
        )
    }

    @Test
    fun sampledPathIsEmptyForFewerThanTwoKeys() {
        assertTrue(MotionPath.samplePath(emptyList()).isEmpty())
        assertTrue(MotionPath.samplePath(listOf(positionKey(0, 0.0, 0.0))).isEmpty())
    }

    @Test
    fun onlyPositionAndAnchorAreSpatial() {
        assertTrue(MotionPath.isSpatialProperty("position"))
        assertTrue(MotionPath.isSpatialProperty("anchor"))
        assertEquals(false, MotionPath.isSpatialProperty("scale"))
        assertEquals(false, MotionPath.isSpatialProperty("rotation"))
        assertEquals(false, MotionPath.isSpatialProperty("opacity"))
    }
}
