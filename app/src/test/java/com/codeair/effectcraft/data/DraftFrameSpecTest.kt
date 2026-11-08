package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 2 (P06): the draft preview draws the draft layers as a
 * full-frame stack, in list order, with opacity applied to the tint
 * alpha and Text layers carrying their name as centred text.
 */
class DraftFrameSpecTest {

    @Test
    fun starterRendersAllThreeLayersInOrderWithOpacity() {
        val draws = draftFrameLayers(ProjectDocument.starter(), 0)
        assertEquals(3, draws.size)
        assertEquals("Background", draws[0].layerName)
        assertEquals(0xFF56647A.toInt(), draws[0].colorArgb)
        assertNull(draws[0].text)
        assertEquals("Title / Main", draws[1].layerName)
        assertEquals("Title / Main", draws[1].text)
        assertEquals(0xFF9278F6.toInt(), draws[1].colorArgb)
        assertEquals("Accent Shapes", draws[2].layerName)
        // 0.82 opacity -> alpha 209 (0xD1).
        assertEquals(0xD145C8B5.toInt(), draws[2].colorArgb)
        assertNull(draws[2].text)
    }

    @Test
    fun invisibleLayersAreSkipped() {
        val project = ProjectDocument.starter().copy(
            layers = ProjectDocument.starter().layers.mapIndexed { index, layer ->
                if (index == 0) layer.copy(visible = false) else layer
            }
        )
        val draws = draftFrameLayers(project, 0)
        assertEquals(2, draws.size)
        assertEquals("Title / Main", draws[0].layerName)
    }

    @Test
    fun layersTrimmedOutAtTheFrameAreSkipped() {
        val layer = LayerDocument.newShape(1).copy(
            inFrame = 10,
            outFrame = 20,
            clips = listOf(ClipDocument.fullRange(150))
        )
        val project = ProjectDocument.starter().copy(layers = listOf(layer), durationFrames = 150)
        assertTrue(draftFrameLayers(project, 0).isEmpty())
        assertEquals(1, draftFrameLayers(project, 15).size)
    }

    @Test
    fun opacityKeyframeOverrideReplacesLayerOpacity() {
        val starter = ProjectDocument.starter()
        val layerId = starter.layers[0].id
        val draws = draftFrameLayers(starter, 0, mapOf(layerId to 0.5f))
        assertEquals(0x7F56647A.toInt(), draws[0].colorArgb)
    }

    @Test
    fun emptyProjectDrawsNothing() {
        val project = ProjectDocument.starter().copy(layers = emptyList())
        assertTrue(draftFrameLayers(project, 0).isEmpty())
    }
}
