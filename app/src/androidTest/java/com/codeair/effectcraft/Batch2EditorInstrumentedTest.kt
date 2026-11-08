package com.codeair.effectcraft

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Rule
import org.junit.Test

/**
 * Batch 2 editor tests (P05 transport, P10 export progress, P12 export
 * discoverability), driving EditorScreen directly with a compose rule
 * the way AdaptiveWorkspaceSmokeTest does.
 */
class Batch2EditorInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Editor(
        project: ProjectDocument = ProjectDocument.starter(),
        isPlaying: Boolean = false,
        isExportingMp4: Boolean = false,
        mp4ExportProgress: Int = 0,
        isExportingSequence: Boolean = false,
        sequenceExportProgress: Int = 0,
        audioPreviewLabel: String = "",
        hasCanonicalProject: Boolean = false,
        onExportMp4Sequence: () -> Unit = {}
    ) {
        EditorScreen(
            project = project,
            selectedLayer = project.layers.first(),
            selectedLayerId = project.layers.first().id,
            playheadFrame = 0,
            selectedOpacityKeyframes = emptyList(),
            audioPreviewLabel = audioPreviewLabel,
            isPlaying = isPlaying,
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
            isExportingSequence = isExportingSequence,
            isSequenceExportWritingOutput = false,
            onExportPngSequence = { _, _, _, _, _ -> },
            onCancelSequenceExport = {},
            sequenceExportProgress = sequenceExportProgress,
            isExportingMp4 = isExportingMp4,
            mp4ExportProgress = mp4ExportProgress,
            onExportMp4Sequence = onExportMp4Sequence,
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
            canEditCanonicalProject = hasCanonicalProject,
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
            onSetCanonicalLayerColorProperty = { _, _, _, _, _, _ -> },
            onRelinkCanonicalAsset = {},
            onRenderCanonicalFrame = { _, _ -> null },
            safGrants = emptyList(),
            missingManifestEntries = 0,
            onReleaseSafGrant = { _, _ -> },
            onReplaceOfflineAsset = {},
            onCollectUnusedOfflineAssets = {}
        )
    }

    @Test
    fun transportRowShowsPlayAndTimecodeBesideKeyframeControls() {
        compose.setContent { Editor() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play timeline").assertIsDisplayed()
        compose.onNodeWithText("00:00:00:00").assertIsDisplayed()
        compose.onNodeWithText("+ Keyframe").assertIsDisplayed()
        compose.onNodeWithText("− Keyframe").assertIsDisplayed()
    }

    @Test
    fun transportRowShowsAudioLabelWhenAudioIsAtPlayhead() {
        compose.setContent { Editor(audioPreviewLabel = "Audio · theme.wav") }
        compose.waitForIdle()
        compose.onNodeWithText("Audio · theme.wav").assertIsDisplayed()
    }

    @Test
    fun timelineTrackListScrollsToLaterLayers() {
        val manyLayers = ProjectDocument.starter().copy(
            layers = (1..10).map { LayerDocument.newShape(it) }
        )
        compose.setContent { Editor(project = manyLayers) }
        compose.waitForIdle()
        compose.onNodeWithTag("timeline-track-list").assertExists()
        compose.onNodeWithTag("timeline-track-list").performScrollToIndex(9)
        compose.waitForIdle()
        compose.onNodeWithText("Shape 10").assertIsDisplayed()
    }

    @Test
    fun mp4ExportProgressShowsBarPercentageAndCancel() {
        compose.setContent { Editor(isExportingMp4 = true, mp4ExportProgress = 42) }
        compose.waitForIdle()
        compose.onNodeWithText("Exporting MP4 · 42%").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun sequenceExportProgressShowsItsOwnLabel() {
        compose.setContent { Editor(isExportingSequence = true, sequenceExportProgress = 17) }
        compose.waitForIdle()
        compose.onNodeWithText("Exporting PNG sequence · 17%").assertIsDisplayed()
    }

    @Test
    fun noExportProgressRowWhenNothingIsExporting() {
        compose.setContent { Editor() }
        compose.waitForIdle()
        compose.onNodeWithText("Exporting MP4 · 0%").assertDoesNotExist()
        compose.onNodeWithText("Exporting PNG sequence · 0%").assertDoesNotExist()
    }
}
