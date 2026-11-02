package ai.effectcraft.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.effectcraft.android.ui.EditorScreen
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.EcprojDocumentStore
import ai.effectcraft.android.data.ProjectDocument
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class EditorSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()


    @Test
    fun wideWindowKeepsInspectorAndPreviewSideBySide() {
        val project = ProjectDocument.starter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = 0,
                    isPlaying = false,
                    saveMessage = "Saved",
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onValidateEcproj = {},
                    onExportEcproj = {},
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

        compose.onNodeWithText("COMPOSITION VIEWER").assertIsDisplayed()
        compose.onNodeWithText("PROPERTIES").assertIsDisplayed()
    }

    @Test
    fun editorLaunchesWithOfflineCompositionWorkspace() {
        compose.onNodeWithText("EFFECTCRAFT").assertIsDisplayed()
        compose.onNodeWithText("OFFLINE").assertIsDisplayed()
        compose.onNodeWithText("COMPOSITION VIEWER").assertIsDisplayed()
        compose.onNodeWithText("TIMELINE").assertIsDisplayed()
    }

    @Test
    fun newCompositionActionRemainsAvailable() {
        compose.onNodeWithText("New").assertIsDisplayed().performClick()
        compose.onNodeWithText("OFFLINE").assertIsDisplayed()
    }

    @Test
    fun addLayerActionAddsTrackToTimeline() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Unsaved changes").assertIsDisplayed()
    }

    @Test
    fun layerMutationCanBeUndoneAndRedone() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo · unsaved").assertIsDisplayed()
        compose.onNodeWithText("Redo").performClick()
        compose.onNodeWithText("Redo · unsaved").assertIsDisplayed()
    }


    @Test
    fun inspectorRenamesSelectedLayer() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Mobile title")
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Mobile title").assertIsDisplayed()
    }

    @Test
    fun inspectorOpacityAndVisibilityControlsUpdateSelectedLayer() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithContentDescription("Layer visibility").assertIsOn()
        compose.onNodeWithContentDescription("Layer visibility").performClick()
        compose.onNodeWithContentDescription("Layer visibility").assertIsOff()

        compose.onNodeWithContentDescription("Layer opacity")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.5f) }
        compose.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun timelinePlayheadScrubsAndTransportToggles() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Timeline playhead")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(60f) }
        compose.onNodeWithText("00:00:02:00").assertIsDisplayed()

        compose.onNodeWithContentDescription("Play timeline").performClick()
        compose.onNodeWithContentDescription("Pause timeline").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause timeline").performClick()
        compose.onNodeWithContentDescription("Play timeline").assertIsDisplayed()
    }

    @Test
    fun inspectorCanReorderAndDeleteAddedLayer() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("Move ↑", substring = true).performClick()
        compose.onNodeWithText("Remove layer", substring = true).performClick()
        compose.onNodeWithText("Layer removed · unsaved").assertIsDisplayed()
        compose.onNodeWithText("Shape 4").assertDoesNotExist()
    }

    @Test
    fun compositionSettingsApplyAndParticipateInUndo() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Comp").performClick()
        compose.onNodeWithContentDescription("Composition frame rate")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(60f) }
        compose.onNodeWithText("Frame rate · 60 FPS").assertIsDisplayed()
        compose.onNodeWithText("Apply settings").performClick()
        compose.onNodeWithText("Composition updated · unsaved").assertIsDisplayed()

        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo · unsaved").assertIsDisplayed()
    }

    @Test
    fun timelineSelectionUpdatesInspectorProperties() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Accent Shapes").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("82%").assertIsDisplayed()
    }

    @Test
    fun editorSaveButtonPersistsTheCurrentOfflineLayerStack() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = DraftProjectStore(context).loadLatest()
        assertNotNull(restored)
        assertEquals("Shape 4", restored?.layers?.last()?.name)
    }

    @Test
    fun canonicalEcprojCachePreservesSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EcprojDocumentStore(context)
        val source = "{\"schema\":1,\"items\":{}}"

        store.save(source)
        assertEquals(source, store.load())
    }

    @Test
    fun draftStoreRoundTripsCompositionAndLayerValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DraftProjectStore(context)
        val source = ProjectDocument.starter().copy(
            name = "M1 Persistence Check",
            durationFrames = 240,
            layers = ProjectDocument.starter().layers.mapIndexed { index, layer ->
                if (index == 0) layer.copy(opacity = 0.42f, visible = false) else layer
            }
        )

        val saved = store.save(source)
        val restored = store.loadLatest()

        assertNotNull(saved)
        assertEquals("M1 Persistence Check", restored?.name)
        assertEquals(240, restored?.durationFrames)
        assertEquals(0.42f, restored?.layers?.first()?.opacity ?: -1f, 0.001f)
        assertEquals(false, restored?.layers?.first()?.visible)
        assertEquals(source.layers.size, restored?.layers?.size)
    }
}
