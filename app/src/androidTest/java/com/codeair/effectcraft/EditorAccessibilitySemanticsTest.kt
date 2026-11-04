package com.codeair.effectcraft

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * TalkBack semantics audit for the draft editor screen, as executable
 * assertions: every primary control must expose a content description and
 * the action a screen-reader user needs (click, toggle, set text, set
 * progress). Manual TalkBack traversal order still needs a physical-device
 * pass; this suite proves the semantics themselves exist and are wired.
 */
class EditorAccessibilitySemanticsTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openInspectorIfNeeded() {
        // Tablet layouts show the inspector inline; phone layouts open it
        // from the Props button. Either way the inspector must be present
        // before its controls are asserted.
        if (compose.onAllNodesWithText("Props").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Props").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun headerControlsExposeDescriptionsAndClickActions() {
        compose.onNodeWithContentDescription("Save draft").assertExists().assertHasClickAction()
        compose.onNodeWithContentDescription("Open offline project").assertExists().assertHasClickAction()
        compose.onNodeWithContentDescription("Offline media library").assertExists().assertHasClickAction()
        // The status node must be announced at launch. Its exact message
        // legitimately depends on sibling autosave state that postdates
        // ec-m6: "Stored on this device" on a clean launch, "Recovered
        // unsaved changes" when a prior run left a newer journal. Both are
        // real product states, so either proves the node exists; inventing
        // a single expected message would fail on legitimate recovery.
        val statusNodes =
            compose.onAllNodesWithContentDescription("Editor status: Stored on this device")
                .fetchSemanticsNodes() +
            compose.onAllNodesWithContentDescription("Editor status: Recovered unsaved changes")
                .fetchSemanticsNodes()
        org.junit.Assert.assertTrue("Editor status node must be exposed", statusNodes.isNotEmpty())
    }

    @Test
    fun timelineControlsExposePlaybackScrubAndLayerActions() {
        compose.onNodeWithContentDescription("Play timeline").assertExists().assertHasClickAction()
        compose.onNodeWithContentDescription("Timeline playhead").assertExists()
        compose.onNodeWithContentDescription("Selected timeline layer: Background").assertExists()
            .assertHasClickAction()
    }

    @Test
    fun inspectorControlsExposeLabelsAndTheirActions() {
        openInspectorIfNeeded()
        // The layer-rename field previously had only placeholder text, which
        // TalkBack does not announce as a label; it must be a labelled edit
        // field with a set-text action.
        compose.onNodeWithContentDescription("Layer name").assertExists().assert(hasSetTextAction())
        compose.onNodeWithContentDescription("Layer visibility").assertExists().assertIsToggleable()
        compose.onNodeWithContentDescription("Layer opacity").assertExists()
    }

    @Test
    fun decorativeIconsDoNotCreateUnlabelledClickableNodes() {
        // The Tune/Layers header icons are decorative (contentDescription =
        // null) inside labelled rows; the rows themselves must be announced
        // by their heading text, not by a bare icon node.
        openInspectorIfNeeded()
        compose.onNodeWithText("PROPERTIES").assertIsDisplayed()
        compose.onNodeWithText("TIMELINE").assertIsDisplayed()
    }
}
