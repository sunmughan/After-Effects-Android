package com.codeair.effectcraft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalCurveKey
import com.codeair.effectcraft.data.CanonicalLayerInfo
import com.codeair.effectcraft.data.CanonicalParamGroup
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.CanonicalScalarParam
import com.codeair.effectcraft.data.CanonicalTextParams
import com.codeair.effectcraft.data.CanonicalVecParam
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.PropertyKeyframe
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
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
        var textSize: List<Any>? = null
        var textTracking: List<Any>? = null
        var scalarProperty: List<Any>? = null
        var vecProperty: List<Any>? = null
    }

    private fun setCanonicalEditorContent(
        canonical: CanonicalProjectInfo,
        captures: CanonicalCaptures,
        onMoveCurveKey: (Long, Long, String, Int, Int) -> Unit = { _, _, _, _, _ -> },
        onRemoveCurveKey: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
        onAddTransformKeyframe: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
        onRemoveTransformKeyframe: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
        onAddCurveKey: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> }
    ) {
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
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onSetCanonicalLayerAnchor = { c, l, x, y -> captures.anchor = listOf(c, l, x, y) },
                    onSetCanonicalLayerOrientation = { c, l, x, y, z -> captures.orientation = listOf(c, l, x, y, z) },
                    onSetCanonicalLayerRotationX = { c, l, v -> captures.rotationX = listOf(c, l, v) },
                    onSetCanonicalLayerRotationY = { c, l, v -> captures.rotationY = listOf(c, l, v) },
                    onSetCanonicalLayerLocked = { c, l, v -> captures.locked = listOf(c, l, v) },
                    onSetCanonicalTextLayerText = { c, l, v -> captures.text = listOf(c, l, v) },
                    onSetCanonicalTextLayerSize = { c, l, v -> captures.textSize = listOf(c, l, v) },
                    onSetCanonicalTextLayerTracking = { c, l, v -> captures.textTracking = listOf(c, l, v) },
                    onSetCanonicalLayerScalarProperty = { c, l, uid, v -> captures.scalarProperty = listOf(c, l, uid, v) },
                    onSetCanonicalLayerVecProperty = { c, l, uid, x, y, z -> captures.vecProperty = listOf(c, l, uid, x, y, z) },
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
        // Bring the graph section into view through its header first: the
        // chips live in a horizontally scrolling row nested inside the
        // vertically scrolling sheet, so scroll the section itself into
        // view before tapping a chip in it.
        compose.onNodeWithText("Curve graph · Position X").performScrollTo()
        // Tap the chip with a real touch event, not the semantics click
        // action: performClick on this chip left the selection on Position
        // X (the next add fired transform:position), while the raw
        // down/up path is the proven gesture route in this file.
        val anchorChip = compose.onNodeWithText("Anchor X").performScrollTo()
        val chipSize = anchorChip.fetchSemanticsNode().size
        anchorChip.performTouchInput {
            down(0, Offset(chipSize.width / 2f, chipSize.height / 2f))
            advanceEventTime(100)
            up(0)
        }
        // Existence, not visibility: the header text proves the selection
        // moved to Anchor X even when the sheet viewport leaves the header
        // itself just out of view.
        compose.onNodeWithText("Curve graph · Anchor X").assertExists()
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
        assertEquals(listOf<Any>(10L, 2L, "Hello mobile"), captures.text)
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
}
