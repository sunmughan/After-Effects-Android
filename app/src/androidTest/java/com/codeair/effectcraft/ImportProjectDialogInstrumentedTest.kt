package com.codeair.effectcraft

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Before
import org.junit.Test

/**
 * Commit 1 (.aep import v1): the Import dialog through the real app. The
 * header's labelled Import button must open a dialog with four options —
 * including a real direct .aep/.aepx import, behind a loss gate — and the
 * After Effects export steps. The old "Direct .aep import is coming in a
 * later update." note is gone: the import exists now.
 */
class ImportProjectDialogInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    /**
     * The first-run welcome dialog shows on a fresh install only.
     * Dismiss it so the editor underneath is reachable; on later
     * tests in the run the flag is already set and this is a no-op.
     */
    @Before
    fun dismissWelcomeDialogIfShown() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Get started").performClick()
        }
    }


    @Test
    fun importButtonOpensDialogWithFourOptionsIncludingAep() {
        compose.onNodeWithContentDescription("Import project").performClick()
        compose.onNodeWithText("EffectCraft project").assertIsDisplayed()
        compose.onNodeWithText("After Effects project (.aep/.aepx)").assertIsDisplayed()
        compose.onNodeWithText("Project bundle (.zip)").assertIsDisplayed()
        compose.onNodeWithText("After Effects export (.zip)").assertIsDisplayed()
        compose.onNodeWithText("Direct .aep import is coming in a later update.", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("EffectCraft project").assertDoesNotExist()
    }

    @Test
    fun aeStepsExpandFromTheDialog() {
        compose.onNodeWithContentDescription("Import project").performClick()
        compose.onNodeWithText("How to export from After Effects").performClick()
        compose.onNodeWithText("Zip that folder.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }
}
