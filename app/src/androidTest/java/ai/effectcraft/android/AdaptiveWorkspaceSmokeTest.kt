package ai.effectcraft.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import ai.effectcraft.android.data.CanonicalCompositionInfo
import ai.effectcraft.android.data.CanonicalLayerInfo
import ai.effectcraft.android.data.CanonicalProjectInfo
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.data.PropertyKeyframe
import ai.effectcraft.android.ui.EditorScreen
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
                    onExportPngSequence = {},
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
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
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
        assertEquals(listOf(10L, 2L, 0), removed)
    }
}
