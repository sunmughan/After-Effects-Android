package com.codeair.effectcraft.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foreground-service export runner tests, PNG sequence only.
 *
 * The runner owns staging, validation, destination copy and rollback, and
 * the interruption marker. A process kill is simulated honestly: [begin]
 * creates the staging file and marker, the test writes partial bytes into
 * the staging file and backdates both files, and [finish] is never called,
 * which is exactly the on-disk state a kill leaves behind. No codec or
 * audio path is involved here.
 */
class ForegroundPngSequenceExportTest {
    private fun plan(start: Int = 0, end: Int = 2) =
        RenderQueuePlan.create(RenderQueueSettings(320, 320, 24, start, end, RenderOutputFormat.PNG_SEQUENCE_ZIP))

    private fun png(width: Int = 320, height: Int = 320): ByteArray =
        ByteArray(24).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                .copyInto(bytes, 0)
            byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(bytes, 12)
            ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        }

    private fun tempDir(): File = Files.createTempDirectory("foreground-png-export").toFile()

    private fun copyToFile(destination: File): (File, () -> Unit, (Long) -> Unit) -> Long =
        { staging, ensureActive, onBytesCopied ->
            var copied = 0L
            staging.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onBytesCopied(copied)
                    }
                }
            }
            copied
        }

    @Test
    fun interruptionMarkerIsCompleteAndPublishedBeforeRendering() {
        val dir = tempDir()
        val started = ForegroundPngSequenceExport.begin(plan(start = 4, end = 6), dir)
        try {
            assertTrue(started.markerFile.isFile)
            assertTrue(started.stagingFile.isFile)
            assertEquals(0L, started.stagingFile.length())
            val marker = org.json.JSONObject(started.markerFile.readText(Charsets.UTF_8))
            assertEquals("png-sequence", marker.getString("format"))
            assertEquals(320, marker.getInt("width"))
            assertEquals(320, marker.getInt("height"))
            assertEquals(24, marker.getInt("frameRate"))
            assertEquals(4, marker.getInt("startFrame"))
            assertEquals(6, marker.getInt("endFrame"))
            assertEquals(3, marker.getInt("frameCount"))
            assertTrue(marker.getLong("startedAtMillis") > 0L)
            assertTrue(started.markerFile.name.endsWith(ForegroundPngSequenceExport.MARKER_SUFFIX))
        } finally {
            started.stagingFile.delete()
            started.markerFile.delete()
            File(dir, started.markerFile.name + ".tmp").delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun destinationCopyProgressTracksBytesFromZeroToComplete() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        val progress = mutableListOf<ForegroundPngSequenceExport.Progress>()
        val result = ForegroundPngSequenceExport.run(
            plan = plan(),
            stagingDir = dir,
            renderPng = { png() },
            copyToDestination = { staging, ensureActive, onBytesCopied ->
                copyToFile(destination)(staging, ensureActive, onBytesCopied)
            },
            rollbackDestination = { destination.delete() },
            onProgress = { if (it.phase == ForegroundPngSequenceExport.Phase.COPYING) progress += it }
        )
        assertTrue(result.destinationBytes > 0L)
        assertEquals(0, progress.first().percent)
        assertEquals(100, progress.last().percent)
        assertEquals(progress.last().totalBytes, progress.last().completedBytes)
        assertTrue(progress.all { it.totalBytes > 0L && it.completedBytes in 0L..it.totalBytes })
        assertTrue("Copy percentage must never regress",
            progress.zipWithNext().all { (before, after) -> after.percent >= before.percent })
        assertEquals(progress.last().totalBytes, destination.length())
        destination.delete()
        dir.deleteRecursively()
    }

    @Test
    fun progressIsReportedDuringExportAndDestinationValidates() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        val progress = mutableListOf<ForegroundPngSequenceExport.Progress>()
        val result = ForegroundPngSequenceExport.run(
            plan = plan(),
            stagingDir = dir,
            renderPng = { png() },
            copyToDestination = copyToFile(destination),
            rollbackDestination = { destination.delete() },
            onProgress = { progress += it }
        )
        assertEquals(3, result.framesValidated)
        assertTrue(result.destinationBytes > 0L)
        assertTrue("Destination must be a complete validated ZIP", destination.isFile)
        val renderProgress = progress.filter { it.phase == ForegroundPngSequenceExport.Phase.RENDERING }
        assertEquals(listOf(1 to 33, 2 to 66, 3 to 100), renderProgress.map { it.completedFrames to it.percent })
        assertTrue(progress.any { it.phase == ForegroundPngSequenceExport.Phase.DONE })
        assertTrue("No staging file or marker may remain after success",
            dir.listFiles().orEmpty().none { it.name.startsWith(StaleSequenceExportCleanup.FILE_PREFIX) })
        ZipInputStream(destination.inputStream()).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                names += zip.nextEntry?.name ?: break
                zip.closeEntry()
            }
            assertEquals(listOf("sequence.json", "frame-000000.png", "frame-000001.png", "frame-000002.png"), names)
        }
    }

    @Test
    fun cancelStopsExportAndLeavesNoFinalOutput() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            ForegroundPngSequenceExport.run(
                plan = plan(),
                stagingDir = dir,
                renderPng = { png() },
                copyToDestination = copyToFile(destination),
                rollbackDestination = { destination.delete() },
                isCancelled = { cancelled },
                onProgress = { if (it.phase == ForegroundPngSequenceExport.Phase.RENDERING) cancelled = true }
            )
        }
        assertFalse("Cancelled export must leave no final output", destination.exists())
        assertTrue("Cancelled export must leave no staging file or marker",
            dir.listFiles().orEmpty().none { it.name.startsWith(StaleSequenceExportCleanup.FILE_PREFIX) })
    }

    @Test
    fun cancelDuringCopyRollsBackDestination() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        assertThrows(CancellationException::class.java) {
            ForegroundPngSequenceExport.run(
                plan = plan(),
                stagingDir = dir,
                renderPng = { png() },
                copyToDestination = { staging, _, _ ->
                    staging.inputStream().use { input ->
                        destination.outputStream().use { output ->
                            val first = ByteArray(64)
                            val read = input.read(first)
                            output.write(first, 0, read)
                        }
                    }
                    throw CancellationException("Copy cancelled mid-write")
                },
                rollbackDestination = { destination.delete() },
                onProgress = { }
            )
        }
        assertFalse("Partial destination must be rolled back", destination.exists())
    }

    @Test
    fun interruptedExportLeavesDetectablePartialStateAndRecoveryCleansIt() {
        val dir = tempDir()
        val started = ForegroundPngSequenceExport.begin(plan(), dir)
        assertTrue("Marker must exist while an export is in flight", started.markerFile.isFile)
        assertEquals(1, ForegroundPngSequenceExport.pendingInterruptions(dir))
        // Simulate the kill: partial bytes in staging, finish never called.
        started.stagingFile.outputStream().use { it.write(ByteArray(128)) }
        val old = System.currentTimeMillis() - StaleSequenceExportCleanup.DEFAULT_MAX_AGE_MILLIS - 1000L
        started.stagingFile.setLastModified(old)
        started.markerFile.setLastModified(old)
        val recovery = ForegroundPngSequenceExport.recover(dir, System.currentTimeMillis())
        assertEquals(1, recovery.cleaned)
        assertEquals(0, recovery.retained)
        assertFalse("Recovery must remove the partial staging file", started.stagingFile.exists())
        assertFalse("Recovery must remove the interruption marker", started.markerFile.exists())
        assertEquals(0, ForegroundPngSequenceExport.pendingInterruptions(dir))
    }

    @Test
    fun recoveryRetainsLiveExportMarkerAndRemovesOrphanMarker() {
        val dir = tempDir()
        val live = ForegroundPngSequenceExport.begin(plan(), dir)
        val orphanMarker = File(dir, "effectcraft-sequence-orphan.zip.exporting")
        orphanMarker.writeText("{\"frameCount\":3}", Charsets.UTF_8)
        orphanMarker.setLastModified(System.currentTimeMillis() - StaleSequenceExportCleanup.DEFAULT_MAX_AGE_MILLIS - 1000L)
        val recovery = ForegroundPngSequenceExport.recover(dir, System.currentTimeMillis())
        assertEquals(0, recovery.cleaned)
        assertEquals(1, recovery.retained)
        assertEquals(1, recovery.orphanMarkersRemoved)
        assertTrue("Live export marker must be retained", live.markerFile.isFile)
        assertTrue("Live staging file must be retained", live.stagingFile.isFile)
        assertFalse("Orphan marker with no staging file must be removed", orphanMarker.exists())
        live.stagingFile.delete()
        live.markerFile.delete()
    }

    @Test
    fun failedRenderLeavesNoFinalOutputAndNoMarker() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundPngSequenceExport.run(
                plan = plan(),
                stagingDir = dir,
                renderPng = { ByteArray(24) },
                copyToDestination = copyToFile(destination),
                rollbackDestination = { destination.delete() }
            )
        }
        assertFalse(destination.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(StaleSequenceExportCleanup.FILE_PREFIX) })
    }
}
