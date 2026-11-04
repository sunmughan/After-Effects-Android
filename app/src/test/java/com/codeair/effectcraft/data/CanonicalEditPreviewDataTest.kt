package com.codeair.effectcraft.data

import com.codeair.effectcraft.engine.RenderedFrameCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM leg of M4 #9 ("Verify every edit updates engine preview and
 * canonical project data"): the canonical data the app displays and the
 * preview session renders from is the parsed metadata of the edited
 * project, so for every edit type this test asserts that an edited
 * metadata document parses to exactly the edited values. The matching
 * instrumented suite (CanonicalEditPreviewSyncTest) applies the same
 * edits through the real native bridge and renders the edited project
 * through a real preview session; this file pins the parse side that
 * the JVM leg can prove without native code.
 *
 * Each edited document below is written in the exact shape native
 * inspect returns after that edit (same field names, same units:
 * position/scale in composition units/percent, angles in degrees,
 * opacity in percent, keyframe frames in composition frames).
 */
class CanonicalEditPreviewDataTest {
    private fun layerJson(): String {
        return """{"valid":true,"compositions":[{"id":10,"name":"Main","width":320,"height":180,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[
            {"id":2,"name":"Title","source":"Text","visible":true,"opacityPercent":100.0,"position":[160.0,90.0],"scale":[100.0,100.0],"rotationDegrees":0.0,"anchor":[0.0,0.0],"orientation":[0.0,0.0,0.0],"rotationXDegrees":0.0,"rotationYDegrees":0.0,"locked":false,"threeD":false,
             "text":{"text":"Preview","font":"Inter","size":72.0,"tracking":0.0},
             "shapeItems":[{"name":"Rectangle Path 1","kind":"rect","scalars":[{"uid":90013,"name":"Roundness","value":0.0}],"vecs":[{"uid":90012,"name":"Size","components":[200.0,100.0]}]}],
             "effects":[{"name":"Gaussian Blur","kind":"gaussianBlur","scalars":[{"uid":90021,"name":"Blurriness","value":0.0}],"vecs":[{"uid":90022,"name":"Center","components":[0.0,0.0]}]}]},
            {"id":3,"name":"Second","source":"Text","visible":true,"opacityPercent":100.0,"position":[160.0,90.0],"scale":[100.0,100.0],"rotationDegrees":0.0,"locked":false}
        ]}]}"""
    }

    private fun editedLayer(base: String, replace: (String) -> String): CanonicalLayerInfo {
        val json = replace(base)
        return CanonicalProjectInfo.parse(json).compositions.single().layers.first { it.id == 2L }
    }

    private fun baseLayer(): CanonicalLayerInfo {
        return CanonicalProjectInfo.parse(layerJson()).compositions.single().layers.first { it.id == 2L }
    }

    @Test
    fun transformEditsParseToEditedCanonicalValues() {
        val base = layerJson()
        val moved = editedLayer(base) { it.replace("\"position\":[160.0,90.0]", "\"position\":[40.0,40.0]") }
        assertEquals(40.0, moved.positionX, 0.001)
        assertEquals(40.0, moved.positionY, 0.001)
        val scaled = editedLayer(base) { it.replace("\"scale\":[100.0,100.0]", "\"scale\":[50.0,75.0]") }
        assertEquals(50.0, scaled.scaleX, 0.001)
        assertEquals(75.0, scaled.scaleY, 0.001)
        val rotated = editedLayer(base) { it.replace("\"rotationDegrees\":0.0", "\"rotationDegrees\":30.0") }
        assertEquals(30.0, rotated.rotationDegrees, 0.001)
        val anchored = editedLayer(base) { it.replace("\"anchor\":[0.0,0.0]", "\"anchor\":[10.0,20.0]") }
        assertEquals(10.0, anchored.anchorX, 0.001)
        assertEquals(20.0, anchored.anchorY, 0.001)
    }

    @Test
    fun threeDRotationEditsParseToEditedCanonicalValues() {
        val base = layerJson()
        val oriented = editedLayer(base) { it.replace("\"orientation\":[0.0,0.0,0.0]", "\"orientation\":[10.0,20.0,30.0]") }
        assertEquals(10.0, oriented.orientationX, 0.001)
        assertEquals(20.0, oriented.orientationY, 0.001)
        assertEquals(30.0, oriented.orientationZ, 0.001)
        val xRotated = editedLayer(base) { it.replace("\"rotationXDegrees\":0.0", "\"rotationXDegrees\":45.0") }
        assertEquals(45.0, xRotated.rotationXDegrees, 0.001)
        val yRotated = editedLayer(base) { it.replace("\"rotationYDegrees\":0.0", "\"rotationYDegrees\":-30.0") }
        assertEquals(-30.0, yRotated.rotationYDegrees, 0.001)
    }

    @Test
    fun textEditsParseToEditedCanonicalValues() {
        val base = layerJson()
        val text = editedLayer(base) {
            it.replace("\"text\":\"Preview\"", "\"text\":\"Edited\"")
                .replace("\"size\":72.0", "\"size\":48.0")
                .replace("\"tracking\":0.0", "\"tracking\":12.5")
        }.textParams!!
        assertEquals("Edited", text.text)
        assertEquals(48.0, text.size, 0.001)
        assertEquals(12.5, text.tracking, 0.001)
    }

    @Test
    fun shapeAndEffectParamEditsParseToEditedCanonicalValues() {
        val base = layerJson()
        val shaped = editedLayer(base) {
            it.replace("\"value\":0.0}],\"vecs\":[{\"uid\":90012", "\"value\":24.0}],\"vecs\":[{\"uid\":90012")
                .replace("\"components\":[200.0,100.0]", "\"components\":[320.0,160.0]")
        }
        val rect = shaped.shapeItems.single { it.kind == "rect" }
        assertEquals(24.0, rect.scalars.single { it.name == "Roundness" }.value, 0.001)
        assertEquals(listOf(320.0, 160.0), rect.vecs.single { it.name == "Size" }.components)
        val effected = editedLayer(base) {
            it.replace("\"value\":0.0}],\"vecs\":[{\"uid\":90022", "\"value\":15.0}],\"vecs\":[{\"uid\":90022")
                .replace("\"components\":[0.0,0.0]", "\"components\":[10.0,20.0]")
        }
        val blur = effected.effects.single { it.kind == "gaussianBlur" }
        assertEquals(15.0, blur.scalars.single { it.name == "Blurriness" }.value, 0.001)
        assertEquals(listOf(10.0, 20.0), blur.vecs.single { it.name == "Center" }.components)
    }

    @Test
    fun opacityAndVisibilityEditsParseToEditedCanonicalValues() {
        val base = layerJson()
        val faded = editedLayer(base) { it.replace("\"opacityPercent\":100.0", "\"opacityPercent\":50.0") }
        assertEquals(50.0, faded.opacityPercent, 0.001)
        val hidden = editedLayer(base) { it.replace("\"visible\":true,\"opacityPercent\":100.0", "\"visible\":false,\"opacityPercent\":100.0") }
        assertFalse(hidden.visible)
        val renamed = editedLayer(base) { it.replace("\"name\":\"Title\"", "\"name\":\"Renamed\"") }
        assertEquals("Renamed", renamed.name)
        val locked = editedLayer(base) { it.replace("\"locked\":false,\"threeD\":false", "\"locked\":true,\"threeD\":false") }
        assertTrue(locked.locked)
    }

    @Test
    fun opacityKeyframeEditsParseWithSortedKeysAndStaticPreservation() {
        val keyed = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":320,"height":180,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[
            {"id":2,"name":"Title","source":"Text","visible":true,"opacityPercent":100.0,"opacityKeys":[{"frame":30,"value":0.0},{"frame":0,"value":100.0}]}]}]}"""
        val layer = CanonicalProjectInfo.parse(keyed).compositions.single().layers.single()
        assertEquals(listOf(0, 30), layer.opacityKeys.map { it.frame })
        assertEquals(50f, PropertyTrackEditor.evaluate(PropertyTrack("opacity", layer.opacityKeys), 15.0)!!, 0.001f)
        assertEquals(0f, PropertyTrackEditor.evaluate(PropertyTrack("opacity", layer.opacityKeys), 60.0)!!, 0.001f)
        val unkeyed = baseLayer()
        assertTrue(unkeyed.opacityKeys.isEmpty())
        assertEquals(100.0, unkeyed.opacityPercent, 0.001)
    }

    @Test
    fun stackOrderAndSelectionSurviveEditedReparse() {
        val moved = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":320,"height":180,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[
            {"id":3,"name":"Second","source":"Text","visible":true,"opacityPercent":100.0},
            {"id":2,"name":"Title","source":"Text","visible":true,"opacityPercent":100.0}]}]}"""
        val layers = CanonicalProjectInfo.parse(moved).compositions.single().layers
        assertEquals(listOf(3L, 2L), layers.map { it.id })
        assertEquals(listOf(layers[1]), CanonicalLayerSelection.resolveAll(layers, setOf(2L)))
        assertEquals(setOf(2L), CanonicalLayerSelection.pruneSelection(setOf(2L, 99L), layers))
        val afterDelete = CanonicalProjectInfo.parse(layerJson()).compositions.single().layers
        assertEquals(setOf(3L), CanonicalLayerSelection.selectionAfterDelete(setOf(2L), afterDelete, setOf(2L)))
    }

    @Test
    fun undoRedoGestureAndBatchHelpersKeepEditedValues() {
        val before = CanonicalEditSnapshot(layerJson(), setOf(2L))
        val after = CanonicalEditSnapshot(layerJson().replace("\"position\":[160.0,90.0]", "\"position\":[40.0,40.0]"), setOf(2L))
        val undoStack = CanonicalEditHistory.record(emptyList(), before)
        val undone = CanonicalEditHistory.undo(undoStack, emptyList(), after)!!
        assertEquals(before, undone.snapshot)
        val redone = CanonicalEditHistory.redo(emptyList(), listOf(after), before)!!
        assertEquals(after, redone.snapshot)
        val layers = CanonicalProjectInfo.parse(layerJson()).compositions.single().layers
        val drag = CanonicalGestureEdit.dragPositionDelta(80f, 0f, 160f, 320f)
        assertEquals(160.0 to 0.0, drag)
        val dragged = CanonicalGestureEdit.draggedPositions(layers, setOf(2L, 3L), drag)
        assertEquals(320.0, dragged.getValue(2L).first, 0.001)
        assertEquals(320.0, dragged.getValue(3L).first, 0.001)
    }

    @Test
    fun editedProjectChangesPreviewCacheKeySoPreviewCannotServeStaleFrame() {
        val before = layerJson()
        val after = before.replace("\"position\":[160.0,90.0]", "\"position\":[40.0,40.0]")
        assertNotEquals(
            RenderedFrameCache.key(before, 10, 0, 160),
            RenderedFrameCache.key(after, 10, 0, 160)
        )
        RenderedFrameCache.clear()
        val stale = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 9)
        val fresh = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8)
        RenderedFrameCache.put(RenderedFrameCache.key(before, 10, 0, 160), stale)
        assertEquals(
            fresh.toList(),
            RenderedFrameCache.getOrRender(RenderedFrameCache.key(after, 10, 0, 160)) { fresh }!!.toList()
        )
        RenderedFrameCache.clear()
    }
}
