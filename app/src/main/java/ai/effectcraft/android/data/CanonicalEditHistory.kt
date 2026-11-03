package ai.effectcraft.android.data

/**
 * One canonical undo/redo step: the full canonical project source before an
 * edit, plus the layer selection that was active at that moment. Undo and
 * redo restore both, so an undone edit restores the prior canonical state
 * and preview, and a redone edit restores the later canonical state, with
 * the selection that belonged to each state.
 */
data class CanonicalEditSnapshot(
    val projectJson: String,
    val selectedLayerIds: Set<Long>
)

/** The stacks and the snapshot one undo or redo step moves to. Null step means the boundary was reached. */
data class CanonicalHistoryStep(
    val undoStack: List<CanonicalEditSnapshot>,
    val redoStack: List<CanonicalEditSnapshot>,
    val snapshot: CanonicalEditSnapshot
)

/**
 * Pure canonical history: stacks of whole-project snapshots, bounded like
 * the draft editor history. Recording a step never clears the redo stack;
 * the caller clears redo when a new edit lands, so a failed edit cannot
 * strand the redo stack. Undo at the start and redo at the end return null
 * and leave both stacks untouched.
 */
object CanonicalEditHistory {
    const val MAX_ENTRIES = 50

    fun record(undoStack: List<CanonicalEditSnapshot>, snapshot: CanonicalEditSnapshot): List<CanonicalEditSnapshot> {
        return (undoStack + snapshot).takeLast(MAX_ENTRIES)
    }

    fun undo(
        undoStack: List<CanonicalEditSnapshot>,
        redoStack: List<CanonicalEditSnapshot>,
        current: CanonicalEditSnapshot
    ): CanonicalHistoryStep? {
        if (undoStack.isEmpty()) return null
        return CanonicalHistoryStep(
            undoStack = undoStack.dropLast(1),
            redoStack = (redoStack + current).takeLast(MAX_ENTRIES),
            snapshot = undoStack.last()
        )
    }

    fun redo(
        undoStack: List<CanonicalEditSnapshot>,
        redoStack: List<CanonicalEditSnapshot>,
        current: CanonicalEditSnapshot
    ): CanonicalHistoryStep? {
        if (redoStack.isEmpty()) return null
        return CanonicalHistoryStep(
            undoStack = (undoStack + current).takeLast(MAX_ENTRIES),
            redoStack = redoStack.dropLast(1),
            snapshot = redoStack.last()
        )
    }
}
