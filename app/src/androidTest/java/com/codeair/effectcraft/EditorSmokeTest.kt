package com.codeair.effectcraft

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.codeair.effectcraft.ui.EditorScreen
import com.codeair.effectcraft.data.DraftProjectStore
import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.EcprojDocumentStore
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.ProjectKeyframeStore
import com.codeair.effectcraft.engine.NativeProjectBridge
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
    fun offlineMediaLibraryOpensAndExplainsEmptyState() {
        compose.onNodeWithContentDescription("Offline media library").performClick()
        compose.onNodeWithText("Offline media library").assertIsDisplayed()
        compose.onNodeWithText(
            "No media imported yet. Relink footage from a canonical project to copy it here for offline use."
        ).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }

    @Test
    fun savedOfflineProjectsCanBeOpenedWithoutNetwork() {
        // Isolate this catalog test from drafts left by other instrumentation cases.
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("projects").listFiles()?.forEach { it.delete() }
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        assertEditorStatus("Saved locally · Untitled Composition")

        // Saving the same project ID again must update its catalog entry, not
        // leave an older 4-layer snapshot beside the newer 5-layer revision.
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        assertEditorStatus("Saved locally · Untitled Composition")

        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(2, DraftProjectStore(appContext).listSavedProjects().size)

        val firstProjectId = DraftProjectStore(appContext).listSavedProjects()
            .first { it.layers.size == 5 }.id
        compose.onNodeWithContentDescription("Open offline project").performClick()
        compose.onNodeWithText("1080×1920 · 30 FPS · 5 layers").performClick()

        assertEditorStatus("Opened locally · Untitled Composition")
        // Timeline intentionally displays a bounded four-row viewport. Verify
        // the restored full model rather than requiring row five on screen.
        compose.onNodeWithContentDescription("Selected timeline layer: Background").assertExists()
        val restored = DraftProjectStore(appContext).load(firstProjectId)
        assertEquals(5, restored?.layers?.size)
        assertEquals("Shape 5", restored?.layers?.last()?.name)
    }

    @Test
    fun effectCraftEcprojxXmlRoundTripsTypedProjectValues() {
        val source = JSONObject()
            .put("schema", 3)
            .put("name", "XML < & round-trip")
            .put("items", org.json.JSONArray().put(
                JSONObject().put("id", 42).put("enabled", true).put("nullable", JSONObject.NULL)
            ))
            .toString()

        val xml = CanonicalProjectDocument.toXml(source)
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("""<EffectCraftProject encoding="json" version="1">"""))
        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))

        assertEquals(3, restored.getInt("schema"))
        assertEquals("XML < & round-trip", restored.getString("name"))
        val item = restored.getJSONArray("items").getJSONObject(0)
        assertEquals(42, item.getInt("id"))
        assertTrue(item.getBoolean("enabled"))
        assertEquals(JSONObject.NULL, item.get("nullable"))
    }

    @Test
    fun editorLaunchesWithOfflineCompositionWorkspace() {
        compose.onNodeWithText("EFFECTCRAFT").assertIsDisplayed()
        compose.onNodeWithText("OFFLINE").assertIsDisplayed()
        compose.onNodeWithText("COMPOSITION VIEWER", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Draft preview not rendered").assertIsDisplayed()
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
        // The inspector now has three text fields (layer name, trim in,
        // trim out): address the rename field by its description instead
        // of assuming it is the only editable field, and the rename Apply
        // by its description (the trim row has its own Apply button, so a
        // bare onNodeWithText("Apply") matches two nodes, not one).
        compose.onNodeWithTag("layer-name-field").performTextClearance()
        compose.onNodeWithTag("layer-name-field").performTextInput("Mobile title")
        compose.onNodeWithContentDescription("Apply layer rename").performClick()
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
    fun opacityKeyframesCanBeAuthoredAtPlayheadAndPersistPerProject() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Add opacity keyframe").performClick()
        assertEditorStatus("Opacity keyframe added · frame 0")
        compose.onNodeWithContentDescription("Timeline playhead")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(30f) }
        compose.onNodeWithContentDescription("Add opacity keyframe").performClick()
        assertEditorStatus("Opacity keyframe added · frame 30")
        compose.onNodeWithText("OPACITY · 2 KEYS").assertIsDisplayed()

        compose.onNodeWithContentDescription("Save draft").performClick()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = DraftProjectStore(appContext).listSavedProjects().maxByOrNull { it.updatedAtEpochMillis }!!
        val store = ProjectKeyframeStore(appContext.filesDir.resolve("keyframes"))
        val stored = store.load(saved.id).single { it.layerId == saved.layers.first().id && it.track.property == "opacity" }
        assertEquals(listOf(0, 30), stored.track.keyframes.map { it.frame })
        assertEquals(listOf(1f, 1f), stored.track.keyframes.map { it.value })

        compose.onNodeWithContentDescription("Remove opacity keyframe").performClick()
        assertEditorStatus("Opacity keyframe removed · frame 30")
        assertEquals(listOf(0), store.load(saved.id).single().track.keyframes.map { it.frame })
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
        compose.onNodeWithContentDescription("Move selected layer up").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Delete selected layer").performScrollTo().assertIsDisplayed().performClick()
        // The phone inspector is a ModalBottomSheet dialog window; its removal
        // is platform-async and outside Compose idle tracking, so wait for the
        // status to surface instead of asserting in the same frame. The
        // assertion itself is unchanged.
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Layer removed · unsaved").assertIsDisplayed() }.isSuccess
        }
        assertEditorStatus("Layer removed · unsaved")
        assertTrue("Removed layer should no longer be present", compose.onAllNodesWithText("Shape 4").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithContentDescription("Move selected layer up").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Delete selected layer").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Layer removed · unsaved").assertIsDisplayed() }.isSuccess
        }

        // Shape 4 was moved into slot 3. Deletion selects the surviving layer
        // now occupying that slot (Accent Shapes), not a stale deleted ID.
        compose.onNodeWithContentDescription("Selected timeline layer: Accent Shapes").assertExists()

        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Selected timeline layer: Shape 4").assertExists()
        compose.onNodeWithText("Redo").performClick()
        compose.onNodeWithContentDescription("Selected timeline layer: Accent Shapes").assertExists()
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
        // The timeline panel scrolls: with 48dp tracks the third layer
        // sits below the visible fold, so scroll it into view before
        // tapping it, exactly as a user would.
        compose.onNodeWithText("Accent Shapes").performScrollTo().performClick()
        compose.onNodeWithText("Props").performClick()
        // The inspector scrolls; the opacity row may sit below the fold.
        compose.onNodeWithTag("layer-opacity-value")
            .performScrollTo().assertIsDisplayed().assertTextContains("82%")
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
    @Test
    fun draftStoreRecoversNewerCompleteAtomicTempAfterProcessDeath() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = appContext.filesDir.resolve("projects")
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".efx-draft") || it.name.endsWith(".efx-draft.tmp") }
            ?.forEach { it.delete() }

        val store = DraftProjectStore(appContext)
        val project = ProjectDocument.starter().copy(name = "Committed baseline")
        val target = store.save(project)
        val interrupted = target.resolveSibling(target.name + ".tmp")
        val pending = JSONObject(target.readText(Charsets.UTF_8))
            .put("name", "Recovered revision")
            .put("updatedAtEpochMillis", JSONObject(target.readText(Charsets.UTF_8)).getLong("updatedAtEpochMillis") + 60_000L)
        interrupted.writeText(pending.toString(2), Charsets.UTF_8)

        val recovered = store.load(project.id)
        assertNotNull(recovered)
        assertEquals("Recovered revision", recovered?.name)
        assertTrue("A promoted atomic temp must no longer exist", !interrupted.exists())
        assertEquals("Recovered revision", JSONObject(target.readText(Charsets.UTF_8)).getString("name"))
    }

    @Test
    fun draftStoreDiscardsIncompleteAtomicTempAndPreservesCommittedProject() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = appContext.filesDir.resolve("projects")
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".efx-draft") || it.name.endsWith(".efx-draft.tmp") }
            ?.forEach { it.delete() }

        val store = DraftProjectStore(appContext)
        val project = ProjectDocument.starter().copy(name = "Last committed")
        val target = store.save(project)
        val interrupted = target.resolveSibling(target.name + ".tmp")
        interrupted.writeText("{ incomplete", Charsets.UTF_8)

        val loaded = store.load(project.id)
        assertEquals("Last committed", loaded?.name)
        assertTrue("Invalid temp must be discarded", !interrupted.exists())
        assertEquals(project.id, JSONObject(target.readText(Charsets.UTF_8)).getString("id"))
    }

}
