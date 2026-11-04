package ai.effectcraft.android

import ai.effectcraft.android.data.DraftProjectStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Offline acceptance: the full open/edit/save/reopen flow with no network
 * and no account. The manifest deliberately requests no network or account
 * permission, and this flow touches only app-private files, so it must work
 * with radios off and no signed-in account anywhere.
 */
class OfflineAcceptanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appRequestsNoNetworkOrAccountPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // GET_PERMISSIONS is required: with flags 0, requestedPermissions
        // comes back null and this test would pass vacuously.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertFalse("Offline app must not request network access", requested.any { it.contains("INTERNET") })
        assertFalse("Offline app must not request account access", requested.any { it.contains("ACCOUNT") })
        assertFalse("Offline app must not request credentials", requested.any { it.contains("CREDENTIAL") })
    }

    @Test
    fun fullOpenEditSaveReopenFlowWorksOfflineWithNoAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "projects").listFiles()?.forEach { it.delete() }

        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        compose.onNodeWithContentDescription("Save draft").performClick()
        compose.onNodeWithText("Saved locally · Untitled Composition").assertIsDisplayed()

        // A fresh store instance (the new-process view) proves the save
        // reached disk; nothing here touched the network or an account.
        val saved = DraftProjectStore(context).listSavedProjects()
        assertEquals(1, saved.size)
        assertEquals(4, saved.single().layers.size)

        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Open offline project").performClick()
        compose.onNodeWithText("1080×1920 · 30 FPS · 4 layers").performClick()
        compose.onNodeWithText("Opened locally · Untitled Composition").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected timeline layer: Background").assertExists()

        val reopened = DraftProjectStore(context).load(saved.single().id)
        assertNotNull(reopened)
        assertEquals(4, reopened!!.layers.size)
        assertEquals("Shape 4", reopened.layers.last().name)
    }
}
