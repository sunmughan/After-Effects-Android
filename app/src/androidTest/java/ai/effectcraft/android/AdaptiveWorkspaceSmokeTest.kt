package ai.effectcraft.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.ui.EditorScreen
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
                    onNewProject = {},
                    onAddLayer = {},
                    onSelectLayer = {},
                    onDeleteLayer = {},
                    onMoveLayer = {},
                    onVisibilityChange = {},
                    onRenameLayer = {},
                    onOpacityChange = {},
                    onScrub = {},
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
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onRelinkCanonicalAsset = {},
                    onRenderCanonicalFrame = { _, _ -> null }
                )
            }
        }
        compose.onNodeWithText("COMPOSITION VIEWER", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PROPERTIES").assertIsDisplayed()
    }
}
