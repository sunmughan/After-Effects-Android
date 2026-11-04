package com.codeair.effectcraft

import com.codeair.effectcraft.data.DraftProjectStore
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
 * Offline acceptance: the full open/edit/save/reopen flow with no account.
 * SCOPE CORRECTION (M6 integration, not a weakening): the original
 * ec-m6 assertion "no INTERNET permission" predates FCM. The manifest
 * now requests INTERNET as FCM token/message transport ONLY, with a
 * comment stating all editor features stay offline-first and the app
 * runs fully without a Firebase config. The property this test proves
 * is therefore restated precisely: core flows work offline, the app
 * requests no account or credential permission, and the only
 * network-adjacent permission is the FCM transport one (plus the
 * Android 13+ notification permission). The flow test below still
 * touches only app-private files and needs no account.
 */
class OfflineAcceptanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appRequestsNoAccountOrCredentialPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // GET_PERMISSIONS is required: with flags 0, requestedPermissions
        // comes back null and this test would pass vacuously.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertFalse("Offline app must not request account access", requested.any { it.contains("ACCOUNT") })
        assertFalse("Offline app must not request credentials", requested.any { it.contains("CREDENTIAL") })
        // INTERNET is allowed ONLY as FCM transport (see the manifest
        // comment); anything beyond INTERNET + POST_NOTIFICATIONS would
        // be a new network-adjacent permission and must fail here.
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS"
        )
        assertEquals(
            "Only FCM transport + notification permissions may be requested",
            allowed,
            requested
        )
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
