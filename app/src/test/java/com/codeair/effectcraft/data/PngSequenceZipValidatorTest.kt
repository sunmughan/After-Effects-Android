package com.codeair.effectcraft.data

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PngSequenceZipValidatorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun plan(start: Int = 0, end: Int = 1) =
        RenderQueuePlan.create(RenderQueueSettings(320, 320, 30, start, end, RenderOutputFormat.PNG_SEQUENCE_ZIP))

    private fun png(width: Int = 320, height: Int = 320): ByteArray =
        ByteArray(24).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
            byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(bytes, 12)
            ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        }

    private fun manifest(plan: RenderQueuePlan): ByteArray = PngSequenceZipExporter.sequenceManifest(plan)

    private fun archive(entries: List<Pair<String, ByteArray>>): java.io.File =
        temp.newFile("sequence-${System.nanoTime()}.zip").also { file ->
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                entries.forEach { (name, payload) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun validatesCompleteOrderedArchiveAndCrc() {
        val plan = plan()
        val file = archive(listOf("sequence.json" to manifest(plan), plan.zipEntryName(0) to png(), plan.zipEntryName(1) to png()))
        val result = PngSequenceZipValidator.validate(file, plan)
        assertEquals(2, result.framesValidated)
        assertEquals(48L, result.uncompressedBytes)
    }

    @Test
    fun rejectsMissingExtraAndMisnamedFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            val plan = plan()
            PngSequenceZipValidator.validate(archive(listOf("sequence.json" to manifest(plan), "frame-000000.png" to png())), plan)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipValidator.validate(
                archive(listOf("sequence.json" to manifest(plan()), "frame-000000.png" to png(), "frame-000001.png" to png(), "extra.png" to png())),
                plan()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipValidator.validate(
                archive(listOf("sequence.json" to manifest(plan()), "frame-000000.png" to png(), "wrong.png" to png())),
                plan()
            )
        }
    }

    @Test
    fun rejectsManifestSettingsThatDoNotMatchThePlan() {
        val expected = plan()
        val other = plan(start = 1, end = 2)
        val file = archive(listOf(
            "sequence.json" to manifest(other),
            expected.zipEntryName(0) to png(),
            expected.zipEntryName(1) to png()
        ))
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipValidator.validate(file, expected)
        }
    }

    @Test
    fun rejectsWrongPngDimensionsAndEmptyArchive() {
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipValidator.validate(
                archive(listOf("sequence.json" to manifest(plan()), "frame-000000.png" to png(640, 320), "frame-000001.png" to png())),
                plan()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PngSequenceZipValidator.validate(temp.newFile("empty.zip"), plan())
        }
    }
}
