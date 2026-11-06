package com.codeair.effectcraft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import com.codeair.effectcraft.data.CanonicalBoolParam
import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalPopupParam
import com.codeair.effectcraft.data.CanonicalCurveKey
import com.codeair.effectcraft.data.CanonicalLayerInfo
import com.codeair.effectcraft.data.CanonicalParamGroup
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.CanonicalScalarParam
import com.codeair.effectcraft.data.CanonicalTextKey
import com.codeair.effectcraft.data.CanonicalTextParams
import com.codeair.effectcraft.data.CanonicalVecParam
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.PropertyKeyframe
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveWorkspaceSmokeTest {
    // EditorScreen is a pure composable and needs no activity host. The
    // previous activity rule failed in both directions: without a process
    // override the instrumentation could not launch the test activity, and
    // with it the cross-process setContent hung the run. A plain compose
    // rule hosts the content in the test process directly.
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wideWindowKeepsInspectorAndPreviewSideBySide() {
        val project = ProjectDocument.starter()
        compose.setContent {
            // The emulator's physical width in pixels becomes a wide dp window at density 1.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = 0,
                    selectedOpacityKeyframes = emptyList(),
                    isPlaying = false,
                    saveMessage = "Saved",
                    savedProjects = emptyList(),
                    offlineAssets = emptyList(),
                    referencedOfflineAssetIds = emptySet(),
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onOpenProject = {},
                    onDeleteOfflineAsset = {},
                    onValidateEcproj = {},
                    onExportEcproj = {},
                    onExportEcprojx = {},
                    isExportingFrame = false,
                    onExportRenderedFrame = {},
                    onCancelFrameExport = {},
                    isExportingSequence = false,
                    isSequenceExportWritingOutput = false,
                    onExportPngSequence = { _, _, _, _, _ -> },
                    onCancelSequenceExport = {},
                    sequenceExportProgress = 0,
                    onNewProject = {},
                    onAddLayer = {},
                    onSelectLayer = {},
                    onDeleteLayer = {},
                    onMoveLayer = {},
                    onVisibilityChange = {},
                    onRenameLayer = {},
                    onOpacityChange = {},
                    onScrub = {},
                    onAddOpacityKeyframe = {},
                    onRemoveOpacityKeyframe = {},
                    onTogglePlayback = {},
                    onApplyComposition = { _, _, _, _ -> },
                    canonicalProject = null,
                    canEditCanonicalProject = false,
                    onRenameCanonicalLayer = { _, _, _ -> },
                    onAddCanonicalTextLayer = { _, _ -> },
                    onSetCanonicalLayerVisibility = { _, _, _ -> },
                    onMoveCanonicalLayer = { _, _, _ -> },
                    onDeleteCanonicalLayer = { _, _ -> },
                    onSetCanonicalLayerOpacity = { _, _, _ -> },
                    onAddCanonicalLayerOpacityKeyframe = { _, _, _, _ -> },
                    onRemoveCanonicalLayerOpacityKeyframe = { _, _, _ -> },
                    onAddCanonicalLayerTransformKeyframe = { _, _, _, _, _, _, _ -> },
                    onRemoveCanonicalLayerTransformKeyframe = { _, _, _, _ -> },
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onSetCanonicalLayerAnchor = { _, _, _, _ -> },
                    onSetCanonicalLayerOrientation = { _, _, _, _, _ -> },
                    onSetCanonicalLayerRotationX = { _, _, _ -> },
                    onSetCanonicalLayerRotationY = { _, _, _ -> },
                    onSetCanonicalLayerLocked = { _, _, _ -> },
                    onSetCanonicalTextLayerText = { _, _, _ -> },
                    onSetCanonicalTextLayerSize = { _, _, _ -> },
                    onSetCanonicalTextLayerTracking = { _, _, _ -> },
                    onSetCanonicalLayerScalarProperty = { _, _, _, _ -> },
                    onSetCanonicalLayerVecProperty = { _, _, _, _, _, _ -> },
                    onSetCanonicalLayerColorProperty = { _, _, _, _, _, _, _ -> },
                    onRelinkCanonicalAsset = {},
                    onRenderCanonicalFrame = { _, _ -> null },
                    safGrants = emptyList(),
                    missingManifestEntries = 0,
                    onReleaseSafGrant = { _, _ -> },
                    onReplaceOfflineAsset = {},
                    onCollectUnusedOfflineAssets = {}
                )
            }
        }
        compose.onNodeWithText("COMPOSITION VIEWER", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PROPERTIES").assertIsDisplayed()
    }

    @Test
    fun canonicalOpacityKeyframeControlsAuthorAtPreviewFrame() {
        val layer = CanonicalLayerInfo(
            id = 2, name = "Headline", source = "Text", visible = true,
            opacityPercent = 100.0, positionX = 0.0, positionY = 0.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0,
            opacityKeys = listOf(PropertyKeyframe(0, 100f))
        )
        val composition = CanonicalCompositionInfo(
            id = 10, name = "Main", width = 640, height = 360,
            frameRateNum = 30, frameRateDen = 1, durationSeconds = 3.0,
            layers = listOf(layer)
        )
        val canonical = CanonicalProjectInfo(compositions = listOf(composition))
        var added: List<Any>? = null
        var removed: List<Any>? = null
        val project = ProjectDocument.starter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = 0,
                    selectedOpacityKeyframes = emptyList(),
                    isPlaying = false,
                    saveMessage = "Saved",
                    savedProjects = emptyList(),
                    offlineAssets = emptyList(),
                    referencedOfflineAssetIds = emptySet(),
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onOpenProject = {},
                    onDeleteOfflineAsset = {},
                    onValidateEcproj = {},
                    onExportEcproj = {},
                    onExportEcprojx = {},
                    isExportingFrame = false,
                    onExportRenderedFrame = {},
                    onCancelFrameExport = {},
                    isExportingSequence = false,
                    isSequenceExportWritingOutput = false,
                    onExportPngSequence = { width, height, fps, start, end -> added = listOf(width, height, fps, start, end) },
                    onCancelSequenceExport = {},
                    sequenceExportProgress = 0,
                    onNewProject = {},
                    onAddLayer = {},
                    onSelectLayer = {},
                    onDeleteLayer = {},
                    onMoveLayer = {},
                    onVisibilityChange = {},
                    onRenameLayer = {},
                    onOpacityChange = {},
                    onScrub = {},
                    onAddOpacityKeyframe = {},
                    onRemoveOpacityKeyframe = {},
                    onTogglePlayback = {},
                    onApplyComposition = { _, _, _, _ -> },
                    canonicalProject = canonical,
                    canEditCanonicalProject = true,
                    canonicalSelectedLayerIds = setOf(2L),
                    onRenameCanonicalLayer = { _, _, _ -> },
                    onAddCanonicalTextLayer = { _, _ -> },
                    onSetCanonicalLayerVisibility = { _, _, _ -> },
                    onMoveCanonicalLayer = { _, _, _ -> },
                    onDeleteCanonicalLayer = { _, _ -> },
                    onSetCanonicalLayerOpacity = { _, _, _ -> },
                    onAddCanonicalLayerOpacityKeyframe = { c, l, f, v -> added = listOf(c, l, f, v) },
                    onRemoveCanonicalLayerOpacityKeyframe = { c, l, f -> removed = listOf(c, l, f) },
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onSetCanonicalLayerAnchor = { _, _, _, _ -> },
                    onSetCanonicalLayerOrientation = { _, _, _, _, _ -> },
                    onSetCanonicalLayerRotationX = { _, _, _ -> },
                    onSetCanonicalLayerRotationY = { _, _, _ -> },
                    onSetCanonicalLayerLocked = { _, _, _ -> },
                    onSetCanonicalTextLayerText = { _, _, _ -> },
                    onSetCanonicalTextLayerSize = { _, _, _ -> },
                    onSetCanonicalTextLayerTracking = { _, _, _ -> },
                    onSetCanonicalLayerScalarProperty = { _, _, _, _ -> },
                    onSetCanonicalLayerVecProperty = { _, _, _, _, _, _ -> },
                    onSetCanonicalLayerColorProperty = { _, _, _, _, _, _, _ -> },
                    onRelinkCanonicalAsset = {},
                    onRenderCanonicalFrame = { _, _ -> null },
                    safGrants = emptyList(),
                    missingManifestEntries = 0,
                    onReleaseSafGrant = { _, _ -> },
                    onReplaceOfflineAsset = {},
                    onCollectUnusedOfflineAssets = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Export PNG sequence ZIP").assertIsDisplayed().performClick()
        compose.onNodeWithText("PNG sequence settings").assertIsDisplayed()
        compose.onNodeWithText("Render ZIP").performClick()
        assertEquals(listOf(640, 360, 30, 0, 89), added)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithText("Opacity keys · 1", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Add canonical opacity keyframe").performScrollTo().performClick()
        assertEquals(listOf(10L, 2L, 0, 100.0), added)
        compose.onNodeWithContentDescription("Remove canonical opacity keyframe").performScrollTo().performClick()
        // Frame is Int in the callback, so listOf<Any> keeps the literal 0
        // as Int instead of inferring List<Long> and coercing it to 0L.
        assertEquals(listOf<Any>(10L, 2L, 0), removed)
    }

    private class CanonicalCaptures {
        var anchor: List<Any>? = null
        var orientation: List<Any>? = null
        var transformKeyframe: List<Any>? = null
        var transformKeyframeRemoved: List<Any>? = null
        var curveKey: List<Any>? = null
        var rotationX: List<Any>? = null
        var rotationY: List<Any>? = null
        var locked: List<Any>? = null
        var text: List<Any>? = null
        var textWholeLayer: List<Any>? = null
        var textKeyframe: List<Any>? = null
        var textSize: List<Any>? = null
        var textTracking: List<Any>? = null
        var scalarProperty: List<Any>? = null
        var vecProperty: List<Any>? = null
        var boolProperty: List<Any>? = null
        var popupProperty: List<Any>? = null
        var boolKeyframe: List<Any>? = null
        var popupKeyframe: List<Any>? = null
        var roving: List<Any>? = null
        var spatialTangents: List<Any>? = null
        val renderRequests = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
    }

    private fun setCanonicalEditorContent(
        canonical: CanonicalProjectInfo,
        captures: CanonicalCaptures,
        onMoveCurveKey: (Long, Long, String, Int, Int) -> Unit = { _, _, _, _, _ -> },
        onRemoveCurveKey: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
        onAddTransformKeyframe: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
        onRemoveTransformKeyframe: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
        onAddCurveKey: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
        onSetBoolProperty: (Long, Long, Long, Boolean) -> Unit = { c, l, uid, v -> captures.boolProperty = listOf(c, l, uid, v) },
        onSetPopupProperty: (Long, Long, Long, Int) -> Unit = { c, l, uid, v -> captures.popupProperty = listOf(c, l, uid, v) },
        onSetBoolKeyframe: (Long, Long, Long, Int, Boolean, Boolean) -> Unit = { c, l, uid, f, v, r -> captures.boolKeyframe = listOf(c, l, uid, f, v, r) },
        onSetPopupKeyframe: (Long, Long, Long, Int, Int, Boolean) -> Unit = { c, l, uid, f, v, r -> captures.popupKeyframe = listOf(c, l, uid, f, v, r) },
        playheadFrame: Int = 0,
        frameBytes: ByteArray? = null
    ) {
        val project = ProjectDocument.starter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = playheadFrame,
                    selectedOpacityKeyframes = emptyList(),
                    isPlaying = false,
                    saveMessage = "Saved",
                    savedProjects = emptyList(),
                    offlineAssets = emptyList(),
                    referencedOfflineAssetIds = emptySet(),
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onOpenProject = {},
                    onDeleteOfflineAsset = {},
                    onValidateEcproj = {},
                    onExportEcproj = {},
                    onExportEcprojx = {},
                    isExportingFrame = false,
                    onExportRenderedFrame = {},
                    onCancelFrameExport = {},
                    isExportingSequence = false,
                    isSequenceExportWritingOutput = false,
                    onExportPngSequence = { _, _, _, _, _ -> },
                    onCancelSequenceExport = {},
                    sequenceExportProgress = 0,
                    onNewProject = {},
                    onAddLayer = {},
                    onSelectLayer = {},
                    onDeleteLayer = {},
                    onMoveLayer = {},
                    onVisibilityChange = {},
                    onRenameLayer = {},
                    onOpacityChange = {},
                    onScrub = {},
                    onAddOpacityKeyframe = {},
                    onRemoveOpacityKeyframe = {},
                    onTogglePlayback = {},
                    onApplyComposition = { _, _, _, _ -> },
                    canonicalProject = canonical,
                    canEditCanonicalProject = true,
                    canonicalSelectedLayerIds = setOf(2L),
                    onRenameCanonicalLayer = { _, _, _ -> },
                    onAddCanonicalTextLayer = { _, _ -> },
                    onSetCanonicalLayerVisibility = { _, _, _ -> },
                    onMoveCanonicalLayer = { _, _, _ -> },
                    onDeleteCanonicalLayer = { _, _ -> },
                    onSetCanonicalLayerOpacity = { _, _, _ -> },
                    onAddCanonicalLayerOpacityKeyframe = { _, _, _, _ -> },
                    onRemoveCanonicalLayerOpacityKeyframe = { _, _, _ -> },
                    onMoveCanonicalCurveKey = onMoveCurveKey,
                    onRemoveCanonicalCurveKey = onRemoveCurveKey,
                    onAddCanonicalLayerTransformKeyframe = onAddTransformKeyframe,
                    onRemoveCanonicalLayerTransformKeyframe = onRemoveTransformKeyframe,
                    onAddCanonicalCurveKey = onAddCurveKey,
                    onSetCanonicalKeyframeRoving = { c, l, p, f, r -> captures.roving = listOf(c, l, p, f, r) },
                    onSetCanonicalKeyframeSpatialTangents = { c, l, p, f, inX, inY, inZ, outX, outY, outZ, auto ->
                        captures.spatialTangents = listOf(c, l, p, f, inX, inY, inZ, outX, outY, outZ, auto)
                    },
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onSetCanonicalLayerAnchor = { c, l, x, y -> captures.anchor = listOf(c, l, x, y) },
                    onSetCanonicalLayerOrientation = { c, l, x, y, z -> captures.orientation = listOf(c, l, x, y, z) },
                    onSetCanonicalLayerRotationX = { c, l, v -> captures.rotationX = listOf(c, l, v) },
                    onSetCanonicalLayerRotationY = { c, l, v -> captures.rotationY = listOf(c, l, v) },
                    onSetCanonicalLayerLocked = { c, l, v -> captures.locked = listOf(c, l, v) },
                    onSetCanonicalTextLayerText = { c, l, v -> captures.text = listOf(c, l, v) },
                    onSetCanonicalTextLayerTextWholeLayer = { c, l, v -> captures.textWholeLayer = listOf(c, l, v) },
                    onSetCanonicalTextLayerKeyframe = { c, l, uid, f, v, r -> captures.textKeyframe = listOf(c, l, uid, f, v, r) },
                    onSetCanonicalTextLayerSize = { c, l, v -> captures.textSize = listOf(c, l, v) },
                    onSetCanonicalTextLayerTracking = { c, l, v -> captures.textTracking = listOf(c, l, v) },
                    onSetCanonicalLayerScalarProperty = { c, l, uid, v -> captures.scalarProperty = listOf(c, l, uid, v) },
                    onSetCanonicalLayerVecProperty = { c, l, uid, x, y, z -> captures.vecProperty = listOf(c, l, uid, x, y, z) },
                    onSetCanonicalLayerColorProperty = { _, _, _, _, _, _, _ -> },
                    onSetCanonicalLayerBoolProperty = onSetBoolProperty,
                    onSetCanonicalLayerPopupProperty = onSetPopupProperty,
                    onSetCanonicalLayerBoolKeyframe = onSetBoolKeyframe,
                    onSetCanonicalLayerPopupKeyframe = onSetPopupKeyframe,
                    onRelinkCanonicalAsset = {},
                    onRenderCanonicalFrame = { compositionId, frame ->
                        captures.renderRequests.add(compositionId to frame)
                        frameBytes
                    },
                    safGrants = emptyList(),
                    missingManifestEntries = 0,
                    onReleaseSafGrant = { _, _ -> },
                    onReplaceOfflineAsset = {},
                    onCollectUnusedOfflineAssets = {}
                )
            }
        }
    }

    /** A synthetic 320x180 black raw-RGBA frame (8-byte header + pixels). */
    private fun blackFrameBytes(width: Int = 320, height: Int = 180): ByteArray {
        val bytes = ByteArray(8 + width * height * 4)
        bytes[0] = (width shr 24).toByte()
        bytes[1] = (width shr 16).toByte()
        bytes[2] = (width shr 8).toByte()
        bytes[3] = width.toByte()
        bytes[4] = (height shr 24).toByte()
        bytes[5] = (height shr 16).toByte()
        bytes[6] = (height shr 8).toByte()
        bytes[7] = height.toByte()
        for (index in 0 until width * height) {
            bytes[8 + index * 4 + 3] = 255.toByte()
        }
        return bytes
    }

    /** A layer with a 3-key position track (unequal legs) for Gap 1 tests. */
    private fun canonicalRovingLayer(): CanonicalProjectInfo {
        val keys = listOf(
            CanonicalCurveKey(frame = 0, value = 0.0, components = listOf(0.0, 90.0, 0.0)),
            CanonicalCurveKey(frame = 15, value = 30.0, components = listOf(30.0, 90.0, 0.0)),
            CanonicalCurveKey(frame = 30, value = 180.0, components = listOf(180.0, 90.0, 0.0))
        )
        val layer = CanonicalLayerInfo(
            id = 2, name = "Headline", source = "Text", visible = true,
            opacityPercent = 100.0, positionX = 30.0, positionY = 90.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0,
            transformKeyframes = mapOf("position" to listOf(0, 15, 30)),
            transformCurves = mapOf("position" to keys)
        )
        val composition = CanonicalCompositionInfo(
            id = 10, name = "Main", width = 640, height = 360,
            frameRateNum = 30, frameRateDen = 1, durationSeconds = 3.0,
            layers = listOf(layer)
        )
        return CanonicalProjectInfo(compositions = listOf(composition))
    }

    private fun canonicalM4Layer(locked: Boolean): CanonicalProjectInfo {
        val layer = CanonicalLayerInfo(
            id = 2, name = "Headline", source = "Text", visible = true,
            opacityPercent = 100.0, positionX = 320.0, positionY = 180.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 15.0,
            anchorX = 120.5, anchorY = 80.25,
            orientationX = 10.0, orientationY = 20.0, orientationZ = 30.0,
            rotationXDegrees = 45.0, rotationYDegrees = -30.0,
            locked = locked, threeD = false,
            textParams = CanonicalTextParams("Hello mobile", "Inter", 48.0, 12.5),
            shapeItems = listOf(
                CanonicalParamGroup(
                    name = "Rectangle Path 1", kind = "rect",
                    scalars = listOf(CanonicalScalarParam(101, "Roundness", 24.0)),
                    vecs = listOf(CanonicalVecParam(102, "Size", listOf(320.0, 160.0)))
                )
            ),
            effects = listOf(
                CanonicalParamGroup(
                    name = "Gaussian Blur", kind = "gaussianBlur",
                    scalars = listOf(CanonicalScalarParam(201, "Blurriness", 15.0))
                )
            )
        )
        val other = CanonicalLayerInfo(
            id = 3, name = "Clip", source = "Footage", visible = true,
            opacityPercent = 100.0, positionX = 0.0, positionY = 0.0,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0
        )
        val composition = CanonicalCompositionInfo(
            id = 10, name = "Main", width = 640, height = 360,
            frameRateNum = 30, frameRateDen = 1, durationSeconds = 3.0,
            layers = listOf(layer, other)
        )
        return CanonicalProjectInfo(compositions = listOf(composition))
    }

    @Test
    fun canonical3dTransformKeyframeControlsTargetSelectedLayerAndCurrentFrame() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(
            canonicalM4Layer(locked = false), captures,
            onAddTransformKeyframe = { c, l, property, frame, x, y, z ->
                captures.transformKeyframe = listOf(c, l, property, frame, x, y, z)
            },
            onRemoveTransformKeyframe = { c, l, property, frame ->
                captures.transformKeyframeRemoved = listOf(c, l, property, frame)
            },
            onAddCurveKey = { c, l, selector, frame, x, y, z ->
                captures.curveKey = listOf(c, l, selector, frame, x, y, z)
            }
        )
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        // The selector chips are horizontally scrollable inside the
        // vertically scrolling inspector. Bring the row into view, swipe
        // its own viewport until Anchor X is displayed, then invoke the chip's
        // semantics click (not a coordinate tap on a clipped child).
        compose.onNodeWithText("Curve graph · Position X").performScrollTo()
        val selectorRow = compose.onNodeWithTag("curve-selector-row").performScrollTo()
        val anchorSelector = compose.onNodeWithTag("curve-selector-transform-anchor")
        for (attempt in 0 until 4) {
            if (runCatching { anchorSelector.assertIsDisplayed() }.isSuccess) break
            selectorRow.performTouchInput { swipeLeft() }
        }
        anchorSelector.assertIsDisplayed().performClick()
        compose.onNodeWithText("Curve graph · Anchor X").assertIsDisplayed()
        // The graph add button fires the curve callback with the selected
        // track selector, not the transform callback, so it is located by
        // its stable visible text and asserted through the curve capture.
        compose.onNodeWithText("Add key at playhead").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "transform:anchor", 0, 120.5, 80.25, 0.0), captures.curveKey)
        compose.onNodeWithContentDescription("Add anchor keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "anchor", 0, 120.5, 80.25, 0.0), captures.transformKeyframe)
        compose.onNodeWithContentDescription("Add orientation keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "orientation", 0, 10.0, 20.0, 30.0), captures.transformKeyframe)
        compose.onNodeWithContentDescription("Add X rotation keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "rotationX", 0, 45.0, 0.0, 0.0), captures.transformKeyframe)
        compose.onNodeWithContentDescription("Add Y rotation keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "rotationY", 0, -30.0, 0.0, 0.0), captures.transformKeyframe)
        compose.onNodeWithContentDescription("Remove anchor keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "anchor", 0), captures.transformKeyframeRemoved)
    }

    @Test
    fun boolAndPopupInspectorControlsTargetSelectedLayerAndCurrentFrame() {
        val captures = CanonicalCaptures()
        val base = canonicalM4Layer(locked = false)
        val composition = base.compositions.single()
        val headline = composition.layers.first().copy(
            shapeItems = listOf(
                CanonicalParamGroup(
                    name = "Rectangle Path 1", kind = "rect",
                    bools = listOf(CanonicalBoolParam(301, "Per-Corner Roundness", false))
                )
            ),
            effects = listOf(
                CanonicalParamGroup(
                    name = "Gaussian Blur", kind = "gaussianBlur",
                    popups = listOf(
                        CanonicalPopupParam(402, "Blur Dimensions", 0, listOf("Horizontal", "Vertical", "Both"))
                    )
                )
            )
        )
        val canonical = base.copy(
            compositions = listOf(composition.copy(layers = listOf(headline, composition.layers[1])))
        )
        setCanonicalEditorContent(canonical, captures)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        // Checkbox toggle with no keys edits the static value.
        compose.onNodeWithContentDescription("Canonical shape bool parameter Rectangle Path 1 Per-Corner Roundness")
            .performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 301L, true), captures.boolProperty)
        // Add key authors a hold key at the playhead with the held value.
        compose.onNodeWithContentDescription("Add canonical shape bool keyframe Rectangle Path 1 Per-Corner Roundness frame 0")
            .performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 301L, 0, false, false), captures.boolKeyframe)
        // Popup trigger opens the option menu; picking an option with no
        // keys edits the static value. Click by content description: the
        // bare text "Both" is ambiguous (the interpolation-side checkbox
        // in the curve section shows the same word off-screen).
        compose.onNodeWithContentDescription("Canonical effect popup parameter Gaussian Blur Blur Dimensions")
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription("Canonical effect popup option Gaussian Blur Blur Dimensions Both")
            .performClick()
        assertEquals(listOf<Any>(10L, 2L, 402L, 2), captures.popupProperty)
        // Add key authors a hold key at the playhead with the held option.
        compose.onNodeWithContentDescription("Add canonical effect popup keyframe Gaussian Blur Blur Dimensions frame 0")
            .performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 402L, 0, 0, false), captures.popupKeyframe)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun curveGraphKeyMoveHasAccessibleCustomAction() {
        var moved: List<Any>? = null
        val base = canonicalM4Layer(locked = false)
        val composition = base.compositions.single()
        val headline = composition.layers.first().copy(
            transformCurves = mapOf(
                "position" to listOf(
                    CanonicalCurveKey(frame = 0, value = 100.0),
                    CanonicalCurveKey(frame = 15, value = 320.0),
                    CanonicalCurveKey(frame = 30, value = 500.0)
                )
            )
        )
        val canonical = base.copy(
            compositions = listOf(composition.copy(layers = listOf(headline, composition.layers[1])))
        )
        // Named argument, not a trailing lambda: the helper gained
        // onRemoveCurveKey/onAddTransformKeyframe/onRemoveTransformKeyframe
        // parameters after onMoveCurveKey, so a trailing lambda would bind to
        // the wrong (4-parameter) callback.
        setCanonicalEditorContent(canonical, CanonicalCaptures(), onMoveCurveKey = { c, l, selector, from, to ->
            moved = listOf(c, l, selector, from, to)
        })
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        // The official test API for this is performCustomAccessibilityActionWithLabel
        // (finds the node's custom action by label and invokes it); the earlier
        // performCustomAction name does not exist, and the CustomActions
        // semantics key is not public API, so it cannot be read directly.
        compose.onNodeWithContentDescription(
            "Curve graph for Position X with keys 0: 100.0, 15: 320.0, 30: 500.0"
        ).performScrollTo()
            .performCustomAccessibilityActionWithLabel("Move key at frame 15 to playhead frame 0")
        assertEquals(listOf<Any>(10L, 2L, "transform:position", 15, 0), moved)
    }

    @Test
    fun curveGraphLongPressDeletesNearestKeyThroughCanonicalCallback() {
        var removed: List<Any>? = null
        val base = canonicalM4Layer(locked = false)
        val composition = base.compositions.single()
        val headline = composition.layers.first().copy(
            transformCurves = mapOf(
                "position" to listOf(
                    CanonicalCurveKey(frame = 0, value = 100.0),
                    CanonicalCurveKey(frame = 15, value = 320.0),
                    CanonicalCurveKey(frame = 30, value = 500.0)
                )
            )
        )
        val canonical = base.copy(
            compositions = listOf(composition.copy(layers = listOf(headline, composition.layers[1])))
        )
        setCanonicalEditorContent(canonical, CanonicalCaptures(), onRemoveCurveKey = { c, l, selector, frame ->
            removed = listOf(c, l, selector, frame)
        })
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        val graph = compose.onNodeWithContentDescription(
            "Curve graph for Position X with keys 0: 100.0, 15: 320.0, 30: 500.0"
        ).performScrollTo()
        // TouchInjectionScope.longClick does not resolve in this project's
        // ui-test version, so hold the pointer down past the long-press
        // timeout with the primitive down/advance/up calls (the same gesture
        // detectTapGestures observes for onLongPress), then poll for the
        // callback like the preview-drag test does.
        val size = graph.fetchSemanticsNode().size
        val target = Offset(size.width * 15f / 90f, size.height / 2f)
        graph.performTouchInput {
            down(0, target)
            advanceEventTime(600)
            up(0)
        }
        compose.waitUntil(timeoutMillis = 5_000) { removed != null }
        assertEquals(listOf<Any>(10L, 2L, "transform:position", 15), removed)
    }

    @Test
    fun curveBezierHandleSelectionExposesPreciseSpeedAndInfluenceControls() {
        val base = canonicalM4Layer(locked = false)
        val composition = base.compositions.single()
        val headline = composition.layers.first().copy(
            transformCurves = mapOf(
                "position" to listOf(
                    CanonicalCurveKey(frame = 0, value = 100.0, outInterp = "Bezier", outSpeed = 12.0, outInfluence = 0.25),
                    CanonicalCurveKey(frame = 30, value = 320.0, inInterp = "Bezier", inSpeed = 10.0, inInfluence = 0.2)
                )
            )
        )
        val canonical = base.copy(
            compositions = listOf(composition.copy(layers = listOf(headline, composition.layers[1])))
        )
        setCanonicalEditorContent(canonical, CanonicalCaptures())
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        val graph = compose.onNodeWithContentDescription(
            "Curve graph for Position X with keys 0: 100.0, 30: 320.0"
        ).performScrollTo()
        val size = graph.fetchSemanticsNode().size
        val handlePoint = Offset(size.width * (7.5f / 90f), size.height * 0.88f)
        graph.performTouchInput {
            down(0, handlePoint)
            up(0)
        }
        compose.onNodeWithText("Selected outgoing handle · frame 0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Curve handle speed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Curve handle influence").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun canonicalAnchor3dRotationTextShapeEffectAndLockControlsTargetSelectedLayer() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalM4Layer(locked = false), captures)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithText("● Headline", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anchor Point · X / Y").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3D Rotation · Orientation X / Y / Z (°)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Text · Source Text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rectangle Path 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gaussian Blur").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Apply canonical anchor").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 120.5, 80.25), captures.anchor)
        compose.onNodeWithContentDescription("Apply canonical orientation").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 10.0, 20.0, 30.0), captures.orientation)
        compose.onNodeWithContentDescription("Apply canonical rotation X").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 45.0), captures.rotationX)
        compose.onNodeWithContentDescription("Apply canonical rotation Y").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, -30.0), captures.rotationY)
        compose.onNodeWithContentDescription("Apply canonical text").performScrollTo().performClick()
        // No Source Text keys: Apply routes to the whole-layer edit.
        assertEquals(listOf<Any>(10L, 2L, "Hello mobile"), captures.textWholeLayer)
        assertEquals(null, captures.textKeyframe)
        compose.onNodeWithContentDescription("Apply canonical text size").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 48.0), captures.textSize)
        compose.onNodeWithContentDescription("Apply canonical text tracking").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 12.5), captures.textTracking)
        compose.onNodeWithContentDescription("Apply canonical shape parameter Rectangle Path 1 Roundness").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 101L, 24.0), captures.scalarProperty)
        compose.onNodeWithContentDescription("Apply canonical shape parameter Rectangle Path 1 Size").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 102L, 320.0, 160.0, 0.0), captures.vecProperty)
        compose.onNodeWithContentDescription("Apply canonical effect parameter Gaussian Blur Blurriness").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 201L, 15.0), captures.scalarProperty)
        compose.onNodeWithContentDescription("Canonical layer locked").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, true), captures.locked)
    }

    @Test
    fun sourceTextTwoModeRoutesApplyByKeyframing() {
        // Keyframes OFF (no keys): the toggle is off and Apply edits the
        // whole layer. Keyframes ON (keys exist): the toggle is on and
        // Apply edits only the hold key at the playhead.
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalM4Layer(locked = false), captures)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithContentDescription("Toggle Source Text keyframes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keyframes off · edits change the whole layer everywhere.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Apply canonical text").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "Hello mobile"), captures.textWholeLayer)
        assertEquals(null, captures.textKeyframe)

        val keyed = canonicalM4Layer(locked = false).compositions.single().layers.first().copy(
            textParams = CanonicalTextParams(
                "Hello mobile", "Inter", 48.0, 12.5, uid = 77L,
                keys = listOf(CanonicalTextKey(0, "Hello mobile"), CanonicalTextKey(15, "Frame fifteen"))
            )
        )
        val keyedCanonical = CanonicalProjectInfo(
            compositions = listOf(
                canonicalM4Layer(locked = false).compositions.single().copy(layers = listOf(keyed))
            )
        )
        val keyedCaptures = CanonicalCaptures()
        setCanonicalEditorContent(keyedCanonical, keyedCaptures, playheadFrame = 15)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithText("Keyframes on · edits change only the hold key at the playhead.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Apply canonical text").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, 77L, 15, "Hello mobile", false), keyedCaptures.textKeyframe)
        assertEquals(null, keyedCaptures.textWholeLayer)
        // Toggling keyframes off routes the same draft through the
        // whole-layer edit (keys dropped, static text set).
        compose.onNodeWithContentDescription("Toggle Source Text keyframes").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "Hello mobile"), keyedCaptures.textWholeLayer)
    }

    @Test
    fun lockedCanonicalLayerDisablesItsEditControls() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalM4Layer(locked = true), captures)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithContentDescription("Canonical layer locked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Apply canonical anchor").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Apply canonical text").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Apply canonical shape parameter Rectangle Path 1 Roundness").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Apply canonical effect parameter Gaussian Blur Blurriness").performScrollTo().assertIsNotEnabled()
        assertEquals(null, captures.anchor)
        assertEquals(null, captures.text)
        assertEquals(null, captures.scalarProperty)
    }

    // --- M4-L10 Gap 1: roving/spatial path interpolation ---

    @Test
    fun motionPathOverlayDrawsForSpatialPositionTrack() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalRovingLayer(), captures, frameBytes = blackFrameBytes())
        // The workspace frame arrives through a LaunchedEffect that decodes
        // on a background dispatcher, so waitForIdle can return before the
        // bitmap (and the overlay over it) is composed; poll for the node.
        // First prove the supplied native RGBA frame is decoded and displayed;
        // the vector overlay is meaningful only over a real composition frame.
        assertNotNull(
            "Motion-path fixture frame must decode",
            com.codeair.effectcraft.engine.RenderedFrameBitmap.decode(blackFrameBytes())
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithContentDescription("Rendered composition frame").assertIsDisplayed()
            }.isSuccess
        }
        compose.onNodeWithContentDescription("Rendered composition frame").assertIsDisplayed()
        assertEquals(true, captures.renderRequests.any { it == (10L to 0L) })
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Motion path overlay")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The workspace preview renders the supplied frame, and the selected
        // layer's 3-key position track draws its motion-path overlay over it.
        compose.onNodeWithContentDescription("Motion path overlay").assertIsDisplayed()
    }

    @Test
    fun motionPathOverlayMatchesCappedPreviewImageBounds() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalRovingLayer(), captures, frameBytes = blackFrameBytes())
        // The inspector preview image is capped at 220dp; the overlay over
        // it must share the image's exact bounds, not the sheet width.
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithContentDescription("EffectCraft rendered composition frame").assertIsDisplayed()
            }.isSuccess
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Motion path overlay")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val imageBounds = compose.onNodeWithContentDescription("EffectCraft rendered composition frame")
            .fetchSemanticsNode().boundsInRoot
        val overlays = compose.onAllNodesWithContentDescription("Motion path overlay")
            .fetchSemanticsNodes()
        val matching = overlays.filter { overlay ->
            kotlin.math.abs(overlay.boundsInRoot.left - imageBounds.left) <= 1f &&
                kotlin.math.abs(overlay.boundsInRoot.top - imageBounds.top) <= 1f &&
                kotlin.math.abs(overlay.boundsInRoot.width - imageBounds.width) <= 1f &&
                kotlin.math.abs(overlay.boundsInRoot.height - imageBounds.height) <= 1f
        }
        assertTrue(
            "An overlay must sit exactly over the inspector preview image",
            matching.isNotEmpty()
        )
        matching.forEach { overlay ->
            assertTrue(
                "Overlay height must not exceed the 220dp preview cap",
                overlay.boundsInRoot.height <= 220f + 1f
            )
        }
    }

    @Test
    fun rovingToggleTargetsSelectedLayerAndCurrentFrame() {
        val captures = CanonicalCaptures()
        setCanonicalEditorContent(canonicalRovingLayer(), captures, playheadFrame = 15)
        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        // The inspector opens on the position property at the playhead (frame
        // 15), where the middle key sits: the roving switch, the auto-path
        // switch and the straight-path button are all present and operable
        // through their semantics actions.
        compose.onNodeWithContentDescription("Roving keyframe for position at frame 15")
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15, true), captures.roving)
        compose.onNodeWithContentDescription("Auto motion path for position at frame 15")
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(
            listOf<Any>(10L, 2L, "position", 15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false),
            captures.spatialTangents
        )
        compose.onNodeWithContentDescription("Straight path for position at frame 15")
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(
            listOf<Any>(10L, 2L, "position", 15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false),
            captures.spatialTangents
        )
        compose.onNodeWithContentDescription("Show motion path overlay")
            .performScrollTo().assertIsDisplayed().performClick()
    }
}
