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
// status root cause is fixed. It deliberately fails with count dumps so the
// evidence appears in the CI test failure output. Remove before finishing.
class DeleteDiagTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun countText(text: String, substring: Boolean = false): Int =
        runCatching { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size }.getOrDefault(-1)

    private fun countDesc(text: String, substring: Boolean = false): Int =
        runCatching { compose.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().size }.getOrDefault(-1)

    private fun dump(tag: String): String =
        tag + " removed=" + countText("Layer removed · unsaved") +
            " unsavedChanges=" + countText("Unsaved changes") +
            " bg=" + countText("Background") +
            " title=" + countText("Title / Main") +
            " accent=" + countText("Accent Shapes") +
            " shape4=" + countText("Shape 4") +
            " selShape4=" + countDesc("Selected timeline layer: Shape 4") +
            " selAccent=" + countDesc("Selected timeline layer: Accent Shapes") +
            " selTitle=" + countDesc("Selected timeline layer: Title / Main") +
            " selBg=" + countDesc("Selected timeline layer: Background")

    @Test
    fun deleteDiagWithoutMove() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("Remove layer", substring = true).performClick()
        compose.waitForIdle()
        fail(dump("NO_MOVE"))
    }

    @Test
    fun deleteDiagWithMoveThenUndo() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("Move ↑", substring = true).performClick()
        compose.onNodeWithText("Remove layer", substring = true).performClick()
        compose.waitForIdle()
        val afterDelete = dump("MOVE_DELETE")
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        fail(afterDelete + " | " + dump("AFTER_UNDO"))
    }
}
