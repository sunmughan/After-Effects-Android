package com.codeair.effectcraft

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.ui.OnboardingStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First-run onboarding: the welcome dialog shows on a fresh first
 * launch, dismissing it sets the persisted flag, and a fresh launch
 * after that never shows it again.
 *
 * This class deliberately has no dismiss @Before (unlike the other
 * MainActivity test classes): it manages the flag itself, resetting
 * it and recreating the activity so the test is independent of
 * whatever earlier tests did.
 */
class OnboardingInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcomeDialogShowsOnFirstLaunchAndNotAgain() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a fresh first launch, whatever earlier tests did.
        OnboardingStore.reset(appContext)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Welcome to EffectCraft").assertIsDisplayed()
        compose.onNodeWithText("1 · Import a project", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Welcome to EffectCraft").assertDoesNotExist()
        // A fresh launch after dismissing never shows it again.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Welcome to EffectCraft").assertDoesNotExist()
    }

    @Test
    fun onboardingStoreRoundTripsTheSeenFlag() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        OnboardingStore.reset(appContext)
        assertFalse(OnboardingStore.isSeen(appContext))
        OnboardingStore.markSeen(appContext)
        assertTrue(OnboardingStore.isSeen(appContext))
        OnboardingStore.reset(appContext)
        assertFalse(OnboardingStore.isSeen(appContext))
    }
}
