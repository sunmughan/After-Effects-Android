package com.codeair.effectcraft.data

/**
 * One draft-editor undo/redo step: the whole draft document before an edit,
 * plus the layer selection and playhead frame that belonged to that moment.
 * Restoring a step restores all three, exactly like the in-memory
 * [EditorHistorySnapshot] in the UI layer, but in a form the recovery
 * journal can persist across process death.
 */
data class EditorSessionSnapshot(
    val document: ProjectDocument,
    val selectedLayerId: String,
    val playheadFrame: Int
)

/**
 * The editor session state that must survive a crash or close alongside the
 * journaled document: both draft undo/redo stacks, the current draft
 * selection and playhead, and both canonical undo/redo stacks plus the
 * canonical layer selection.
 *
 * [validatedAgainst] is the single place that makes restored state safe to
 * apply: snapshots for another project or failing document validation are
 * dropped, playheads are coerced into the recovered document's frame range,
 * a selection naming a layer that no longer exists falls back to the first
 * layer, and every stack is capped at [CanonicalEditHistory.MAX_ENTRIES].
 * Canonical snapshots carry their own canonical project JSON, which this
 * draft document cannot validate, so only blank sources are dropped there;
 * the UI prunes canonical ids against the parsed canonical layers on
 * render, as it already does for live selection changes.
 */
data class EditorSessionState(
    val undoStack: List<EditorSessionSnapshot> = emptyList(),
    val redoStack: List<EditorSessionSnapshot> = emptyList(),
    val selectedLayerId: String = "",
    val playheadFrame: Int = 0,
    val canonicalUndoStack: List<CanonicalEditSnapshot> = emptyList(),
    val canonicalRedoStack: List<CanonicalEditSnapshot> = emptyList(),
    val canonicalSelectedIds: Set<Long> = emptySet()
) {
    fun validatedAgainst(document: ProjectDocument): EditorSessionState {
        val maxFrame = (document.durationFrames - 1).coerceAtLeast(0)
        val firstLayerId = document.layers.firstOrNull()?.id.orEmpty()
        fun validSnapshots(stack: List<EditorSessionSnapshot>): List<EditorSessionSnapshot> =
            stack.filter { it.document.id == document.id && it.document.validationErrors().isEmpty() }
                .takeLast(CanonicalEditHistory.MAX_ENTRIES)
                .map {
                    it.copy(
                        selectedLayerId = it.selectedLayerId
                            .takeIf { id -> document.layers.any { layer -> layer.id == id } } ?: firstLayerId,
                        playheadFrame = it.playheadFrame.coerceIn(0, maxFrame)
                    )
                }
        return copy(
            undoStack = validSnapshots(undoStack),
            redoStack = validSnapshots(redoStack),
            selectedLayerId = selectedLayerId
                .takeIf { id -> document.layers.any { layer -> layer.id == id } } ?: firstLayerId,
            playheadFrame = playheadFrame.coerceIn(0, maxFrame),
            canonicalUndoStack = canonicalUndoStack
                .filter { it.projectJson.isNotBlank() }.takeLast(CanonicalEditHistory.MAX_ENTRIES),
            canonicalRedoStack = canonicalRedoStack
                .filter { it.projectJson.isNotBlank() }.takeLast(CanonicalEditHistory.MAX_ENTRIES)
        )
    }

    companion object {
        val EMPTY = EditorSessionState()
    }
}
