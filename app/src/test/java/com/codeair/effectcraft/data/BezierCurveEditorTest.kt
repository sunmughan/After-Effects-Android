package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BezierCurveEditorTest {

    private fun twoKeyTrack(property: String = "position"): CurveTrack {
        val left = CurveKey(0, 0.0, outHandle = CurveHandle(0.0, 0.75))
        val right = CurveKey(30, 90.0, inHandle = CurveHandle(0.0, 0.75))
        return CurveTrack(property, listOf(left, right))
    }

    @Test
    fun bezierGraphSamplesEaseAwayFromLinear() {
        val sampled = BezierCurveEditor.sample(twoKeyTrack(), samplesPerSegment = 4, framesPerSecond = 30.0)
        assertEquals(0.0, sampled.first().value, 0.000001)
        assertEquals(90.0, sampled.last().value, 0.000001)
        // Zero-speed handles on both sides make an S-curve: slow at the start,
        // so the quarter point sits below the linear 22.5 value.
        val quarter = sampled.first { it.frame == 7.5 }
        assertTrue("quarter sample ${quarter.value} must sit below linear 22.5", quarter.value < 22.5)
        assertTrue("quarter sample must stay above 0", quarter.value > 0.0)
        val values = sampled.map { it.value }
        assertEquals(values, values.sorted())
    }

    @Test
    fun holdSegmentHoldsLeftValueAcrossSamples() {
        val track = CurveTrack("opacity", listOf(
            CurveKey(0, 40.0, outInterp = CurveInterp.HOLD),
            CurveKey(30, 80.0)
        ))
        val sampled = BezierCurveEditor.sample(track, samplesPerSegment = 4, framesPerSecond = 30.0)
        assertTrue(sampled.dropLast(1).all { it.value == 40.0 })
        assertEquals(80.0, sampled.last().value, 0.0)
    }

    @Test
    fun firstKeyHasNoIncomingHandleAndLastKeyHasNoOutgoingHandle() {
        val track = twoKeyTrack()
        assertThrows(IllegalArgumentException::class.java) {
            BezierCurveEditor.withHandle(track, 0, "incoming", CurveHandle(0.0, 0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BezierCurveEditor.withHandle(track, 30, "outgoing", CurveHandle(0.0, 0.5))
        }
        val firstOutgoing = BezierCurveEditor.withHandle(track, 0, "outgoing", CurveHandle(10.0, 0.5))
        assertEquals(CurveInterp.BEZIER, firstOutgoing.keys[0].outInterp)
        assertEquals(0.5, firstOutgoing.keys[0].outHandle!!.influence, 0.0)
        val lastIncoming = BezierCurveEditor.withHandle(track, 30, "incoming", CurveHandle(10.0, 0.5))
        assertEquals(CurveInterp.BEZIER, lastIncoming.keys[1].inInterp)
        val cleared = BezierCurveEditor.withHandle(firstOutgoing, 0, "outgoing", null)
        assertEquals(CurveInterp.LINEAR, cleared.keys[0].outInterp)
        assertEquals(null, cleared.keys[0].outHandle)
    }

    @Test
    fun dragPastNeighborReordersAndPreservesHandles() {
        val track = CurveTrack("scale", listOf(
            CurveKey(0, 100.0, outHandle = CurveHandle(0.0, 0.5)),
            CurveKey(10, 150.0, inHandle = CurveHandle(0.0, 0.5), outHandle = CurveHandle(0.0, 0.5)),
            CurveKey(20, 200.0, inHandle = CurveHandle(0.0, 0.5))
        ))
        val past = BezierCurveEditor.move(track, 10, 25)
        assertEquals(listOf(0, 20, 25), past.keys.map { it.frame })
        val dragged = past.keys.last()
        assertEquals(150.0, dragged.value, 0.0)
        assertEquals(0.5, dragged.inHandle!!.influence, 0.0)
        assertEquals(0.5, dragged.outHandle!!.influence, 0.0)
        val onto = BezierCurveEditor.move(track, 10, 20)
        assertEquals(listOf(0, 20), onto.keys.map { it.frame })
        assertEquals(150.0, onto.keys.last().value, 0.0)
    }

    @Test
    fun dragRejectsNegativeTargetAndMissingKey() {
        val track = twoKeyTrack()
        assertThrows(IllegalArgumentException::class.java) { BezierCurveEditor.move(track, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { BezierCurveEditor.move(track, 15, 20) }
    }

    @Test
    fun handleFromPointerComputesInfluenceAndSpeed() {
        // Pointer halfway along a 30-frame segment, a quarter of the way up
        // the 90-unit value delta: influence 0.5, normalised speed 0.5, so
        // speed is 0.5 * 90 / 1 second = 45 units per second.
        val handle = BezierCurveEditor.handleFromPointer(
            keyFrame = 0, keyValue = 0.0, neighborFrame = 30, neighborValue = 90.0,
            pointerFrame = 15.0, pointerValue = 22.5, framesPerSecond = 30.0, side = "outgoing"
        )
        assertEquals(0.5, handle.influence, 0.000001)
        assertEquals(45.0, handle.speed, 0.000001)
    }

    @Test
    fun handleFromPointerOnFallingSegmentKeepsSpeedWithSegmentDirection() {
        val handle = BezierCurveEditor.handleFromPointer(
            keyFrame = 0, keyValue = 90.0, neighborFrame = 30, neighborValue = 0.0,
            pointerFrame = 15.0, pointerValue = 67.5, framesPerSecond = 30.0, side = "outgoing"
        )
        assertEquals(0.5, handle.influence, 0.000001)
        assertEquals(-45.0, handle.speed, 0.000001)
    }

    @Test
    fun handleFromPointerAcrossKeyValueFlattensHandle() {
        val handle = BezierCurveEditor.handleFromPointer(
            keyFrame = 0, keyValue = 0.0, neighborFrame = 30, neighborValue = 90.0,
            pointerFrame = 15.0, pointerValue = -10.0, framesPerSecond = 30.0, side = "outgoing"
        )
        assertEquals(0.5, handle.influence, 0.000001)
        assertEquals(0.0, handle.speed, 0.0)
    }

    @Test
    fun graphEditDragAndHandlesWorkForEveryNamedFamily() {
        val families = listOf("position", "scale", "rotation", "opacity", "param:12345")
        for (family in families) {
            val track = twoKeyTrack(family)
            val edited = BezierCurveEditor.withHandle(track, 30, "incoming", CurveHandle(5.0, 0.25))
            assertEquals(CurveInterp.BEZIER, edited.keys[1].inInterp)
            val dragged = BezierCurveEditor.move(edited, 30, 40)
            assertEquals(listOf(0, 40), dragged.keys.map { it.frame })
            val sampled = BezierCurveEditor.sample(dragged, samplesPerSegment = 2, framesPerSecond = 30.0)
            assertEquals(0.0, sampled.first().value, 0.0)
            assertEquals(90.0, sampled.last().value, 0.0)
        }
    }

    @Test
    fun easeProgressMatchesLinearWhenHandlesAreLinear() {
        assertEquals(0.25, BezierCurveEditor.easeProgress(1.0, 1.0 / 3.0, 1.0, 1.0 / 3.0, 0.25), 0.000001)
        assertEquals(0.5, BezierCurveEditor.easeProgress(1.0, 1.0 / 3.0, 1.0, 1.0 / 3.0, 0.5), 0.000001)
    }

    @Test
    fun rejectsInvalidHandleValues() {
        assertThrows(IllegalArgumentException::class.java) { CurveHandle(Double.NaN, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { CurveHandle(0.0, 1.5) }
        assertThrows(IllegalArgumentException::class.java) { CurveHandle(0.0, -0.1) }
    }
}
