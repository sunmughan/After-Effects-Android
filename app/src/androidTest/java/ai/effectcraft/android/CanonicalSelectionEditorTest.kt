package ai.effectcraft.android

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import java.util.concurrent.CopyOnWriteArrayList
import ai.effectcraft.android.data.CanonicalCompositionInfo
import ai.effectcraft.android.data.CanonicalLayerInfo
import ai.effectcraft.android.data.CanonicalProjectInfo
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.ui.EditorScreen
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
        compose.onNodeWithContentDescription("Canonical transform keyframes: 0, 15, 30")
            .assertIsDisplayed().performClick() // screen-reader click action must seek just like a pointer tap
        assertEquals(30, workspaceFrame.intValue)

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

        // Native transform keys are authored at the same workspace frame,
        // using the selected canonical layer's current inspector values.
        compose.onNodeWithContentDescription("Add position keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15, 10.0, 20.0, 0.0), transformKeyframe)
        compose.onNodeWithContentDescription("Remove position keyframe").performScrollTo().performClick()
        assertEquals(listOf<Any>(10L, 2L, "position", 15), removedTransformKeyframe)

        // Tap selects one layer; long-press adds a second layer to the set.
        compose.onNodeWithContentDescription("Canonical layer row Title").performClick()
        assertEquals(setOf(3L), selectionState.value)
        compose.onNodeWithText("● Title", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Canonical layer row Background")
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
