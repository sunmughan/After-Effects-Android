package com.codeair.effectcraft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.codeair.effectcraft.data.CanonicalProjectRepository
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RepositoryPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun repositoryPickerListsEntriesAndSwitchesProject() {
        val opened = mutableListOf<String>()
        val entries = listOf(
            CanonicalProjectRepository.Metadata(
                id = "alpha", displayName = "Alpha project", format = "ecproj",
                schemaVersion = 1, documentSha256 = "aa", updatedAtMillis = 1L, sourceName = null
            ),
            CanonicalProjectRepository.Metadata(
                id = "beta", displayName = "Beta project", format = "ecprojx",
                schemaVersion = 1, documentSha256 = "bb", updatedAtMillis = 2L, sourceName = null
            )
        )
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
                    repositoryProjects = entries,
                    activeRepositoryProjectId = "alpha",
                    onOpenRepositoryProject = { opened.add(it) },
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

        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("Open canonical project").assertIsDisplayed()
        compose.onNodeWithText("Alpha project").assertIsDisplayed()
        compose.onNodeWithText("Beta project").assertIsDisplayed()
        compose.onNodeWithText("ecproj · schema v1 · Current", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Beta project").performClick()
        assertEquals(listOf("beta"), opened)
    }
}
