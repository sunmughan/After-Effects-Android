package ai.effectcraft.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * M1 device-level shell smoke test. Runs on an Android emulator and verifies
 * the native Compose workspace actually launches (not just that it compiles).
 */
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
}
