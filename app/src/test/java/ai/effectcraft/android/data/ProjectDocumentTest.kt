package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDocumentTest {
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

}
