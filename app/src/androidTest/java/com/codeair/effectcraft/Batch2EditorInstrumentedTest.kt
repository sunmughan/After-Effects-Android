package com.codeair.effectcraft

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
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
        canonicalProject: CanonicalProjectInfo? = null,
        onExportEcproj: () -> Unit = {},
        onExportEcprojx: () -> Unit = {},
        onExportRenderedFrame: () -> Unit = {},
        onExportMp4Sequence: (Int, Int, Int, Int, Int, Int, RenderOutputFormat) -> Unit = { _, _, _, _, _, _, _ -> }
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
            onImportProject = {},
            onExportEcproj = onExportEcproj,
            onExportEcprojx = onExportEcprojx,
            isExportingFrame = false,
            onExportRenderedFrame = onExportRenderedFrame,
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
            canonicalProject = canonicalProject,
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

    private fun canonicalProject() = CanonicalProjectInfo(
        compositions = listOf(
            CanonicalCompositionInfo(
                id = 1L,
                name = "Comp",
                width = 1920,
                height = 1080,
                frameRateNum = 30,
                frameRateDen = 1,
                durationSeconds = 5.0,
                layers = emptyList()
            )
        )
    )

    @Test
    fun exportVideoButtonOpensMp4SettingsInOneTap() {
        compose.setContent { Editor(canonicalProject = canonicalProject()) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Export video (MP4)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Export video (MP4)").assertIsEnabled()
        compose.onNodeWithContentDescription("Export video (MP4)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("H.264 MP4 settings").assertIsDisplayed()
    }

    @Test
    fun exportVideoButtonIsDisabledWithoutACanonicalProject() {
        compose.setContent { Editor() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Export video (MP4)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Export video (MP4)").assertIsNotEnabled()
    }

    @Test
    fun exportOverflowMenuListsFourLabelledExportsAndNoIconOnlyButtons() {
        compose.setContent { Editor(canonicalProject = canonicalProject()) }
        compose.waitForIdle()
        // Phone bar: the four exports live in the single "More options"
        // overflow, not behind a separate export overflow button.
        compose.onNodeWithContentDescription("More options").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Export .ecproj").assertIsDisplayed()
        compose.onNodeWithText("Export .ecprojx XML").assertIsDisplayed()
        compose.onNodeWithText("Export rendered frame PNG").assertIsDisplayed()
        compose.onNodeWithText("Export PNG sequence ZIP").assertIsDisplayed()
        // The old four FileDownload icons are gone: none of the four
        // exports exists as an icon-only content description any more.
        compose.onNodeWithContentDescription("Export .ecproj").assertDoesNotExist()
        compose.onNodeWithContentDescription("Export .ecprojx XML").assertDoesNotExist()
        compose.onNodeWithContentDescription("Export rendered frame PNG").assertDoesNotExist()
        compose.onNodeWithContentDescription("Export PNG sequence ZIP").assertDoesNotExist()
    }

    @Test
    fun exportOverflowMenuEntriesFireTheirCallbacks() {
        var ecproj = 0
        var ecprojx = 0
        var frame = 0
        compose.setContent {
            Editor(
                canonicalProject = canonicalProject(),
                onExportEcproj = { ecproj++ },
                onExportEcprojx = { ecprojx++ },
                onExportRenderedFrame = { frame++ }
            )
        }
        compose.waitForIdle()
        // Phone bar: each export entry is reached through "More options";
        // the menu closes after every entry, so it is reopened each time.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export .ecproj").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export .ecprojx XML").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export rendered frame PNG").performClick()
        compose.waitForIdle()
        assertEquals(1, ecproj)
        assertEquals(1, ecprojx)
        assertEquals(1, frame)
    }

    @Test
    fun exportOverflowMenuSequenceEntryOpensPngSequenceSettings() {
        compose.setContent { Editor(canonicalProject = canonicalProject()) }
        compose.waitForIdle()
        // Phone bar: the PNG sequence entry lives in "More options".
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export PNG sequence ZIP").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PNG sequence settings").assertIsDisplayed()
    }
}
