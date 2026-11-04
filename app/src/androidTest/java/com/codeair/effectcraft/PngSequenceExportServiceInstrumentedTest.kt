package com.codeair.effectcraft

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.EcprojDocumentStore
import com.codeair.effectcraft.data.ForegroundPngSequenceExport
import com.codeair.effectcraft.data.PngSequenceZipValidator
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.export.PngSequenceExportService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Foreground-service proof on the emulator, PNG sequence only.
 *
 * The service renders real frames through the native session from the
 * canonical fixture (the same fixture NativeSessionInstrumentedTest
 * uses), stages and validates the ZIP, and copies it to a MediaStore
 * Downloads entry, which is the same openOutputStream route a SAF
 * CreateDocument destination uses. No codec or audio path is involved.
 * Progress during export is proven deterministically by the JVM
 * runner tests; here the service must complete a real export, show its
 * foreground notification while a long export runs, and cancel cleanly.
 */
class PngSequenceExportServiceInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun appContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cacheSnapshot(context: Context): Set<String> =
        context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()

    private fun storeFile(context: Context): File =
        File(File(context.filesDir, "canonical-projects"), "latest.ecproj")

    private fun saveFixture(context: Context): ByteArray? {
        val file = storeFile(context)
        val prior = file.takeIf { it.isFile }?.readBytes()
        EcprojDocumentStore(context).save(NativeSessionInstrumentedTest.FIXTURE_JSON)
        return prior
    }

    private fun restoreStore(context: Context, prior: ByteArray?) {
        val file = storeFile(context)
        if (prior == null) file.delete() else file.writeBytes(prior)
    }

    private fun createDestination(context: Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "effectcraft-service-test-${System.nanoTime()}.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/EffectCraftTest")
            }
        }
        return context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: error("MediaStore refused the test downloads entry")
    }

    private fun deleteDestination(context: Context, destination: Uri) {
        runCatching { context.contentResolver.delete(destination, null, null) }
    }

    private fun waitForFinished(
        timeoutMillis: Long = 180_000L,
        matches: (PngSequenceExportService.Status) -> Boolean
    ): PngSequenceExportService.Status {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val current = PngSequenceExportService.status
            if (current != null && current.finished && matches(current)) return current
            Thread.sleep(250L)
        }
        fail("Foreground export did not reach the expected finished status within ${timeoutMillis}ms; " +
            "last status was ${PngSequenceExportService.status}")
        throw AssertionError("unreachable")
    }

    @Test
    fun foregroundServiceExportsRealPngSequenceToSafStyleDestination() {
        val context = appContext()
        val before = cacheSnapshot(context)
        val prior = saveFixture(context)
        val settings = RenderQueueSettings(320, 180, 30, 0, 1)
        val plan = RenderQueuePlan.create(settings, 100)
        val destination = createDestination(context)
        try {
            context.startForegroundService(
                PngSequenceExportService.startIntent(
                    context, destination,
                    NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                    settings, 100
                )
            )
            val finished = waitForFinished { it.succeeded }
            assertEquals(2, finished.completedFrames)
            assertEquals(100, finished.percent)
            val copyBack = File(context.cacheDir, "effectcraft-service-copy-${System.nanoTime()}.zip")
            try {
                context.contentResolver.openInputStream(destination)?.use { input ->
                    copyBack.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot read back the copied document")
                val validated = PngSequenceZipValidator.validate(copyBack, plan)
                assertEquals(2, validated.framesValidated)
            } finally {
                copyBack.delete()
            }
            val leftovers = cacheSnapshot(context) - before
            assertTrue("Service must leave no new staging file or marker, found $leftovers",
                leftovers.none { it.startsWith("effectcraft-sequence-") })
        } finally {
            deleteDestination(context, destination)
            restoreStore(context, prior)
        }
    }

    @Test
    fun foregroundServiceShowsNotificationAndCancelLeavesNoFinalOutput() {
        val context = appContext()
        val before = cacheSnapshot(context)
        val prior = saveFixture(context)
        val settings = RenderQueueSettings(320, 180, 30, 0, 599)
        val destination = createDestination(context)
        try {
            context.startForegroundService(
                PngSequenceExportService.startIntent(
                    context, destination,
                    NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                    settings, 1000
                )
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var notificationSeen = false
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline && !notificationSeen) {
                notificationSeen = manager.activeNotifications.any {
                    it.id == PngSequenceExportService.NOTIFICATION_ID
                }
                if (!notificationSeen) Thread.sleep(250L)
            }
            assertTrue("Foreground export notification must be visible while exporting", notificationSeen)
            context.startService(PngSequenceExportService.cancelIntent(context))
            val finished = waitForFinished { it.phase == ForegroundPngSequenceExport.Phase.CANCELLED }
            assertFalse(finished.succeeded)
            val readBack = runCatching { context.contentResolver.openInputStream(destination) }.getOrNull()
            assertTrue("Cancelled export must leave no readable final output", readBack == null)
            readBack?.close()
            val leftovers = cacheSnapshot(context) - before
            assertTrue("Cancelled service export must leave no new staging file or marker, found $leftovers",
                leftovers.none { it.startsWith("effectcraft-sequence-") })
        } finally {
            deleteDestination(context, destination)
            restoreStore(context, prior)
        }
    }
}
