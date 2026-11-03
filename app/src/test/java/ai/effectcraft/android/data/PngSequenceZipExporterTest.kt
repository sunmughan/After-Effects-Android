package ai.effectcraft.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PngSequenceZipExporterTest {
    private fun plan(start: Int = 4, end: Int = 5) =
        RenderQueuePlan.create(RenderQueueSettings(320, 320, 24, start, end))

    /** Header-only PNG fixture: exporter validates signature/IHDR, ZIP preserves bytes verbatim. */
    private fun png(width: Int = 320, height: Int = 320): ByteArray =
        ByteArray(24).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                .copyInto(bytes, 0)
            byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(bytes, 12)
            ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        }

    @Test
    fun writesOrderedPngEntriesAndReportsProgress() {
        val output = ByteArrayOutputStream()
        val frames = mapOf(4 to png(), 5 to png())
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = PngSequenceZipExporter.export(
            plan(), output, { frames.getValue(it) },
            onProgress = { count, percent -> progress += count to percent }
        )
        assertEquals(2, result.framesWritten)
        assertEquals(frames.values.sumOf { it.size.toLong() }, result.bytesOfPng)
        assertEquals(listOf(1 to 50, 2 to 100), progress)

        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes()
                zip.closeEntry()
            }
        }
        assertEquals(listOf("frame-000004.png", "frame-000005.png"), entries.map { it.first })
        assertArrayEquals(frames.getValue(4), entries[0].second)
        assertArrayEquals(frames.getValue(5), entries[1].second)
    }

    @Test
    fun cancellationStopsBeforeRenderingTheNextFrame() {
        val output = ByteArrayOutputStream()
        var cancelled = false
        val rendered = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            PngSequenceZipExporter.export(
                plan(start = 0, end = 2), output,
                { frame -> rendered += frame; png() },
                isCancelled = { cancelled },
                onProgress = { _, _ -> cancelled = true }
            )
        }
        assertEquals(listOf(0), rendered)
        // The first completed entry remains readable; callers must discard the partial document.
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals("frame-000000.png", zip.nextEntry?.name)
        }
    }

    @Test
    fun rejectsNonPngAndDimensionMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipExporter.export(plan(start = 0, end = 0), ByteArrayOutputStream(), { ByteArray(24) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipExporter.export(plan(start = 0, end = 0), ByteArrayOutputStream(), { png(640, 320) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipExporter.export(plan(start = 0, end = 0), ByteArrayOutputStream(), { png() }, isCancelled = { true })
        }
    }
}
