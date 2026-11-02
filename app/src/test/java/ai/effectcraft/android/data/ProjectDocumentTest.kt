package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
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
    fun projectValidationRejectsBrokenOfflineCompositionInvariants() {
        val starter = ProjectDocument.starter()
        assertTrue(starter.validationErrors().isEmpty())

        val invalid = starter.copy(
            width = 100,
            frameRate = 240,
            durationFrames = 0,
            layers = listOf(starter.layers.first().copy(opacity = Float.NaN))
        )
        val errors = invalid.validationErrors()
        assertTrue(errors.any { it.contains("width") })
        assertTrue(errors.any { it.contains("Frame rate") })
        assertTrue(errors.any { it.contains("Duration") })
        assertTrue(errors.any { it.contains("opacity") })

        assertTrue(starter.copy(layers = emptyList()).validationErrors().any { it.contains("at least one layer") })
        assertTrue(starter.copy(layers = listOf(starter.layers[0], starter.layers[0])).validationErrors().any { it.contains("unique") })
    }

}
