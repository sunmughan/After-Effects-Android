package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PropertyTrackEditorTest {
    @Test
    fun upsertKeepsKeysSortedAndReplacesSameFrame() {
        val track = PropertyTrack("opacity", emptyList())
        val edited = PropertyTrackEditor.upsert(
            PropertyTrackEditor.upsert(track, 20, 1f), 0, 0f
        )
        val replaced = PropertyTrackEditor.upsert(edited, 20, 0.5f)
        assertEquals(listOf(0, 20), replaced.keyframes.map { it.frame })
        assertEquals(0.5f, replaced.keyframes.last().value, 0f)
    }

    @Test
    fun interpolationIsLinearAndHoldsEndpoints() {
        val track = PropertyTrack("position.x", listOf(
            PropertyKeyframe(10, 2f),
            PropertyKeyframe(30, 22f)
        ))
        assertEquals(2f, PropertyTrackEditor.evaluate(track, -5.0)!!, 0f)
        assertEquals(7f, PropertyTrackEditor.evaluate(track, 15.0)!!, 0.0001f)
        assertEquals(12f, PropertyTrackEditor.evaluate(track, 20.0)!!, 0.0001f)
        assertEquals(22f, PropertyTrackEditor.evaluate(track, 45.0)!!, 0f)
    }

    @Test
    fun removeAndEmptyTrackAreDeterministic() {
        val track = PropertyTrack("rotation", listOf(
            PropertyKeyframe(0, 0f), PropertyKeyframe(10, 90f)
        ))
        assertEquals(listOf(0), PropertyTrackEditor.remove(track, 10).keyframes.map { it.frame })
        assertNull(PropertyTrackEditor.evaluate(PropertyTrack("rotation", emptyList()), 0.0))
    }

    @Test
    fun rejectsInvalidKeyframeInputs() {
        val track = PropertyTrack("opacity", emptyList())
        assertThrows(IllegalArgumentException::class.java) { PropertyTrackEditor.upsert(track, -1, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PropertyTrackEditor.upsert(track, 0, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PropertyTrackEditor.evaluate(track, Double.NaN) }
    }
}
