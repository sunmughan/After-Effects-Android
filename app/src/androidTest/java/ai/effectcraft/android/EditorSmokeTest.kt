package ai.effectcraft.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Unsaved changes").assertIsDisplayed()
    }

    @Test
    fun layerMutationCanBeUndoneAndRedone() {
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo · unsaved").assertIsDisplayed()
        compose.onNodeWithText("Redo").performClick()
        compose.onNodeWithText("Redo · unsaved").assertIsDisplayed()
    }

    @Test
    fun canonicalEcprojCachePreservesSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EcprojDocumentStore(context)
        val source = "{\\\"schema\\\":1,\\\"items\\\":{}}"

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
