package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDocumentTest {
    @Test
    fun playbackFrameIntervalTracksCompositionFrameRateAndBoundsInvalidValues() {
        assertEquals(1_000L, ProjectEditorActions.frameIntervalMillis(1))
        assertEquals(33L, ProjectEditorActions.frameIntervalMillis(30))
        assertEquals(17L, ProjectEditorActions.frameIntervalMillis(60))
        assertEquals(8L, ProjectEditorActions.frameIntervalMillis(120))
        assertEquals(1_000L, ProjectEditorActions.frameIntervalMillis(0))
        assertEquals(8L, ProjectEditorActions.frameIntervalMillis(240))
    }

    @Test
    fun starterCompositionUsesVerticalVideoDefaults() {
        val project = ProjectDocument.starter()

        assertEquals(1080, project.width)
        assertEquals(1920, project.height)
        assertEquals(30, project.frameRate)
        assertEquals(150, project.durationFrames)
        assertTrue(project.layers.isNotEmpty())
    }

    @Test
    fun isUneditedStarterAcceptsFreshStarterDespiteRandomIds() {
        // Two starters never share ids or timestamps, yet both are unedited.
        assertTrue(ProjectDocument.isUneditedStarter(ProjectDocument.starter()))
        assertTrue(ProjectDocument.isUneditedStarter(ProjectDocument.starter()))
    }

    @Test
    fun isUneditedStarterRejectsAnyEdit() {
        val starter = ProjectDocument.starter()
        assertFalse(
            ProjectDocument.isUneditedStarter(
                starter.copy(layers = starter.layers + LayerDocument.newShape(starter.layers.size + 1))
            )
        )
        assertFalse(
            ProjectDocument.isUneditedStarter(
                starter.copy(layers = starter.layers.mapIndexed { index, layer ->
                    if (index == 0) layer.copy(opacity = 0.5f) else layer
                })
            )
        )
        assertFalse(ProjectDocument.isUneditedStarter(starter.copy(name = "Renamed")))
        assertFalse(ProjectDocument.isUneditedStarter(starter.copy(width = 1920)))
    }

    @Test
    fun movingLayerPreservesIdentityAndChangesOrderWithinBounds() {
        val layers = ProjectDocument.starter().layers
        val moved = ProjectEditorActions.moveLayer(layers, layers[2].id, -2)

        assertEquals(layers[2].id, moved.first().id)
        assertEquals(layers[0].id, moved[1].id)
        assertEquals(layers[1].id, moved[2].id)
        assertEquals(layers.map { it.id }.toSet(), moved.map { it.id }.toSet())
    }

    @Test
    fun movingLayerClampsAtEdgesAndIgnoresUnknownIds() {
        val layers = ProjectDocument.starter().layers
        assertEquals(layers, ProjectEditorActions.moveLayer(layers, layers.first().id, -1))
        assertEquals(layers, ProjectEditorActions.moveLayer(layers, "missing", 1))
        assertEquals(layers.first().id, ProjectEditorActions.moveLayer(layers, layers.first().id, 99).last().id)
    }

    @Test
    fun newShapeHasStableIdentityAndEditableOpacity() {
        val first = LayerDocument.newShape(1)
        val second = LayerDocument.newShape(2)

        assertNotEquals(first.id, second.id)
        assertEquals("Shape", first.kind)
        assertEquals(1f, first.opacity, 0f)
    }

    @Test
    fun renamingLayerTrimsNameAndPreservesStableIdentity() {
        val layers = ProjectDocument.starter().layers
        val target = layers[1]
        val renamed = ProjectEditorActions.renameLayer(layers, target.id, "  Main title  ")

        assertEquals("Main title", renamed[1].name)
        assertEquals(target.id, renamed[1].id)
        assertEquals(layers[0], renamed[0])
    }

    @Test
    fun renamingLayerRejectsBlankAndUnknownTargets() {
        val layers = ProjectDocument.starter().layers
        assertEquals(layers, ProjectEditorActions.renameLayer(layers, layers[0].id, "  "))
        assertEquals(layers, ProjectEditorActions.renameLayer(layers, "missing", "New name"))
    }

    @Test
    fun deletingLayerPreservesIdentityAndNeverLeavesEmptyComposition() {
        val layers = ProjectDocument.starter().layers
        val removed = ProjectEditorActions.deleteLayer(layers, layers[1].id)
        assertEquals(2, removed.size)
        assertEquals(setOf(layers[0].id, layers[2].id), removed.map { it.id }.toSet())

        val onlyLayer = listOf(layers[0])
        assertEquals(onlyLayer, ProjectEditorActions.deleteLayer(onlyLayer, layers[0].id))
        assertEquals(layers, ProjectEditorActions.deleteLayer(layers, "unknown"))
    }

    @Test
    fun deletingSelectedLayerSelectsAdjacentLayerAndPreservesSelectionWhenDeletionIsRejected() {
        val layers = ProjectDocument.starter().layers

        val (afterFirst, firstSelection) = ProjectEditorActions.deleteLayerAndSelection(layers, layers.first().id)
        assertEquals(2, afterFirst.size)
        assertEquals(layers[1].id, firstSelection)

        val (afterMiddle, middleSelection) = ProjectEditorActions.deleteLayerAndSelection(layers, layers[1].id)
        assertEquals(2, afterMiddle.size)
        assertEquals(layers[2].id, middleSelection)

        val (unchanged, onlySelection) = ProjectEditorActions.deleteLayerAndSelection(listOf(layers.first()), layers.first().id)
        assertEquals(listOf(layers.first()), unchanged)
        assertEquals(layers.first().id, onlySelection)
    }

    @Test
    fun visibilityAndOpacityReducersPreserveOtherLayerValuesAndClampOpacity() {
        val layers = ProjectDocument.starter().layers
        val target = layers[1]
        val hidden = ProjectEditorActions.setLayerVisibility(layers, target.id, false)
        assertEquals(false, hidden[1].visible)
        assertEquals(target.id, hidden[1].id)
        assertEquals(layers[0], hidden[0])

        val half = ProjectEditorActions.setLayerOpacity(layers, target.id, 0.5f)
        assertEquals(0.5f, half[1].opacity, 0f)
        assertEquals(1f, ProjectEditorActions.setLayerOpacity(layers, target.id, 5f)[1].opacity, 0f)
        assertEquals(layers, ProjectEditorActions.setLayerOpacity(layers, target.id, Float.NaN))
        assertEquals(layers, ProjectEditorActions.setLayerOpacity(layers, "unknown", 0.25f))
    }
    @Test
    fun trimmingLayerSetsInclusiveRangeAndPreservesOtherLayers() {
        val layers = ProjectDocument.starter().layers
        val target = layers[1]
        val trimmed = ProjectEditorActions.trimLayer(layers, target.id, 30, 89, 150)

        assertEquals(30, trimmed[1].inFrame)
        assertEquals(89, trimmed[1].outFrame)
        assertEquals(60, trimmed[1].trimmedDurationFrames(150))
        assertTrue(trimmed[1].isTrimmed(150))
        assertEquals(layers[0], trimmed[0])
        assertEquals(layers[2], trimmed[2])
        assertTrue(trimmed[1].isActiveAt(30, 150))
        assertTrue(trimmed[1].isActiveAt(89, 150))
        assertTrue(!trimmed[1].isActiveAt(29, 150))
        assertTrue(!trimmed[1].isActiveAt(90, 150))
    }

    @Test
    fun trimmingClampsOutOfRangeBoundsAndCollapsesReversedRange() {
        val layers = ProjectDocument.starter().layers
        val target = layers[0]
        val clamped = ProjectEditorActions.trimLayer(layers, target.id, -5, 999, 150)
        assertEquals(0, clamped[0].inFrame)
        assertEquals(149, clamped[0].outFrame)

        val reversed = ProjectEditorActions.trimLayer(layers, target.id, 100, 40, 150)
        assertEquals(100, reversed[0].inFrame)
        assertEquals(100, reversed[0].outFrame)
        assertEquals(1, reversed[0].trimmedDurationFrames(150))

        val pastEnd = ProjectEditorActions.trimLayer(layers, target.id, 500, null, 150)
        assertEquals(149, pastEnd[0].inFrame)
        assertEquals(null, pastEnd[0].outFrame)
        assertEquals(1, pastEnd[0].trimmedDurationFrames(150))
    }

    @Test
    fun trimmingIgnoresUnknownIdsAndNoOpTrims() {
        val layers = ProjectDocument.starter().layers
        assertEquals(layers, ProjectEditorActions.trimLayer(layers, "missing", 10, 20, 150))
        assertEquals(layers, ProjectEditorActions.trimLayer(layers, layers[0].id, 0, null, 150))
        assertEquals(layers, ProjectEditorActions.trimLayer(layers, layers[0].id, 10, 20, 0))
    }

    @Test
    fun clearingTrimRestoresFullRangeAndIgnoresUnknownIds() {
        val layers = ProjectDocument.starter().layers
        val target = layers[1]
        val trimmed = ProjectEditorActions.trimLayer(layers, target.id, 30, 89, 150)
        val cleared = ProjectEditorActions.clearTrim(trimmed, target.id)
        assertEquals(0, cleared[1].inFrame)
        assertEquals(null, cleared[1].outFrame)
        assertTrue(!cleared[1].isTrimmed(150))
        assertEquals(150, cleared[1].trimmedDurationFrames(150))
        assertEquals(trimmed, ProjectEditorActions.clearTrim(trimmed, "missing"))
        assertEquals(layers, ProjectEditorActions.clearTrim(layers, target.id))
    }

    @Test
    fun activeLayersAtPlayheadRespectsVisibilityAndTrim() {
        val layers = ProjectDocument.starter().layers
        val target = layers[1]
        val trimmed = ProjectEditorActions.trimLayer(layers, target.id, 30, 89, 150)
        assertEquals(3, ProjectEditorActions.activeLayersAt(trimmed, 50, 150).size)
        assertEquals(2, ProjectEditorActions.activeLayersAt(trimmed, 10, 150).size)
        assertTrue(ProjectEditorActions.activeLayersAt(trimmed, 10, 150).none { it.id == target.id })

        val hidden = ProjectEditorActions.setLayerVisibility(trimmed, layers[0].id, false)
        assertEquals(2, ProjectEditorActions.activeLayersAt(hidden, 50, 150).size)
    }

    @Test
    fun trimmedExportRangeIntersectsRequestWithVisibleSpans() {
        val layers = ProjectDocument.starter().layers
        val trimmed = ProjectEditorActions.trimLayer(layers, layers[0].id, 30, 89, 150)
        // The other two layers still span the full composition, so the union stays full.
        assertEquals(0..149, ProjectEditorActions.trimmedExportRange(trimmed, 0, 149, 150))
        assertEquals(40..60, ProjectEditorActions.trimmedExportRange(trimmed, 40, 60, 150))

        val allTrimmed = ProjectEditorActions.trimLayer(
            ProjectEditorActions.trimLayer(
                ProjectEditorActions.trimLayer(layers, layers[0].id, 30, 89, 150),
                layers[1].id, 100, 120, 150
            ),
            layers[2].id, 110, 130, 150
        )
        assertEquals(30..130, ProjectEditorActions.trimmedExportRange(allTrimmed, 0, 149, 150))
        assertEquals(100..120, ProjectEditorActions.trimmedExportRange(allTrimmed, 100, 120, 150))
        assertEquals(30..89, ProjectEditorActions.trimmedExportRange(allTrimmed, 0, 89, 150))
        assertEquals(null, ProjectEditorActions.trimmedExportRange(allTrimmed, 0, 29, 150))

        val hidden = ProjectEditorActions.setLayerVisibility(allTrimmed, layers[0].id, false)
        assertEquals(100..130, ProjectEditorActions.trimmedExportRange(hidden, 0, 149, 150))
        val allHidden = hidden.map { it.copy(visible = false) }
        assertEquals(null, ProjectEditorActions.trimmedExportRange(allHidden, 0, 149, 150))
        assertEquals(null, ProjectEditorActions.trimmedExportRange(layers, 10, 5, 150))
    }

    @Test
    fun projectValidationRejectsTrimOutsideComposition() {
        val starter = ProjectDocument.starter()
        val badIn = starter.copy(layers = listOf(starter.layers.first().copy(inFrame = 150)))
        assertTrue(badIn.validationErrors().any { it.contains("in-frame") })
        val badOut = starter.copy(layers = listOf(starter.layers.first().copy(inFrame = 10, outFrame = 9)))
        assertTrue(badOut.validationErrors().any { it.contains("out-frame") })
        val badOutEnd = starter.copy(layers = listOf(starter.layers.first().copy(outFrame = 150)))
        assertTrue(badOutEnd.validationErrors().any { it.contains("out-frame") })
        val openEnded = starter.copy(layers = listOf(starter.layers.first().copy(inFrame = 10, outFrame = null)))
        assertTrue(openEnded.validationErrors().isEmpty())
        assertEquals(140, openEnded.layers.first().trimmedDurationFrames(150))
    }

}
