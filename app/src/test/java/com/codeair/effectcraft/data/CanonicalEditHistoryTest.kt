package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalEditHistoryTest {
    private fun snapshot(json: String, vararg selected: Long): CanonicalEditSnapshot {
        return CanonicalEditSnapshot(json, selected.toSet())
    }

    @Test
    fun undoRestoresPriorProjectAndSelection() {
        val undo = listOf(snapshot("project-a", 2), snapshot("project-b", 2, 3))
        val current = snapshot("project-c", 3)

        val step = CanonicalEditHistory.undo(undo, emptyList(), current)

        checkNotNull(step)
        assertEquals("project-b", step.snapshot.projectJson)
        assertEquals(setOf(2L, 3L), step.snapshot.selectedLayerIds)
        assertEquals(listOf(snapshot("project-a", 2)), step.undoStack)
        assertEquals(listOf(current), step.redoStack)
    }

    @Test
    fun redoRestoresLaterProjectAndSelection() {
        val redo = listOf(snapshot("project-b", 2, 3))
        val current = snapshot("project-a", 2)

        val step = CanonicalEditHistory.redo(emptyList(), redo, current)

        checkNotNull(step)
        assertEquals("project-b", step.snapshot.projectJson)
        assertEquals(setOf(2L, 3L), step.snapshot.selectedLayerIds)
        assertEquals(listOf(current), step.undoStack)
        assertEquals(emptyList<CanonicalEditSnapshot>(), step.redoStack)
    }

    @Test
    fun undoAtStartOfHistoryReturnsNull() {
        val current = snapshot("project-a", 2)

        assertNull(CanonicalEditHistory.undo(emptyList(), emptyList(), current))
    }

    @Test
    fun redoAtEndOfHistoryReturnsNull() {
        val current = snapshot("project-a", 2)

        assertNull(CanonicalEditHistory.redo(emptyList(), emptyList(), current))
    }

    @Test
    fun undoThenRedoRoundTripsThroughBothStates() {
        val first = snapshot("project-a", 2)
        val second = snapshot("project-b", 3)
        val current = snapshot("project-c", 3)
        val undo = listOf(first, second)

        val undone = checkNotNull(CanonicalEditHistory.undo(undo, emptyList(), current))
        assertEquals(second, undone.snapshot)
        val redone = checkNotNull(CanonicalEditHistory.redo(undone.undoStack, undone.redoStack, undone.snapshot))
        assertEquals(current, redone.snapshot)
        assertEquals(undo, redone.undoStack)
    }

    @Test
    fun recordCapsHistoryAtFiftyEntries() {
        var stack = emptyList<CanonicalEditSnapshot>()
        repeat(55) { index -> stack = CanonicalEditHistory.record(stack, snapshot("project-$index", 2)) }

        assertEquals(50, stack.size)
        assertEquals("project-5", stack.first().projectJson)
        assertEquals("project-54", stack.last().projectJson)
    }
}
