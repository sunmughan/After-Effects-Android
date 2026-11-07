package com.codeair.effectcraft

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.DraftProjectStore
import com.codeair.effectcraft.data.OfflineAssetStore
import com.codeair.effectcraft.data.PngSequenceZipExporter
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.push.FirebaseInit
import com.google.firebase.FirebaseApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * M6-L7 offline acceptance: the editor, local media, local save and load,
 * and local export and import must all work with no account signed in and
 * no network.
 *
 * How offline conditions are enforced at the test level (stated plainly):
 * no step in any test below opens a socket, a URL, or an HTTP client.
 * Every input is a byte array or a file under the app-private filesDir,
 * every output is a file under filesDir, and no account object is ever
 * created. The account gate is checked directly: this build ships no
 * google-services.json, so FirebaseApp.getApps must be empty and
 * FirebaseInit.ensureInitialized must return false. The emulator may
 * still have a network interface up; these tests do not depend on it
 * being down, because no code path under test can reach it. That is a
 * stub-at-test-level offline proof, not a network-disabled device proof,
 * and it is recorded as such in PHASES.md if this line is flipped.
 */
class OfflineAcceptanceInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mediaStore() = OfflineAssetStore(File(appContext().filesDir, "media-library"))

    private fun assertEditorStatus(message: String) {
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithContentDescription("Editor status: $message").assertExists()
    }

    private fun renderPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    @Test
    fun offlineEditorOpensEditsSavesAndReloadsLocalProjectWithLocalMedia() {
        val context = appContext()
        // Isolate the open dialog from drafts left by other instrumentation cases.
        context.filesDir.resolve("projects").listFiles()?.forEach { it.delete() }

        // Stage local media through the same app-private store the editor reads.
        val fixture = "EffectCraft offline media fixture".toByteArray(Charsets.UTF_8)
        val asset = mediaStore().import(ByteArrayInputStream(fixture), "offline-clip.png", "image/png")
        assertTrue("Staged local media must verify against its index record", mediaStore().verify(asset.sha256))

        // The editor snapshots the library at composition, so recreate the
        // activity to pick up the newly staged asset, then prove the editor
        // itself lists it.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Offline media library").performClick()
        compose.onNodeWithText("offline-clip.png").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        // Edit in the editor, save locally, then reopen from the saved catalog.
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Layer").performClick()
        assertEditorStatus("Unsaved changes")
        compose.onNodeWithContentDescription("Save draft").performClick()
        assertEditorStatus("Saved locally · Untitled Composition")

        val saved = DraftProjectStore(context).listSavedProjects()
        assertEquals(1, saved.size)
        assertEquals("Shape 4", saved.first().layers.last().name)

        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Open offline project").performClick()
        compose.onNodeWithText("1080×1920 · 30 FPS · 4 layers").performClick()
        assertEditorStatus("Opened locally · Untitled Composition")
        compose.onNodeWithText("Shape 4").assertExists()

        // Reload from fresh store instances: the edit and the media survive.
        val reloaded = DraftProjectStore(context).load(saved.first().id)
        assertNotNull(reloaded)
        assertEquals("Shape 4", reloaded?.layers?.last()?.name)
        val freshStore = mediaStore()
        assertTrue("Local media must still verify after editor save and reload", freshStore.verify(asset.sha256))
        assertArrayEquals(fixture, freshStore.open(asset.sha256).readBytes())
    }

    @Test
    fun localMediaExportImportRoundtripNeedsNoNetwork() {
        val context = appContext()
        val store = mediaStore()
        val fixture = "EffectCraft offline roundtrip fixture".toByteArray(Charsets.UTF_8)
        val imported = store.import(ByteArrayInputStream(fixture), "offline-roundtrip.png", "image/png")
        assertArrayEquals(fixture, store.open(imported.sha256).readBytes())

        // Export a local PNG sequence ZIP to an app-private file. Frames are
        // rendered by a local callback, never fetched from anywhere.
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(width = 16, height = 16, frameRate = 30, startFrame = 0, endFrame = 1, outputFormat = RenderOutputFormat.PNG_SEQUENCE_ZIP)
        )
        val rendered = mutableMapOf<Int, ByteArray>()
        val exportDir = File(context.filesDir, "offline-acceptance").apply { mkdirs() }
        val zipFile = File(exportDir, "offline-export.zip")
        zipFile.outputStream().buffered().use { output ->
            val result = PngSequenceZipExporter.export(plan, output, renderPng = { frame ->
                renderPng(16, 16).also { rendered[frame] = it }
            })
            assertEquals(2, result.framesWritten)
        }
        assertTrue("Export output must live under app-private filesDir", zipFile.absolutePath.startsWith(context.filesDir.absolutePath))

        // Read the ZIP back and check every entry against what was rendered.
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        assertTrue("ZIP manifest must describe a PNG sequence", String(entries.getValue("sequence.json"), Charsets.UTF_8).contains("png-sequence"))
        assertArrayEquals(rendered.getValue(0), entries.getValue("frame-000000.png"))
        assertArrayEquals(rendered.getValue(1), entries.getValue("frame-000001.png"))

        // Import the exported ZIP back into the local library and verify bytes.
        val reimported = store.import(ByteArrayInputStream(zipFile.readBytes()), "offline-export.zip", "application/zip")
        assertTrue(store.verify(reimported.sha256))
        assertArrayEquals(zipFile.readBytes(), store.open(reimported.sha256).readBytes())

        store.delete(imported.sha256)
        store.delete(reimported.sha256)
    }

    @Test
    fun offlineFlowRequiresNoAccountSignInOrNetworkCall() {
        val context = appContext()
        // No account exists in this build and no sign-in is attempted.
        assertTrue("No Firebase app may exist for offline acceptance", FirebaseApp.getApps(context).isEmpty())
        assertFalse("Firebase init must report uninitialized with no config", FirebaseInit.ensureInitialized(context))
        assertTrue("Firebase init must not create an app as a side effect", FirebaseApp.getApps(context).isEmpty())

        // Every required step of the offline flow, driven with local-only
        // inputs and app-private outputs, and no account object anywhere.
        val draftStore = DraftProjectStore(context)
        val project = ProjectDocument.starter().copy(name = "Offline gate check")
        draftStore.save(project)
        val reloaded = draftStore.load(project.id)
        assertNotNull(reloaded)
        assertEquals("Offline gate check", reloaded?.name)

        val store = mediaStore()
        val fixture = "EffectCraft offline gate fixture".toByteArray(Charsets.UTF_8)
        val asset = store.import(ByteArrayInputStream(fixture), "offline-gate.png", "image/png")
        assertTrue(store.verify(asset.sha256))

        val plan = RenderQueuePlan.create(
            RenderQueueSettings(width = 16, height = 16, frameRate = 30, startFrame = 0, endFrame = 0, outputFormat = RenderOutputFormat.PNG_SEQUENCE_ZIP)
        )
        val exportDir = File(context.filesDir, "offline-acceptance").apply { mkdirs() }
        val zipFile = File(exportDir, "offline-gate.zip")
        zipFile.outputStream().buffered().use { output ->
            val result = PngSequenceZipExporter.export(plan, output, renderPng = { renderPng(16, 16) })
            assertEquals(1, result.framesWritten)
        }
        assertTrue("Exported ZIP must exist locally", zipFile.isFile && zipFile.length() > 0)
        assertTrue("No Firebase app may exist after the full offline flow", FirebaseApp.getApps(context).isEmpty())

        store.delete(asset.sha256)
    }
}
