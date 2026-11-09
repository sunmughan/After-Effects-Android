package com.codeair.effectcraft

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.DraftFrameRenderer
import com.codeair.effectcraft.ui.EditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Batch 2 (P06): the draft renderer draws real frames — pixel-checked
 * here — and the editor shows them instead of the dead
 * "Draft preview not rendered" placeholder.
 */
class DraftPreviewInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun singleLayerProject(width: Int, height: Int, tint: Long, opacity: Float = 1f): ProjectDocument =
        ProjectDocument.starter().copy(
            width = width,
            height = height,
            layers = listOf(
                LayerDocument.newShape(1).copy(tint = tint, opacity = opacity)
            )
        )

    @Test
    fun rendererDrawsSingleLayerAtCompositionSize() {
        val bitmap = DraftFrameRenderer.render(singleLayerProject(320, 240, 0xFF112233), 0)
        assertNotNull(bitmap)
        bitmap!!
        assertEquals(320, bitmap.width)
        assertEquals(240, bitmap.height)
        assertEquals(0xFF112233.toInt(), bitmap.getPixel(10, 10))
    }

    @Test
    fun rendererCapsLongSideAtPreviewSize() {
        val bitmap = DraftFrameRenderer.render(ProjectDocument.starter(), 0)
        assertNotNull(bitmap)
        bitmap!!
        assertEquals(405, bitmap.width)
        assertEquals(720, bitmap.height)
    }

    @Test
    fun rendererStacksLaterLayersOnTopWithOpacity() {
        val bottom = LayerDocument.newShape(1).copy(tint = 0xFF0000FF)
        val top = LayerDocument.newShape(2).copy(tint = 0xFFFFFFFF, opacity = 0.5f)
        val project = ProjectDocument.starter().copy(
            width = 320, height = 240, layers = listOf(bottom, top)
        )
        val bitmap = DraftFrameRenderer.render(project, 0)
        assertNotNull(bitmap)
        bitmap!!
        // White at 0.5 over blue: red/green 127, blue 255.
        assertEquals(0xFF7F7FFF.toInt(), bitmap.getPixel(10, 10))
    }

    @Test
    fun rendererReturnsNullForUnusableSize() {
        assertEquals(null, DraftFrameRenderer.render(singleLayerProject(0, 240, 0xFF112233), 0))
    }

    @Test
    fun draftPreviewShowsRenderedFrameInsteadOfPlaceholder() {
        val project = ProjectDocument.starter()
        compose.setContent {
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
        compose.waitForIdle()
        // Run 42441138764: the P06 draft render runs on
        // Dispatchers.Default inside a LaunchedEffect, which
        // waitForIdle() does not track — on a loaded emulator the
        // frame had not landed when the assertion ran right after
        // idle, so the node was absent and assertIsDisplayed
        // reported "not displayed". Wait for the real condition
        // instead (the house pattern for async content — see
        // EditorSmokeTest's dialog-removal wait). Run 42445908283:
        // 10 s was still too short on a cold emulator (the frame
        // took longer than 10 s to appear in the retry, while it
        // passed the first attempt) — the budget is 30 s, matching
        // AdaptiveWorkspaceSmokeTest's wait for this same frame
        // node. Run 42476880237: 30 s was too short on loaded
        // runners — the frame landed after 30 s in all four
        // emulator runs, while the same frame passed
        // editorLaunches in the same run's second attempt, so the
        // render lands late rather than never. Budget is 60 s.
        // Run 42576011373: 60 s was too short in the retry
        // attempt (the first attempt passed the same test in the
        // same run, on a portrait pixel_2 emulator the landscape
        // change cannot affect) — the render lands late rather
        // than never. Budget is 120 s; waitUntil returns as soon
        // as the frame lands, so this costs nothing when the
        // runner is fast.
        // The assertions below are unchanged and still fail
        // if the frame never renders.
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onNodeWithContentDescription("Rendered composition frame").isDisplayed()
        }
        compose.onNodeWithContentDescription("Rendered composition frame").assertIsDisplayed()
        compose.onNodeWithText("Draft preview not rendered").assertDoesNotExist()
        compose.onNodeWithText("Import a supported .ecproj to see the actual composition here. Draft editing remains offline.").assertDoesNotExist()
    }
}
