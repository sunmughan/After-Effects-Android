package com.codeair.effectcraft

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test

/**
 * Lifecycle/rotation: MainActivity declares
 * orientation|screenSize|smallestScreenSize in configChanges, so a rotation
 * (including a foldable posture change, which arrives as a screen-size
 * change) must not recreate the activity or lose editor state. This test
 * rotates the live activity both ways and proves the saved editor state is
 * still on screen, with no data loss.
 */
class EditorRotationLifecycleTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Batch 2 (P11): the track list is a LazyColumn, so the selected
     * fourth row is not composed until it is scrolled into view.
     */
    private fun scrollTimelineToLayer(index: Int) {
        compose.onNodeWithTag("timeline-track-list").performScrollToIndex(index)
        compose.waitForIdle()
    }

    @Test
    fun rotationKeepsSavedEditorStateWithoutDataLoss() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()

        // The Layer click above selects the NEW layer (EffectCraftApp's
        // onAddLayer sets selectedLayerId to the added layer), so after this
        // flow the selected row is "Shape 4", not Background — the test's
        // original Background expectation never matched the real app
        // (this test was never run green before integration). Asserting the
        // true post-add selection in both orientations proves rotation
        // preserves the actual editor state, not just that some row exists.
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()
        scrollTimelineToLayer(3)
        compose.onNodeWithContentDescription("Selected timeline layer: Shape 4").assertExists()

        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitForIdle()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()
        scrollTimelineToLayer(3)
        compose.onNodeWithContentDescription("Selected timeline layer: Shape 4").assertExists()
    }
}
