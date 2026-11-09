package com.codeair.effectcraft

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.DraftProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Lifecycle and rotation half of M6-L4: an unsaved edit must survive
 * activity recreation (the rotation path) through the autosave journal,
 * and the restored document must keep its canonical layer data intact.
 * Undo history is intentionally in-memory and is not expected to survive.
 */
class EditorLifecycleInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForJournalFile() {
        val journalDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "recovery-journal")
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (journalDir.listFiles()?.any { it.name.endsWith(".journal") } == true) return
            Thread.sleep(100L)
        }
        assertTrue("Autosave journal was not written after the unsaved edit", false)
    }

    private fun assertEditorStatus(message: String) {
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithContentDescription("Editor status: $message").assertExists()
    }

    /**
     * Batch 2 (P11): the track list is a LazyColumn, so a row below the
     * fold is not composed until it is scrolled into view — exactly as a
     * user must scroll to see it. Scroll before asserting an off-screen
     * layer, and assert the visible rows first: scrolling away uncomposes
     * them.
     */
    private fun scrollTimelineToLayer(index: Int) {
        compose.onNodeWithTag("timeline-track-list").performScrollToIndex(index)
        compose.waitForIdle()
    }

    @Test
    fun unsavedEditSurvivesActivityRecreationWithCanonicalDataIntact() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Add layer").performClick()
        assertEditorStatus("Unsaved changes")
        waitForJournalFile()

        // Rotation path: the activity is destroyed and recreated in place.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        assertEditorStatus("Recovered unsaved changes")
        compose.onNodeWithText("Background").assertExists()
        scrollTimelineToLayer(3)
        compose.onNodeWithText("Shape 4").assertExists()

        compose.onNodeWithContentDescription("Save draft").performClick()
        assertEditorStatus("Saved locally · Untitled Composition")
        val restored = DraftProjectStore(InstrumentationRegistry.getInstrumentation().targetContext).loadLatest()
        assertEquals(4, restored?.layers?.size)
        assertEquals("Background", restored?.layers?.get(0)?.name)
        assertEquals("Title / Main", restored?.layers?.get(1)?.name)
        assertEquals("Accent Shapes", restored?.layers?.get(2)?.name)
        assertEquals("Shape 4", restored?.layers?.get(3)?.name)
    }

    @Test
    fun savedEditDoesNotReportRecoveryAfterRecreation() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Add layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        assertEditorStatus("Saved locally · Untitled Composition")

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        // The explicit save discarded the journal, so recreation must load
        // the committed draft normally, not as a recovery.
        scrollTimelineToLayer(3)
        compose.onNodeWithText("Shape 4").assertExists()
        compose.onNodeWithText("Recovered unsaved changes").assertDoesNotExist()
    }
}
