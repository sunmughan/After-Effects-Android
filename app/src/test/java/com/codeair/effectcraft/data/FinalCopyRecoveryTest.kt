package com.codeair.effectcraft.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M5-L7 final SAF provider copy recovery tests, PNG sequence ZIP and
 * staged MP4, best effort only.
 *
 * A process kill is simulated honestly: a copy marker is published and
 * the finish call is never made, which is exactly the on-disk state a
 * kill mid-copy leaves behind, because the kill bypasses every finally
 * block. Recovery is cleanup plus restart from frame 0, never resume,
 * and never a claim that a provider-side partial was rolled back: this
 * process cannot delete bytes a provider already wrote, and it cannot
 * tell a completed destination from a partial one after death, so the
 * destination is reported as possibly partial and left for the user or
 * the provider app to delete. No codec, audio, or MP4 encoding code is
 * involved here; the MP4 leg covers the shared copy marker mechanism
 * and the startup recovery call site, not encoding.
 */
class FinalCopyRecoveryTest {
    private fun plan(start: Int = 0, end: Int = 2) =
        RenderQueuePlan.create(RenderQueueSettings(320, 320, 24, start, end))

    private fun png(width: Int = 320, height: Int = 320): ByteArray =
        ByteArray(24).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                .copyInto(bytes, 0)
            byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(bytes, 12)
            ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        }

    private fun tempDir(): File = Files.createTempDirectory("final-copy-recovery").toFile()

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

    /** Simulates a kill mid-copy: marker published, finish never called. */
    private fun simulateCopyKill(dir: File, stagingName: String, format: String): Pair<File, File> {
        val staging = File(dir, stagingName)
        staging.writeBytes(ByteArray(128))
        val marker = FinalCopyRecovery.beginCopy(staging, format)
        return staging to marker
    }

    @Test
    fun pngSequenceKillDuringFinalCopyLeavesDetectablePartialState() {
        val dir = tempDir()
        try {
            val (staging, marker) = simulateCopyKill(
                dir, "effectcraft-sequence-1.zip", FinalCopyRecovery.FORMAT_PNG_SEQUENCE
            )
            assertTrue(marker.isFile)
            assertTrue(staging.isFile)
            assertEquals(1, FinalCopyRecovery.pendingCopyInterruptions(dir))
            val parsed = org.json.JSONObject(marker.readText(Charsets.UTF_8))
            assertEquals("png-sequence", parsed.getString("format"))
            assertEquals("copying", parsed.getString("phase"))
            assertEquals(staging.name, parsed.getString("stagedName"))
            assertTrue(parsed.getLong("startedAtMillis") > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stagedMp4KillDuringFinalCopyLeavesDetectablePartialState() {
        val dir = tempDir()
        try {
            val (staging, marker) = simulateCopyKill(
                dir, "effectcraft-video-1.mp4", FinalCopyRecovery.FORMAT_MP4
            )
            assertTrue(marker.isFile)
            assertTrue(staging.isFile)
            assertEquals(1, FinalCopyRecovery.pendingCopyInterruptions(dir))
            val parsed = org.json.JSONObject(marker.readText(Charsets.UTF_8))
            assertEquals("mp4", parsed.getString("format"))
            assertEquals("copying", parsed.getString("phase"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun startupRecoveryCleansAppSideStagingAndMarkersAndReportsProviderPartialMayRemain() {
        val dir = tempDir()
        try {
            val (staging, marker) = simulateCopyKill(
                dir, "effectcraft-sequence-2.zip", FinalCopyRecovery.FORMAT_PNG_SEQUENCE
            )
            val result = FinalCopyRecovery.recover(dir)
            assertEquals(1, result.cleaned)
            assertEquals(0, result.orphanMarkersRemoved)
            assertEquals(0, result.retained)
            assertTrue(result.failed.isEmpty())
            assertTrue(result.cleanedFormats.contains("png-sequence"))
            assertTrue(
                "Recovery must report that a provider-side partial may remain",
                result.providerPartialMayRemain
            )
            assertFalse("Recovery must remove the partial staging file", staging.exists())
            assertFalse("Recovery must remove the copy marker", marker.exists())
            assertEquals(0, FinalCopyRecovery.pendingCopyInterruptions(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun startupRecoveryCleansMixedPngAndMp4CopyMarkersInOnePass() {
        val dir = tempDir()
        try {
            val (pngStaging, pngMarker) = simulateCopyKill(
                dir, "effectcraft-sequence-3.zip", FinalCopyRecovery.FORMAT_PNG_SEQUENCE
            )
            val (mp4Staging, mp4Marker) = simulateCopyKill(
                dir, "effectcraft-video-3.mp4", FinalCopyRecovery.FORMAT_MP4
            )
            val result = FinalCopyRecovery.recover(dir)
            assertEquals(2, result.cleaned)
            assertTrue(result.providerPartialMayRemain)
            assertFalse(pngStaging.exists())
            assertFalse(pngMarker.exists())
            assertFalse(mp4Staging.exists())
            assertFalse(mp4Marker.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recoveryRemovesOrphanCopyMarkerAndRetainsUnparseableMarkerForOtherFormat() {
        val dir = tempDir()
        try {
            val orphan = File(dir, "effectcraft-video-orphan.mp4.copying")
            orphan.writeText(
                "{\"format\":\"mp4\",\"phase\":\"copying\"}", Charsets.UTF_8
            )
            val unparseable = File(dir, "effectcraft-sequence-broken.zip.copying")
            unparseable.writeText("not json", Charsets.UTF_8)
            val result = FinalCopyRecovery.recover(
                dir, formats = setOf(FinalCopyRecovery.FORMAT_MP4)
            )
            assertEquals(0, result.cleaned)
            assertEquals(1, result.orphanMarkersRemoved)
            assertEquals(1, result.retained)
            assertTrue(result.providerPartialMayRemain)
            assertFalse("Orphan copy marker must be removed", orphan.exists())
            assertTrue("Unparseable marker for another format must be retained", unparseable.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun formatScopedRecoveryDoesNotTouchLiveCopyMarkerOfAnotherFormat() {
        val dir = tempDir()
        try {
            val (_, mp4Marker) = simulateCopyKill(
                dir, "effectcraft-video-4.mp4", FinalCopyRecovery.FORMAT_MP4
            )
            val result = FinalCopyRecovery.recover(
                dir, formats = setOf(FinalCopyRecovery.FORMAT_PNG_SEQUENCE)
            )
            assertEquals(0, result.cleaned)
            assertEquals(1, result.retained)
            assertFalse(result.providerPartialMayRemain)
            assertTrue("A live MP4 copy marker must survive a PNG-scoped sweep", mp4Marker.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successfulForegroundExportDeletesCopyMarker() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        try {
            val result = ForegroundPngSequenceExport.run(
                plan = plan(),
                stagingDir = dir,
                renderPng = { png() },
                copyToDestination = copyToFile(destination),
                rollbackDestination = { destination.delete() }
            )
            assertEquals(3, result.framesValidated)
            assertTrue(result.destinationBytes > 0L)
            assertTrue(destination.isFile)
            assertTrue(
                "No copy marker may remain after a successful copy",
                dir.listFiles().orEmpty().none { it.name.endsWith(FinalCopyRecovery.COPY_MARKER_SUFFIX) }
            )
        } finally {
            destination.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedCopyDeletesCopyMarkerAndRollsBackDestination() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        try {
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
                    rollbackDestination = { destination.delete() }
                )
            }
            assertFalse("Partial destination must be rolled back", destination.exists())
            assertTrue(
                "No copy marker may remain after a failed copy",
                dir.listFiles().orEmpty().none { it.name.endsWith(FinalCopyRecovery.COPY_MARKER_SUFFIX) }
            )
        } finally {
            destination.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun restartFromFrameZeroAfterCopyKillProducesValidExport() {
        val dir = tempDir()
        val destination = File(dir, "final.zip")
        try {
            simulateCopyKill(dir, "effectcraft-sequence-9.zip", FinalCopyRecovery.FORMAT_PNG_SEQUENCE)
            assertEquals(1, FinalCopyRecovery.pendingCopyInterruptions(dir))
            val result = ForegroundPngSequenceExport.run(
                plan = plan(),
                stagingDir = dir,
                renderPng = { png() },
                copyToDestination = copyToFile(destination),
                rollbackDestination = { destination.delete() }
            )
            assertEquals(3, result.framesValidated)
            assertTrue(result.destinationBytes > 0L)
            assertEquals(0, FinalCopyRecovery.pendingCopyInterruptions(dir))
            assertTrue(
                "The killed export's staging and marker must be gone after restart",
                dir.listFiles().orEmpty().none { it.name.startsWith(StaleSequenceExportCleanup.FILE_PREFIX) }
            )
            val names = mutableListOf<String>()
            java.util.zip.ZipInputStream(destination.inputStream()).use { zip ->
                while (true) {
                    names += zip.nextEntry?.name ?: break
                    zip.closeEntry()
                }
            }
            assertEquals(
                listOf("sequence.json", "frame-000000.png", "frame-000001.png", "frame-000002.png"),
                names
            )
        } finally {
            destination.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun recoveryOnMissingDirectoryReportsNothingAndNeverClaimsProviderCleanup() {
        val missing = File(tempDir(), "no-such-cache")
        val result = FinalCopyRecovery.recover(missing)
        assertEquals(0, result.cleaned)
        assertFalse(result.providerPartialMayRemain)
    }
}
