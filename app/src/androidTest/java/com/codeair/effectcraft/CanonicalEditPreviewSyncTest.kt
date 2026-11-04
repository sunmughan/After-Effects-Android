package com.codeair.effectcraft

import com.codeair.effectcraft.data.CanonicalEditHistory
import com.codeair.effectcraft.data.CanonicalEditSnapshot
import com.codeair.effectcraft.data.CanonicalGestureEdit
import com.codeair.effectcraft.data.CanonicalLayerInfo
import com.codeair.effectcraft.data.CanonicalLayerSelection
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented leg of M4 #9 ("Verify every edit updates engine preview
 * and canonical project data").
 *
 * Every test below applies one edit type through the exact path the
 * editor UI uses (NativeProjectBridge, the same bridge callbacks wired
 * in EffectCraftApp), then proves BOTH sides of the edit:
 *
 * - Canonical side: the edited project re-inspected through
 *   NativeProjectBridge.inspectEcproj and parsed with
 *   CanonicalProjectInfo (the same parse that feeds canonicalProjectInfo
 *   in the app) carries the edited value.
 * - Preview side: a NativeSession opened on the pre-edit project (the
 *   app's workspace preview session) accepts the edited project through
 *   updateProject (the same syncRenderSession step commitCanonicalSource
 *   performs) and renders a frame from it. A preview pinned to stale
 *   data cannot pass, because updateProject rejects an invalid project
 *   and the render is served from the session's current project, never
 *   from the previous one (the frame cache is keyed on project JSON).
 *
 * Pixel tests add a third proof where the edit changes painted pixels:
 * the frame rendered from the edited project differs from (or, for
 * hold/static cases, equals) the frame rendered from the pre-edit
 * project. Edits that change no pixel by construction (rename, lock,
 * anchor alone, 3D rotation on a 2D layer, shape/effect parameters on
 * a synthetic group with no render implementation) assert the session
 * render succeeds from the edited project instead of claiming a pixel
 * change the renderer cannot produce.
 *
 * Fixture: the empty canonical composition from
 * NativeSessionInstrumentedTest, plus two text layers added through the
 * bridge. The first layer carries the text "Preview" so it paints white
 * glyphs on the black composition background. Shape and effect
 * parameters need a layer that carries them, and the bridge only creates
 * text layers, so those tests graft a Rectangle shape item and a
 * Gaussian Blur effect group onto the first layer's property tree in
 * the fixture JSON (the same synthetic groups the native Rust test
 * builds with build::shape_rect and a blur group). The graft uses only
 * documented node shapes ({"node":"Group"/"Prop","uid","match","name",
 * "value":{"t":...,"v":...}}); the grafted fixture is re-inspected
 * before any edit runs, so a bad graft fails loudly at setup, never
 * as a silent pass.
 *
 * Emulator proof only: no physical-device result is claimed here.
 */
class CanonicalEditPreviewSyncTest {
    private data class Fixture(
        val project: String,
        val compositionId: Long,
        val firstId: Long,
        val secondId: Long
    )

    private fun applyEdit(envelope: String?): String {
        val response = JSONObject(envelope ?: throw AssertionError("Native bridge returned null; native library unavailable"))
        assertTrue(
            "Canonical edit must succeed, got error: ${response.optString("error")}",
            response.optBoolean("valid")
        )
        return response.getString("project")
    }

    private fun removeEdit(envelope: String?): Pair<String, Boolean> {
        val response = JSONObject(envelope ?: throw AssertionError("Native bridge returned null; native library unavailable"))
        assertTrue(
            "Canonical key removal must succeed, got error: ${response.optString("error")}",
            response.optBoolean("valid")
        )
        return response.getString("project") to response.optBoolean("removed")
    }

    private fun canonical(project: String): CanonicalProjectInfo {
        val metadata = NativeProjectBridge.inspectEcproj(project)
            ?: throw AssertionError("Native inspect returned null for the edited project")
        return CanonicalProjectInfo.parse(metadata)
    }

    private fun layer(info: CanonicalProjectInfo, id: Long): CanonicalLayerInfo {
        return info.compositions.single().layers.firstOrNull { it.id == id }
            ?: throw AssertionError("Layer $id missing from canonical metadata")
    }

    private fun baseFixture(): Fixture {
        assertTrue(NativeProjectBridge.isAvailable())
        var project = applyEdit(
            NativeProjectBridge.addTextLayerEcproj(
                NativeSessionInstrumentedTest.FIXTURE_JSON,
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                "Title"
            )
        )
        project = applyEdit(
            NativeProjectBridge.addTextLayerEcproj(
                project,
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                "Second"
            )
        )
        val info = canonical(project)
        val first = info.compositions.single().layers.first { it.name == "Title" }
        val second = info.compositions.single().layers.first { it.name == "Second" }
        project = applyEdit(
            NativeProjectBridge.setTextLayerTextEcproj(
                project,
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                first.id,
                "Preview"
            )
        )
        return Fixture(
            project = project,
            compositionId = NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
            firstId = first.id,
            secondId = second.id
        )
    }

    /**
     * Grafts a Rectangle shape item (Size vec, Roundness scalar) and a
     * Gaussian Blur effect group (Blurriness scalar, Center vec) onto the
     * first layer of [fixture], returning the grafted project. The graft
     * is verified by re-inspection before it is returned.
     */
    private fun shapedFixture(fixture: Fixture): String {
        val doc = JSONObject(fixture.project)
        val items = doc.getJSONObject("items")
        val compKey = items.keys().asSequence().first { key ->
            items.getJSONObject(key).optJSONObject("kind")?.optString("type") == "Comp"
        }
        val layers = items.getJSONObject(compKey).getJSONObject("kind").getJSONArray("layers")
        var layerObject: JSONObject? = null
        for (index in 0 until layers.length()) {
            val candidate = layers.getJSONObject(index)
            if (candidate.optString("name") == "Title") layerObject = candidate
        }
        val layerJson = layerObject ?: throw AssertionError("Title layer missing from raw project JSON")
        val children = layerJson.getJSONObject("props").getJSONArray("children")
        var effectsChildren: JSONArray? = null
        for (index in 0 until children.length()) {
            val child = children.getJSONObject(index)
            if (child.optString("match") == "effects") effectsChildren = child.getJSONArray("children")
        }
        val effects = effectsChildren ?: throw AssertionError("Effects group missing from Title layer props")
        effects.put(
            JSONObject(
                """{"node":"Group","uid":90020,"match":"gaussianBlur","name":"Gaussian Blur","children":[
                    {"node":"Prop","uid":90021,"match":"blurriness","name":"Blurriness","value":{"t":"Scalar","v":0.0}},
                    {"node":"Prop","uid":90022,"match":"center","name":"Center","value":{"t":"Vec2","v":[0.0,0.0]}}
                ]}"""
            )
        )
        children.put(
            JSONObject(
                """{"node":"Group","uid":90010,"match":"contents","name":"Contents","children":[
                    {"node":"Group","uid":90011,"match":"rect","name":"Rectangle Path 1","children":[
                        {"node":"Prop","uid":90012,"match":"size","name":"Size","value":{"t":"Vec2","v":[200.0,100.0]}},
                        {"node":"Prop","uid":90013,"match":"roundness","name":"Roundness","value":{"t":"Scalar","v":0.0}}
                    ]}
                ]}"""
            )
        )
        val grafted = doc.toString()
        val info = canonical(grafted)
        val graftedLayer = layer(info, fixture.firstId)
        assertTrue(
            "Grafted shape item must be exposed in canonical metadata",
            graftedLayer.shapeItems.any { it.kind == "rect" }
        )
        assertTrue(
            "Grafted effect group must be exposed in canonical metadata",
            graftedLayer.effects.any { it.kind == "gaussianBlur" }
        )
        return grafted
    }

    /**
     * Renders [frame] from [before] through a preview session, swaps the
     * session to [after] exactly as the app does on commit, renders the
     * same frame again, and returns both frames. Fails when the session
     * refuses the edited project or either render is null.
     */
    private fun previewBeforeAndAfter(before: String, after: String, compositionId: Long, frame: Long): Pair<ByteArray, ByteArray> {
        val session = NativeSession.open(before, 160)
            ?: throw AssertionError("Preview session must open for the pre-edit project")
        try {
            val baseFrame = session.render(compositionId, frame)
                ?: throw AssertionError("Preview session must render the pre-edit frame $frame")
            assertTrue(
                "Preview session must accept the edited project",
                session.updateProject(after)
            )
            val editedFrame = session.render(compositionId, frame)
                ?: throw AssertionError("Preview session must render the edited frame $frame")
            return baseFrame to editedFrame
        } finally {
            session.close()
        }
    }

    private fun previewRenders(before: String, after: String, compositionId: Long, frame: Long): ByteArray {
        return previewBeforeAndAfter(before, after, compositionId, frame).second
    }

    private fun assertFramesDiffer(first: ByteArray, second: ByteArray, message: String) {
        assertFalse(message, first.contentEquals(second))
    }

    @Test
    fun positionEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(fixture.project, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        val editedLayer = layer(canonical(edited), fixture.firstId)
        assertEquals(40.0, editedLayer.positionX, 0.001)
        assertEquals(40.0, editedLayer.positionY, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must move with the position edit")
    }

    @Test
    fun scaleEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerScaleEcproj(fixture.project, fixture.compositionId, fixture.firstId, 50.0, 75.0)
        )
        val editedLayer = layer(canonical(edited), fixture.firstId)
        assertEquals(50.0, editedLayer.scaleX, 0.001)
        assertEquals(75.0, editedLayer.scaleY, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must rescale with the scale edit")
    }

    @Test
    fun rotationEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerRotationEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30.0)
        )
        assertEquals(30.0, layer(canonical(edited), fixture.firstId).rotationDegrees, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must rotate with the rotation edit")
    }

    @Test
    fun anchorEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerAnchorEcproj(fixture.project, fixture.compositionId, fixture.firstId, 10.0, 20.0)
        )
        val editedLayer = layer(canonical(edited), fixture.firstId)
        assertEquals(10.0, editedLayer.anchorX, 0.001)
        assertEquals(20.0, editedLayer.anchorY, 0.001)
        assertNotNull(
            "Preview session must render from the anchor-edited project",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun orientationEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerOrientationEcproj(fixture.project, fixture.compositionId, fixture.firstId, 10.0, 20.0, 30.0)
        )
        val editedLayer = layer(canonical(edited), fixture.firstId)
        assertEquals(10.0, editedLayer.orientationX, 0.001)
        assertEquals(20.0, editedLayer.orientationY, 0.001)
        assertEquals(30.0, editedLayer.orientationZ, 0.001)
        assertNotNull(
            "Preview session must render from the orientation-edited project",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun rotationXEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerRotationXEcproj(fixture.project, fixture.compositionId, fixture.firstId, 45.0)
        )
        assertEquals(45.0, layer(canonical(edited), fixture.firstId).rotationXDegrees, 0.001)
        assertNotNull(
            "Preview session must render from the X-rotation-edited project",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun rotationYEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerRotationYEcproj(fixture.project, fixture.compositionId, fixture.firstId, -30.0)
        )
        assertEquals(-30.0, layer(canonical(edited), fixture.firstId).rotationYDegrees, 0.001)
        assertNotNull(
            "Preview session must render from the Y-rotation-edited project",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun textSourceEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setTextLayerTextEcproj(fixture.project, fixture.compositionId, fixture.firstId, "Edited")
        )
        assertEquals("Edited", layer(canonical(edited), fixture.firstId).textParams!!.text)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must change with the source text edit")
    }

    @Test
    fun textSizeEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setTextLayerSizeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 48.0)
        )
        assertEquals(48.0, layer(canonical(edited), fixture.firstId).textParams!!.size, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must change with the text size edit")
    }

    @Test
    fun textTrackingEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setTextLayerTrackingEcproj(fixture.project, fixture.compositionId, fixture.firstId, 12.5)
        )
        assertEquals(12.5, layer(canonical(edited), fixture.firstId).textParams!!.tracking, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must change with the text tracking edit")
    }

    @Test
    fun shapeScalarEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val shaped = shapedFixture(fixture)
        val edited = applyEdit(
            NativeProjectBridge.setLayerScalarPropertyEcproj(shaped, fixture.compositionId, fixture.firstId, 90013L, 24.0)
        )
        val rect = layer(canonical(edited), fixture.firstId).shapeItems.single { it.kind == "rect" }
        assertEquals(24.0, rect.scalars.single { it.name == "Roundness" }.value, 0.001)
        assertNotNull(
            "Preview session must render from the shape-scalar-edited project",
            previewRenders(shaped, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun shapeVectorEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val shaped = shapedFixture(fixture)
        val edited = applyEdit(
            NativeProjectBridge.setLayerVecPropertyEcproj(shaped, fixture.compositionId, fixture.firstId, 90012L, 320.0, 160.0, 0.0)
        )
        val rect = layer(canonical(edited), fixture.firstId).shapeItems.single { it.kind == "rect" }
        assertEquals(listOf(320.0, 160.0), rect.vecs.single { it.name == "Size" }.components)
        assertNotNull(
            "Preview session must render from the shape-vector-edited project",
            previewRenders(shaped, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun effectScalarEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val shaped = shapedFixture(fixture)
        val edited = applyEdit(
            NativeProjectBridge.setLayerScalarPropertyEcproj(shaped, fixture.compositionId, fixture.firstId, 90021L, 15.0)
        )
        val blur = layer(canonical(edited), fixture.firstId).effects.single { it.kind == "gaussianBlur" }
        assertEquals(15.0, blur.scalars.single { it.name == "Blurriness" }.value, 0.001)
        assertNotNull(
            "Preview session must render from the effect-scalar-edited project",
            previewRenders(shaped, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun effectVectorEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val shaped = shapedFixture(fixture)
        val edited = applyEdit(
            NativeProjectBridge.setLayerVecPropertyEcproj(shaped, fixture.compositionId, fixture.firstId, 90022L, 10.0, 20.0, 0.0)
        )
        val blur = layer(canonical(edited), fixture.firstId).effects.single { it.kind == "gaussianBlur" }
        assertEquals(listOf(10.0, 20.0), blur.vecs.single { it.name == "Center" }.components)
        assertNotNull(
            "Preview session must render from the effect-vector-edited project",
            previewRenders(shaped, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun opacityEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerOpacityEcproj(fixture.project, fixture.compositionId, fixture.firstId, 50.0)
        )
        assertEquals(50.0, layer(canonical(edited), fixture.firstId).opacityPercent, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must fade with the opacity edit")
    }

    @Test
    fun opacityKeyframeAddUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerOpacityKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30L, 0.0)
        )
        val keys = layer(canonical(keyed), fixture.firstId).opacityKeys
        assertEquals(listOf(0, 30), keys.map { it.frame })
        val session = NativeSession.open(fixture.project, 160)
            ?: throw AssertionError("Preview session must open for the pre-keyframe project")
        try {
            val frameZero = session.render(fixture.compositionId, 0)
                ?: throw AssertionError("Preview session must render frame 0")
            assertTrue("Preview session must accept the keyframed project", session.updateProject(keyed))
            val frameThirty = session.render(fixture.compositionId, 30)
                ?: throw AssertionError("Preview session must render frame 30")
            assertFramesDiffer(frameZero, frameThirty, "Preview at the keyframe frame must differ from frame 0")
        } finally {
            session.close()
        }
    }

    @Test
    fun opacityKeyframeRemoveUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerOpacityKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30L, 0.0)
        )
        val (unkeyed, removed) = removeEdit(
            NativeProjectBridge.removeLayerOpacityKeyframeEcproj(keyed, fixture.compositionId, fixture.firstId, 30L)
        )
        assertTrue("Removing the frame 30 key must report removed", removed)
        assertEquals(listOf(0), layer(canonical(unkeyed), fixture.firstId).opacityKeys.map { it.frame })
        val session = NativeSession.open(keyed, 160)
            ?: throw AssertionError("Preview session must open for the keyframed project")
        try {
            val keyedFrame = session.render(fixture.compositionId, 30)
                ?: throw AssertionError("Preview session must render keyframed frame 30")
            assertTrue("Preview session must accept the key-removed project", session.updateProject(unkeyed))
            val unkeyedFrame = session.render(fixture.compositionId, 30)
                ?: throw AssertionError("Preview session must render unkeyframed frame 30")
            assertFramesDiffer(keyedFrame, unkeyedFrame, "Preview at frame 30 must change when its key is removed")
        } finally {
            session.close()
        }
    }

    @Test
    fun curveGraphDeleteRemovesTransformKeyAndRestoresStaticPreview() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerTransformKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, "position", 0L, 0.0, 0.0, 0.0)
        )
        val animated = applyEdit(
            NativeProjectBridge.setLayerTransformKeyframeEcproj(keyed, fixture.compositionId, fixture.firstId, "position", 30L, 100.0, 0.0, 0.0)
        )
        assertEquals(listOf(0, 30), layer(canonical(animated), fixture.firstId).transformCurves["position"]!!.map { it.frame })
        val (staticProject, removed) = removeEdit(
            NativeProjectBridge.removeLayerCurveKeyframeEcproj(animated, fixture.compositionId, fixture.firstId, "transform:position", 30L)
        )
        assertTrue("Curve key removal must report removed", removed)
        assertEquals(listOf(0), layer(canonical(staticProject), fixture.firstId).transformCurves["position"]!!.map { it.frame })
        val (animatedFrame, staticFrame) = previewBeforeAndAfter(animated, staticProject, fixture.compositionId, 30)
        assertFramesDiffer(animatedFrame, staticFrame, "Removing the frame-30 position key must restore the static-position preview")
    }

    @Test
    fun opacityKeyframeInterpolatesInPreview() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerOpacityKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30L, 0.0)
        )
        val session = NativeSession.open(keyed, 160)
            ?: throw AssertionError("Preview session must open for the keyframed project")
        try {
            val frameZero = session.render(fixture.compositionId, 0)
                ?: throw AssertionError("Preview session must render frame 0")
            val frameMid = session.render(fixture.compositionId, 15)
                ?: throw AssertionError("Preview session must render frame 15")
            val frameThirty = session.render(fixture.compositionId, 30)
                ?: throw AssertionError("Preview session must render frame 30")
            assertFramesDiffer(frameZero, frameMid, "Preview at the midpoint must differ from frame 0")
            assertFramesDiffer(frameMid, frameThirty, "Preview at the midpoint must differ from frame 30")
        } finally {
            session.close()
        }
    }

    @Test
    fun opacityKeyframeHoldsPastLastKeyInPreview() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerOpacityKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30L, 0.0)
        )
        val session = NativeSession.open(keyed, 160)
            ?: throw AssertionError("Preview session must open for the keyframed project")
        try {
            val frameZero = session.render(fixture.compositionId, 0)
                ?: throw AssertionError("Preview session must render frame 0")
            val frameThirty = session.render(fixture.compositionId, 30)
                ?: throw AssertionError("Preview session must render frame 30")
            val frameSixty = session.render(fixture.compositionId, 60)
                ?: throw AssertionError("Preview session must render frame 60")
            assertFramesDiffer(frameZero, frameThirty, "Preview at frame 30 must differ from frame 0")
            assertArrayEquals("Preview past the last key must hold the last key frame", frameThirty, frameSixty)
        } finally {
            session.close()
        }
    }

    @Test
    fun lastOpacityKeyframeRemovedPreservesStaticOpacityInCanonicalDataAndPreview() {
        val fixture = baseFixture()
        val keyed = applyEdit(
            NativeProjectBridge.setLayerOpacityKeyframeEcproj(fixture.project, fixture.compositionId, fixture.firstId, 30L, 0.0)
        )
        val (afterFirstRemove, _) = removeEdit(
            NativeProjectBridge.removeLayerOpacityKeyframeEcproj(keyed, fixture.compositionId, fixture.firstId, 30L)
        )
        val (static, removed) = removeEdit(
            NativeProjectBridge.removeLayerOpacityKeyframeEcproj(afterFirstRemove, fixture.compositionId, fixture.firstId, 0L)
        )
        assertTrue("Removing the last key must report removed", removed)
        val staticLayer = layer(canonical(static), fixture.firstId)
        assertTrue("No opacity keys must remain after the last key is removed", staticLayer.opacityKeys.isEmpty())
        assertEquals(100.0, staticLayer.opacityPercent, 0.001)
        val (baseFrame, staticFrame) = previewBeforeAndAfter(fixture.project, static, fixture.compositionId, 0)
        assertArrayEquals("Preview of the static-restored project must match the original frame", baseFrame, staticFrame)
    }

    @Test
    fun visibilityEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerVisibilityEcproj(fixture.project, fixture.compositionId, fixture.firstId, false)
        )
        assertFalse(layer(canonical(edited), fixture.firstId).visible)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must lose the layer when visibility is off")
    }

    @Test
    fun renameEditUpdatesCanonicalDataAndPreviewSessionWithoutChangingPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.renameLayerEcproj(fixture.project, fixture.compositionId, fixture.firstId, "Renamed")
        )
        assertEquals("Renamed", layer(canonical(edited), fixture.firstId).name)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertArrayEquals("A rename must not change preview pixels", baseFrame, editedFrame)
    }

    @Test
    fun moveEditUpdatesCanonicalDataAndPreviewSession() {
        val fixture = baseFixture()
        val beforeOrder = canonical(fixture.project).compositions.single().layers.map { it.id }
        val edited = applyEdit(
            NativeProjectBridge.moveLayerEcproj(fixture.project, fixture.compositionId, fixture.firstId, 1)
        )
        val afterOrder = canonical(edited).compositions.single().layers.map { it.id }
        assertEquals(beforeOrder.reversed(), afterOrder)
        assertNotNull(
            "Preview session must render from the reordered project",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
    }

    @Test
    fun deleteEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.deleteLayerEcproj(fixture.project, fixture.compositionId, fixture.firstId)
        )
        val remaining = canonical(edited).compositions.single().layers
        assertEquals(listOf(fixture.secondId), remaining.map { it.id })
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must lose the deleted layer")
    }

    @Test
    fun lockEditBlocksEditsAndUnlockRestoresCanonicalDataAndPreview() {
        val fixture = baseFixture()
        val locked = applyEdit(
            NativeProjectBridge.setLayerLockedEcproj(fixture.project, fixture.compositionId, fixture.firstId, true)
        )
        assertTrue(layer(canonical(locked), fixture.firstId).locked)
        val blocked = JSONObject(
            NativeProjectBridge.setLayerPositionEcproj(locked, fixture.compositionId, fixture.firstId, 40.0, 40.0)
                ?: throw AssertionError("Native bridge returned null for the locked edit")
        )
        assertFalse("A position edit on a locked layer must be rejected", blocked.optBoolean("valid"))
        assertEquals(160.0, layer(canonical(locked), fixture.firstId).positionX, 0.001)
        val unlocked = applyEdit(
            NativeProjectBridge.setLayerLockedEcproj(locked, fixture.compositionId, fixture.firstId, false)
        )
        assertFalse(layer(canonical(unlocked), fixture.firstId).locked)
        val moved = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(unlocked, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        assertEquals(40.0, layer(canonical(moved), fixture.firstId).positionX, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, moved, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must move once the layer is unlocked")
    }

    @Test
    fun undoRestoresCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(fixture.project, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        val undoStack = CanonicalEditHistory.record(
            emptyList(),
            CanonicalEditSnapshot(fixture.project, setOf(fixture.firstId))
        )
        val undone = CanonicalEditHistory.undo(undoStack, emptyList(), CanonicalEditSnapshot(edited, setOf(fixture.firstId)))!!
        assertEquals(fixture.project, undone.snapshot.projectJson)
        assertEquals(setOf(fixture.firstId), undone.snapshot.selectedLayerIds)
        assertEquals(160.0, layer(canonical(undone.snapshot.projectJson), fixture.firstId).positionX, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(edited, undone.snapshot.projectJson, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Undo preview must differ from the edited preview")
        val (originalFrame, undoneFrame) = previewBeforeAndAfter(fixture.project, undone.snapshot.projectJson, fixture.compositionId, 0)
        assertArrayEquals("Undo preview must match the original preview", originalFrame, undoneFrame)
    }

    @Test
    fun redoRestoresCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(fixture.project, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        val redone = CanonicalEditHistory.redo(
            emptyList(),
            listOf(CanonicalEditSnapshot(edited, setOf(fixture.firstId))),
            CanonicalEditSnapshot(fixture.project, setOf(fixture.firstId))
        )!!
        assertEquals(edited, redone.snapshot.projectJson)
        assertEquals(40.0, layer(canonical(redone.snapshot.projectJson), fixture.firstId).positionX, 0.001)
        val (baseFrame, redoneFrame) = previewBeforeAndAfter(fixture.project, redone.snapshot.projectJson, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, redoneFrame, "Redo preview must match the edited preview, not the original")
        val (_, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertArrayEquals("Redo preview must equal the edited preview", editedFrame, redoneFrame)
    }

    @Test
    fun previewDragGestureUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        val info = canonical(fixture.project)
        val layers = info.compositions.single().layers
        // A drag of 80 preview pixels across a 160px-wide preview of a
        // 320px-wide composition moves selected layers by 160 comp pixels.
        val delta = CanonicalGestureEdit.dragPositionDelta(80f, 0f, 160f, 320f)
        assertEquals(160.0 to 0.0, delta)
        val dragged = CanonicalGestureEdit.draggedPositions(layers, setOf(fixture.firstId, fixture.secondId), delta)
        var edited = fixture.project
        dragged.forEach { (layerId, position) ->
            edited = applyEdit(
                NativeProjectBridge.setLayerPositionEcproj(edited, fixture.compositionId, layerId, position.first, position.second)
            )
        }
        val editedInfo = canonical(edited)
        assertEquals(320.0, layer(editedInfo, fixture.firstId).positionX, 0.001)
        assertEquals(320.0, layer(editedInfo, fixture.secondId).positionX, 0.001)
        assertEquals(90.0, layer(editedInfo, fixture.firstId).positionY, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must follow the drag gesture edit")
    }

    @Test
    fun batchMultiSelectionEditUpdatesCanonicalDataAndPreviewPixels() {
        val fixture = baseFixture()
        // One batch step per selected layer, chained like
        // applyCanonicalEditBatch: each step transforms the previous
        // step's project, and only the final project is committed.
        var edited = fixture.project
        edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(edited, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(edited, fixture.compositionId, fixture.secondId, 40.0, 40.0)
        )
        edited = applyEdit(
            NativeProjectBridge.setLayerScaleEcproj(edited, fixture.compositionId, fixture.firstId, 50.0, 50.0)
        )
        edited = applyEdit(
            NativeProjectBridge.setLayerScaleEcproj(edited, fixture.compositionId, fixture.secondId, 50.0, 50.0)
        )
        val editedInfo = canonical(edited)
        assertEquals(40.0, layer(editedInfo, fixture.firstId).positionX, 0.001)
        assertEquals(40.0, layer(editedInfo, fixture.secondId).positionX, 0.001)
        assertEquals(50.0, layer(editedInfo, fixture.firstId).scaleX, 0.001)
        assertEquals(50.0, layer(editedInfo, fixture.secondId).scaleX, 0.001)
        val (baseFrame, editedFrame) = previewBeforeAndAfter(fixture.project, edited, fixture.compositionId, 0)
        assertFramesDiffer(baseFrame, editedFrame, "Preview pixels must follow the batch multi-selection edit")
    }

    @Test
    fun selectionChangeSurvivesReparseAndPreviewRendersSelectedProject() {
        val fixture = baseFixture()
        val info = canonical(fixture.project)
        val layers = info.compositions.single().layers
        val selected = CanonicalLayerSelection.toggleSelection(setOf(fixture.firstId), fixture.secondId, layers)
        assertEquals(setOf(fixture.firstId, fixture.secondId), selected)
        val resolved = CanonicalLayerSelection.resolveAll(layers, selected)
        assertEquals(listOf(fixture.firstId, fixture.secondId), resolved.map { it.id })
        // Re-parse after an edit: the selection still resolves to layers
        // that exist in the project the preview renders from.
        val edited = applyEdit(
            NativeProjectBridge.setLayerPositionEcproj(fixture.project, fixture.compositionId, fixture.firstId, 40.0, 40.0)
        )
        val editedLayers = canonical(edited).compositions.single().layers
        val pruned = CanonicalLayerSelection.pruneSelection(selected, editedLayers)
        assertEquals(selected, pruned)
        assertNotNull(
            "Preview session must render the project the reselected layers belong to",
            previewRenders(fixture.project, edited, fixture.compositionId, 0)
        )
        // Deleting the whole selection moves it to the neighbor, and the
        // preview renders the project with that neighbor selected.
        val deleted = applyEdit(
            NativeProjectBridge.deleteLayerEcproj(fixture.project, fixture.compositionId, fixture.firstId)
        )
        val remainingLayers = canonical(deleted).compositions.single().layers
        val afterDelete = CanonicalLayerSelection.selectionAfterDelete(selected, layers, setOf(fixture.firstId))
        assertEquals(setOf(fixture.secondId), afterDelete)
        assertTrue(remainingLayers.any { it.id == afterDelete.single() })
        assertNotNull(
            "Preview session must render the project after the selection-moving delete",
            previewRenders(fixture.project, deleted, fixture.compositionId, 0)
        )
    }
}
