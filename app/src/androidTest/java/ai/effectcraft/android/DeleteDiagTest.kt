package ai.effectcraft.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import org.junit.Rule
import org.junit.Test

// TEMPORARY diagnostic only, to be removed after CI log inspection.
class DeleteDiagTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deleteDiagDumpsTreeAfterRemove() {
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithText("Props").performClick()
        compose.onNodeWithText("Move \u2191", substring = true).performClick()
        compose.onNodeWithText("Remove layer", substring = true).performClick()
        compose.onRoot(useUnmergedTree = true).printToLog("DELETE_DIAG")
        val unsaved = compose.onAllNodesWithText("unsaved", substring = true).fetchSemanticsNodes()
        println("DELETE_DIAG unsaved nodes: " + unsaved.size)
        val removed = compose.onAllNodesWithText("Layer removed", substring = true).fetchSemanticsNodes()
        println("DELETE_DIAG removed nodes: " + removed.size)
        val shape4 = compose.onAllNodesWithText("Shape 4").fetchSemanticsNodes()
        println("DELETE_DIAG shape4 nodes: " + shape4.size)
        val props = compose.onAllNodesWithText("PROPERTIES").fetchSemanticsNodes()
        println("DELETE_DIAG properties nodes: " + props.size)
    }
}
