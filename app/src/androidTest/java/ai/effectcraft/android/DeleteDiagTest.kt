package ai.effectcraft.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

// TEMPORARY diagnostic only, owned by the user-approved delete-test fix task.
// DO NOT REMOVE until its CI failure message has been read and the delete
// status root cause is fixed. It deliberately fails with a count dump so the
// evidence appears in the CI test failure output. Remove before finishing.
class DeleteDiagTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deleteDiagDumpsTreeAfterRemove() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("Move ↑", substring = true).performClick()
        compose.onNodeWithText("Remove layer", substring = true).performClick()
        compose.waitForIdle()
        fun countText(text: String, substring: Boolean = false): Int =
            runCatching { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size }.getOrDefault(-1)
        fun countDesc(text: String, substring: Boolean = false): Int =
            runCatching { compose.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().size }.getOrDefault(-1)
        val report = "DELETE_DIAG exactLayerRemoved=" + countText("Layer removed · unsaved") +
            " substringLayerRemoved=" + countText("Layer removed", true) +
            " substringUnsaved=" + countText("unsaved", true) +
            " unsavedChanges=" + countText("Unsaved changes") +
            " statusDesc=" + countDesc("Editor status: Layer removed · unsaved") +
            " statusDescAny=" + countDesc("Editor status", true) +
            " shape4=" + countText("Shape 4") +
            " properties=" + countText("PROPERTIES") +
            " removeLayerStillPresent=" + countText("Remove layer", true) +
            " timeline=" + countText("TIMELINE")
        println(report)
        fail(report)
    }
}
