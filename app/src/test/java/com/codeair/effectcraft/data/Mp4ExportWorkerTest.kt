package com.codeair.effectcraft.data

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ExportWorkerTest {
    @Test
    fun optionalAudioFallbackReturnsValueOnSuccess() {
        assertEquals("mixed", Mp4ExportWorker.runAudioFallback { "mixed" })
    }

    @Test
    fun optionalAudioFallbackRecoversOrdinaryFailure() {
        assertNull(Mp4ExportWorker.runAudioFallback<String> {
            throw IllegalStateException("audio decoder unavailable")
        })
    }

    @Test
    fun optionalAudioFallbackNeverSwallowsCancellation() {
        val cancellation = CancellationException("user cancelled export")
        val observed = try {
            Mp4ExportWorker.runAudioFallback<String> { throw cancellation }
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertTrue("Cancellation must propagate unchanged", observed === cancellation)
    }

    private fun item(
        hasAudio: Boolean,
        missing: Boolean = false,
        path: String? = null
    ) = CanonicalProjectItemInfo(
        id = 1L, name = "clip", type = "footage", parentId = null, path = path,
        width = 0, height = 0, hasVideo = false, hasAudio = hasAudio,
        missing = missing, sequence = emptyList()
    )

    private val sha = "a".repeat(64)

    @Test
    fun resolveAudioReturnsFirstResolvableOfflinePayload() {
        val found = java.nio.file.Files.createTempFile("effectcraft-audio", ".m4a").toFile()
        try {
            val resolution = Mp4ExportWorker.resolveAudio(
                listOf(
                    item(hasAudio = true, path = "https://example.invalid/not-offline.mp4"),
                    item(hasAudio = true, path = "offline/$sha.m4a"),
                    item(hasAudio = true, path = "offline/${"b".repeat(64)}.m4a")
                )
            ) { hash -> if (hash == sha) found else null }
            assertTrue(resolution.hasAudioItems)
            assertEquals(found, resolution.file)
        } finally {
            found.delete()
        }
    }

    @Test
    fun resolveAudioRequiresContentAddressedPayloadVerification() {
        val first = java.nio.file.Files.createTempFile("effectcraft-audio-unverified", ".m4a").toFile()
        val second = java.nio.file.Files.createTempFile("effectcraft-audio-verified", ".m4a").toFile()
        val firstHash = "a".repeat(64)
        val secondHash = "b".repeat(64)
        try {
            val resolution = Mp4ExportWorker.resolveAudio(
                listOf(
                    item(hasAudio = true, path = "offline/$firstHash.m4a"),
                    item(hasAudio = true, path = "offline/$secondHash.m4a")
                ),
                verifyPayload = { hash, file -> hash == secondHash && file == second }
            ) { hash ->
                when (hash) {
                    firstHash -> first
                    secondHash -> second
                    else -> null
                }
            }
            assertTrue(resolution.hasAudioItems)
            assertEquals("Corrupt/unverified first asset must be skipped in favor of verified second", second, resolution.file)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun resolveAudioSkipsMissingAndUnreadableOfflinePayloads() {
        val dir = java.nio.file.Files.createTempDirectory("effectcraft-audio-resolution").toFile()
        val missing = File(dir, "missing.m4a")
        val directoryInsteadOfFile = File(dir, "not-a-media-file")
        directoryInsteadOfFile.mkdir()
        try {
            val resolution = Mp4ExportWorker.resolveAudio(
                listOf(
                    item(hasAudio = true, path = "offline/${"a".repeat(64)}.m4a"),
                    item(hasAudio = true, path = "offline/${"b".repeat(64)}.m4a"),
                    item(hasAudio = true, path = "offline/${"c".repeat(64)}.m4a")
                )
            ) { hash ->
                when (hash) {
                    "a".repeat(64) -> missing
                    "b".repeat(64) -> directoryInsteadOfFile
                    else -> null
                }
            }
            assertTrue(resolution.hasAudioItems)
            assertEquals(null, resolution.file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveAudioReportsAudioItemsWhenNothingResolves() {
        val resolution = Mp4ExportWorker.resolveAudio(
            listOf(
                item(hasAudio = true, missing = true, path = "offline/$sha.m4a"),
                item(hasAudio = true, path = "offline/not-a-hash.m4a")
            )
        ) { null }
        assertTrue(resolution.hasAudioItems)
        assertNull(resolution.file)
    }

    @Test
    fun resolveAudioIgnoresNonAudioItems() {
        val resolution = Mp4ExportWorker.resolveAudio(
            listOf(item(hasAudio = false, path = "offline/$sha.m4a"))
        ) { File("/tmp/never.m4a") }
        assertFalse(resolution.hasAudioItems)
        assertNull(resolution.file)
    }

    @Test
    fun resolveAudioRejectsSameSizeCorruptionInOfflineAssetStore() {
        val dir = java.nio.file.Files.createTempDirectory("effectcraft-audio-store").toFile()
        val store = OfflineAssetStore(dir)
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val asset = store.import(java.io.ByteArrayInputStream(bytes), "voice.m4a", "audio/mp4")
        val item = item(hasAudio = true, path = "offline/" + asset.sha256 + "." + asset.extension)
        try {
            val valid = Mp4ExportWorker.resolveAudio(
                listOf(item),
                verifyPayload = { hash, _ -> store.verify(hash) }
            ) { hash -> store.fileFor(hash) }
            val payload = store.fileFor(asset.sha256)!!
            assertEquals(payload, valid.file)
            assertTrue(store.verify(asset.sha256))

            // Preserve the indexed length while changing the content digest.
            payload.writeBytes(byteArrayOf(6, 5, 4, 3, 2, 1))
            assertEquals(bytes.size.toLong(), payload.length())
            assertFalse(store.verify(asset.sha256))
            val corrupted = Mp4ExportWorker.resolveAudio(
                listOf(item),
                verifyPayload = { hash, _ -> store.verify(hash) }
            ) { hash -> store.fileFor(hash) }
            assertTrue(corrupted.hasAudioItems)
            assertEquals("Same-size bit rot must not reach MediaExtractor", null, corrupted.file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun audioRangeUsesCompositionFrameRateNotExportFrameRate() {
        // Export frames 30..59 at 10 FPS from a 30 FPS composition:
        // composition time runs 1,000,000..2,000,000us regardless of export FPS.
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(64, 64, 10, 30, 59, RenderOutputFormat.MP4_H264)
        )
        val (startUs, endUs) = Mp4ExportWorker.audioRangeUs(plan, 30, 1)
        assertEquals(1_000_000L, startUs)
        assertEquals(2_000_000L, endUs)
    }

    @Test
    fun audioRangeUsesFractionalCompositionFrameRate() {
        val plan = RenderQueuePlan.create(
            RenderQueueSettings(64, 64, 30, 0, 29, RenderOutputFormat.MP4_H264)
        )
        // 30000/1001 FPS: 30 frames = 30 * 1,000,000 * 1001 / 30000 = 1,001,000us.
        val (startUs, endUs) = Mp4ExportWorker.audioRangeUs(plan, 30000, 1001)
        assertEquals(0L, startUs)
        assertEquals(1_001_000L, endUs)
    }
}
