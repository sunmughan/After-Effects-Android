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
        assertEquals(listOf(-200.0 / 6.0, 0.0, 0.0), tin)
        assertEquals(listOf(200.0 / 6.0, 0.0, 0.0), tout)
        // End keys use their one neighbour.
        val (_, firstOut) = MotionPath.effectiveTangents(keys, 0)
        assertEquals(listOf(100.0 / 6.0, 100.0 / 6.0, 0.0), firstOut)
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
        // Mid-segment of the bent leg sits well above the y=0 chord.
        val mid = path.minByOrNull { it.y }!!
        assertTrue("bent path lowest y ${mid.y} must sit well above the chord", mid.y < -10.0)
        assertTrue("bent path midpoint x ${mid.x} must stay between its keys", mid.x > 100.0 && mid.x < 200.0)
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
