package ai.effectcraft.android

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

    @Test
    fun rotationKeepsSavedEditorStateWithoutDataLoss() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()

        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected timeline layer: Background").assertExists()

        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitForIdle()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected timeline layer: Background").assertExists()
    }
}
