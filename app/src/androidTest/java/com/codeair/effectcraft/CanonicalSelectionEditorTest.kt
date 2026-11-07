package com.codeair.effectcraft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import java.util.concurrent.CopyOnWriteArrayList
import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalCurveKey
import com.codeair.effectcraft.data.CanonicalLayerInfo
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class QuadGestureEdit(
    val compositionId: Long,
    val positions: Map<Long, Pair<Double, Double>>,
    val scales: Map<Long, Pair<Double, Double>>,
    val rotations: Map<Long, Double>
)

class CanonicalSelectionEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun layer(id: Long, name: String, x: Double, y: Double, locked: Boolean = false): CanonicalLayerInfo {
        return CanonicalLayerInfo(
            id = id, name = name, source = "Text", visible = true,
            opacityPercent = 100.0, positionX = x, positionY = y,
            scaleX = 100.0, scaleY = 100.0, rotationDegrees = 0.0, locked = locked
        )
    }

    @Test
    fun tapLongPressGestureUndoRedoAndReparseKeepCanonicalSelection() {
        val composition = CanonicalCompositionInfo(
            id = 10, name = "Main", width = 1920, height = 1080,
            frameRateNum = 30, frameRateDen = 1, durationSeconds = 3.0,
            layers = listOf(
                layer(2, "Background", 10.0, 20.0).copy(transformKeyframes = mapOf("position" to listOf(0, 15, 30), "scale" to listOf(0), "rotation" to listOf(30))),
                layer(3, "Title", 30.0, 40.0),
                layer(4, "Locked", 50.0, 60.0, locked = true)
            )
        )
        val canonicalState = mutableStateOf(CanonicalProjectInfo(compositions = listOf(composition)))
        val selectionState = mutableStateOf(setOf(2L))
        val workspaceFrame = mutableIntStateOf(0)
        val renderedFrames = CopyOnWriteArrayList<Long>()
        val selectionChanges = mutableListOf<Set<Long>>()
        var undoClicks = 0
        var redoClicks = 0
        var visibilityForSelection: List<Any>? = null
        var gestureEdit: QuadGestureEdit? = null
        var transformKeyframe: List<Any>? = null
        var removedTransformKeyframe: List<Any>? = null
        var interpolationAction: List<Any>? = null
        var deleteCalls = 0
        val project = ProjectDocument.starter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = workspaceFrame.intValue,
                    selectedOpacityKeyframes = emptyList(),
                    canonicalTransformKeyframes = listOf(0, 15, 30),
                    canonicalTransformDurationFrames = 90,
                    canonicalColorKeys = listOf(
                        CanonicalCurveKey(frame = 0, value = 1.0, components = listOf(1.0, 0.0, 0.0, 1.0)),
                        CanonicalCurveKey(frame = 15, value = 0.0, components = listOf(0.0, 1.0, 0.0, 1.0)),
                        CanonicalCurveKey(frame = 30, value = 0.0, components = listOf(0.0, 0.0, 1.0, 1.0))
                    ),
                    canonicalColorDurationFrames = 90,
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
                    onScrub = { workspaceFrame.intValue = it },
                    onAddOpacityKeyframe = {},
                    onRemoveOpacityKeyframe = {},
                    onTogglePlayback = {},
                    onApplyComposition = { _, _, _, _ -> },
                    canonicalProject = canonicalState.value,
                    canEditCanonicalProject = true,
                    canonicalSelectedLayerIds = selectionState.value,
                    onCanonicalSelectionChange = { selectionState.value = it; selectionChanges.add(it) },
                    canUndoCanonical = true,
                    canRedoCanonical = true,
                    onUndoCanonical = { undoClicks += 1 },
                    onRedoCanonical = { redoClicks += 1 },
                    onSetCanonicalLayerOpacityForSelection = { _, _, _ -> },
                    onSetCanonicalLayerVisibilityForSelection = { c, ids, visible -> visibilityForSelection = listOf(c, ids, visible) },
                    onGestureEditCanonicalSelection = { c, positions, scales, rotations -> gestureEdit = QuadGestureEdit(c, positions, scales, rotations) },
                    onRenameCanonicalLayer = { _, _, _ -> },
                    onAddCanonicalTextLayer = { _, _ -> },
                    onSetCanonicalLayerVisibility = { _, _, _ -> },
                    onMoveCanonicalLayer = { _, _, _ -> },
                    onDeleteCanonicalLayer = { _, _ -> deleteCalls += 1 },
                    onSetCanonicalLayerOpacity = { _, _, _ -> },
                    onAddCanonicalLayerOpacityKeyframe = { _, _, _, _ -> },
                    onAddCanonicalLayerTransformKeyframe = { c, l, property, frame, x, y, z -> transformKeyframe = listOf(c, l, property, frame, x, y, z) },
                    onRemoveCanonicalLayerTransformKeyframe = { c, l, property, frame -> removedTransformKeyframe = listOf(c, l, property, frame) },
                    onSetCanonicalLayerTransformInterpolation = { c, l, property, frame, preset -> interpolationAction = listOf(c, l, property, frame, preset) },
                    onRemoveCanonicalLayerOpacityKeyframe = { _, _, _ -> },
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
                    onRenderCanonicalFrame = { _, frame -> renderedFrames.add(frame); null },
                    safGrants = emptyList(),
                    missingManifestEntries = 0,
                    onReleaseSafGrant = { _, _ -> },
                    onReplaceOfflineAsset = {},
                    onCollectUnusedOfflineAssets = {}
                )
            }
        }

        // Workspace transform markers are available before opening the modal
        // canonical inspector; tapping the lane seeks the nearest key frame.
        // Lanes are 48dp tap targets, so on a small viewport the lane may
        // sit below the fold: scroll it into view first (the color lane
        // below already does this).
        workspaceFrame.intValue = 29 // accessible activation seeks the key nearest the current playhead
        compose.onNodeWithTag("timeline-track-list")
            .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, 500f) }
        compose.onNodeWithContentDescription("Canonical transform keyframes: 0, 15, 30")
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(30, workspaceFrame.intValue)

        // The COLOR lane draws one swatch per color keyframe (the key's own
        // RGBA, not the layer's current color) and seeks the nearest key.
        // The lane sits below the transform lane, so scroll it into view
        // before asserting it is displayed. Tap at frame 15's own position
        // in the lane: the tap detector seeks the nearest key to the tapped
        // frame, which must be 15 (a centre tap would land near frame 45
        // and seek 30 instead).
        val colorLane = compose.onNodeWithContentDescription("Canonical color keyframes: 0: #FF0000, 15: #00FF00, 30: #0000FF")
        colorLane.performScrollTo().assertIsDisplayed()
        workspaceFrame.intValue = 14
        val laneBounds = colorLane.fetchSemanticsNode().boundsInRoot
        colorLane.performTouchInput {
            click(Offset(laneBounds.width * 15f / 89f, laneBounds.height / 2f))
        }
        assertEquals(15, workspaceFrame.intValue)

        compose.onNodeWithContentDescription("Edit imported .ecproj").performClick()
        compose.onNodeWithText("CANONICAL .ECPROJ").assertIsDisplayed()
        compose.onNodeWithText("● Background", substring = true).assertIsDisplayed()

        // The canonical preview follows the workspace timeline in both
        // directions; scrubbing the main playhead requests that real frame.
        workspaceFrame.intValue = 15
        compose.waitUntil(timeoutMillis = 5_000) { renderedFrames.contains(15L) }
        compose.onNodeWithText("Frame 15 / 89").assertIsDisplayed()

        // Native transform metadata exposes key markers; tapping the inspector
        // jump action updates the shared playhead and requests that preview.
        workspaceFrame.intValue = 20
        compose.waitUntil(timeoutMillis = 5_000) { renderedFrames.contains(20L) }
        compose.onNodeWithContentDescription("Jump to Position keyframe").performScrollTo().performClick()
        assertEquals(15, workspaceFrame.intValue)
        compose.onNodeWithText("Frame 15 / 89").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Select incoming interpolation side").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Set position key to Easy Ease on incoming side at frame 15").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15, "incoming_easy_ease"), interpolationAction)
        compose.onNodeWithContentDescription("Select outgoing interpolation side").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Set position key to Hold on outgoing side at frame 15").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15, "outgoing_hold"), interpolationAction)

        // Native transform keys are authored at the same workspace frame,
        // using the selected canonical layer's current inspector values.
        compose.onNodeWithContentDescription("Add position keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15, 10.0, 20.0, 0.0), transformKeyframe)
        compose.onNodeWithContentDescription("Remove position keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15), removedTransformKeyframe)

        // Tap selects one layer; long-press adds a second layer to the set.
        // The keyframe steps above scrolled the sheet down to the inspector,
        // so the layer rows are out of the visible viewport: a click injected
        // at their off-screen position is swallowed and leaves the selection
        // on Background (line-205 failure: expected [3] but was [2]).
        // Scroll each row back into view first, as the indicator and preview
        // steps below already do.
        // The row scrollTo above only scrolls the inner layer list, not the
        // sheet itself, so the sheet stays scrolled down at the inspector
        // and the tap is still swallowed. Scroll the sheet header, a direct
        // child of the sheet column, back into view first to bring the rows
        // back, then tap the row.
        compose.onNodeWithText("CANONICAL .ECPROJ").performScrollTo()
        compose.onNodeWithContentDescription("Canonical layer row Title").performScrollTo().performClick()
        assertEquals(setOf(3L), selectionState.value)
        compose.onNodeWithText("● Title", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Canonical layer row Background")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(setOf(3L, 2L), selectionState.value)
        // The keyframe step above scrolled the sheet down to the inspector,
        // so scroll the top-of-sheet indicator back into view before
        // asserting it renders for the two-layer selection.
        compose.onNodeWithText("2 layers selected", substring = true).performScrollTo().assertIsDisplayed()

        // A horizontal drag on the preview moves both selected layers right
        // in composition pixels and leaves their heights unchanged.
        compose.onNodeWithContentDescription("Canonical preview gesture area").performScrollTo().performTouchInput {
            swipe(start = center - Offset(80f, 0f), end = center + Offset(80f, 0f), durationMillis = 300)
        }
        // The gesture callback arrives through input dispatch, which an idle
        // wait does not guarantee; poll for it instead of reading too early.
        compose.waitUntil(timeoutMillis = 5_000) { gestureEdit != null }
        val edit = gestureEdit ?: error("Preview drag must invoke the gesture edit callback")
        assertEquals(10L, edit.compositionId)
        assertEquals(2, edit.positions.size)
        assertTrue(edit.positions.getValue(2).first > 10.0)
        assertTrue(edit.positions.getValue(3).first > 30.0)
        assertEquals(20.0, edit.positions.getValue(2).second, 0.001)
        assertEquals(40.0, edit.positions.getValue(3).second, 0.001)
        assertEquals(emptyMap<Long, Pair<Double, Double>>(), edit.scales)
        assertEquals(emptyMap<Long, Double>(), edit.rotations)

        // With two layers selected, the visibility switch applies to the set.
        compose.onNodeWithContentDescription("Canonical layer visibility").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, setOf(2L, 3L), false), visibilityForSelection)

        // Canonical undo and redo buttons invoke their callbacks.
        compose.onNodeWithText("Canonical Undo").performScrollTo().performClick()
        compose.onNodeWithText("Canonical Redo").performScrollTo().performClick()
        assertEquals(1, undoClicks)
        assertEquals(1, redoClicks)

        // A re-parse that drops the Title layer prunes the selection to the
        // surviving Background layer instead of pointing at stale data.
        canonicalState.value = CanonicalProjectInfo(
            compositions = listOf(composition.copy(layers = listOf(layer(2, "Background", 10.0, 20.0), layer(4, "Locked", 50.0, 60.0, locked = true))))
        )
        compose.waitForIdle()
        assertEquals(setOf(2L), selectionState.value)
        compose.onNodeWithText("● Background", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("2 layers selected", substring = true).assertCountEquals(0)

        // Deleting the selected layer moves selection to its neighbor.
        compose.onNodeWithContentDescription("Remove canonical layer").performScrollTo().performClick()
        assertEquals(1, deleteCalls)
        assertEquals(setOf(4L), selectionState.value)
        assertEquals(setOf(4L), selectionChanges.last())
    }
}
