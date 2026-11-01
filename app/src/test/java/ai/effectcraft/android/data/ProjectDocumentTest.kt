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
    fun newShapeHasStableIdentityAndEditableOpacity() {
        val first = LayerDocument.newShape(1)
        val second = LayerDocument.newShape(2)

        assertNotEquals(first.id, second.id)
        assertEquals("Shape", first.kind)
        assertEquals(1f, first.opacity, 0f)
    }
}
