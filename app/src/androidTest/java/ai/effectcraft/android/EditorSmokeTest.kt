package ai.effectcraft.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.effectcraft.android.ui.EditorScreen
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.EcprojDocumentStore
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.engine.NativeProjectBridge
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun assertEditorStatus(message: String) {
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithContentDescription("Editor status: $message").assertExists()
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
        assertEditorStatus("Unsaved changes")
    }

    @Test
    fun layerMutationCanBeUndoneAndRedone() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Undo").performClick()
        assertEditorStatus("Undo · unsaved")
        compose.onNodeWithText("Redo").performClick()
        assertEditorStatus("Redo · unsaved")
    }


    @Test
    fun inspectorRenamesSelectedLayer() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Mobile title")
        compose.onNodeWithText("Apply").performClick()
        assertTrue("Renamed layer should appear in timeline and inspector", compose.onAllNodesWithText("Mobile title").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun inspectorOpacityAndVisibilityControlsUpdateSelectedLayerAndUndoRedo() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Props").performClick()
        val visibility = compose.onNodeWithContentDescription("Layer visibility")
        visibility.assertIsOn()
        visibility.performClick()
        visibility.assertIsOff()

        val opacity = compose.onNodeWithContentDescription("Layer opacity")
        opacity.performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.5f) }
        assertEquals(0.5f, opacity.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        compose.onNodeWithText("50%").assertIsDisplayed()

        // Each property mutation is a separate undo snapshot; redo restores the exact values.
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1f, opacity.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        compose.onNodeWithText("Undo").performClick()
        visibility.assertIsOn()
        compose.onNodeWithText("Redo").performClick()
        visibility.assertIsOff()
        compose.onNodeWithText("Redo").performClick()
        assertEquals(0.5f, opacity.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
    }

    @Test
    fun timelinePlayheadScrubsAndTransportToggles() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Timeline playhead")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(60f) }
        compose.onNodeWithText("00:00:02:00").assertIsDisplayed()

        val playhead = compose.onNodeWithContentDescription("Timeline playhead")
        compose.onNodeWithContentDescription("Play timeline").performClick()
        compose.onNodeWithContentDescription("Pause timeline").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 3_000) {
            playhead.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current > 60f
        }
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
        assertEditorStatus("Layer removed · unsaved")
        assertTrue("Removed layer should no longer be present", compose.onAllNodesWithText("Shape 4").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun compositionSettingsApplyAndParticipateInUndo() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Comp").performClick()
        compose.onNodeWithContentDescription("Composition frame rate")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(60f) }
        compose.onNodeWithText("Frame rate · 60 FPS").assertIsDisplayed()
        compose.onNodeWithText("Apply settings").performClick()
        assertEditorStatus("Composition updated · unsaved")

        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo · unsaved").assertIsDisplayed()

        // Reopen settings and verify the model value, not merely the status label.
        compose.onNodeWithText("Comp").performClick()
        assertEquals(
            30f,
            compose.onNodeWithContentDescription("Composition frame rate")
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,
            0.001f
        )
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
        assertEditorStatus("Saved locally · Untitled Composition")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = DraftProjectStore(context).loadLatest()
        assertNotNull(restored)
        assertEquals("Shape 4", restored?.layers?.last()?.name)
    }

    @Test
    fun nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator() {
        assertTrue("The emulator ABI must load libeffectcraft_android", NativeProjectBridge.isAvailable())
        val envelope = NativeProjectBridge.inspectEcproj("{}")
        assertNotNull("JNI parser call must return a JSON envelope", envelope)
        val result = JSONObject(envelope!!)
        assertTrue("Invalid input must be reported by the native parser", !result.optBoolean("valid"))
        assertTrue("Native parser must include a validation error", result.optString("error").isNotBlank())
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
