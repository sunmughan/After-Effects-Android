package com.codeair.effectcraft

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Batch 3: the Import dialog through the real app. The header's
 * labelled Import button must open a dialog with three honest
 * options — including that direct .aep import is coming in a later
 * update (the ExtendScript export is an interim route, not the
 * solution) — and the After Effects export steps.
 */
class ImportProjectDialogInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun importButtonOpensDialogWithThreeHonestOptions() {
        compose.onNodeWithContentDescription("Import project").performClick()
        compose.onNodeWithText("EffectCraft project").assertIsDisplayed()
        compose.onNodeWithText("Project bundle (.zip)").assertIsDisplayed()
        compose.onNodeWithText("After Effects export (.zip)").assertIsDisplayed()
        compose.onNodeWithText("Direct .aep import is coming in a later update.", substring = true)
            .assertIsDisplayed()
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
