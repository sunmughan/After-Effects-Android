package com.codeair.effectcraft.data

import com.codeair.effectcraft.engine.RenderBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Offline acceptance at the unit level: open (validate) a local project,
 * edit it (keyframes), plan a render within budget, and export a frame
 * sequence — every step from local files only, with no network or account
 * API anywhere in the path.
 */
class OfflineAcceptanceTest {

    private fun minimalPng(width: Int, height: Int): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val ihdr = ByteBuffer.allocate(4 + 4 + 13).order(ByteOrder.BIG_ENDIAN)
            .putInt(13)
            .put(byteArrayOf(0x49, 0x48, 0x44, 0x52))
            .putInt(width).putInt(height)
            .put(byteArrayOf(8, 2, 0, 0, 0))
        val iend = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .put(byteArrayOf(0x49, 0x45, 0x4e, 0x44))
            .putInt(0)
        return signature + ihdr.array() + iend.array()
    }

    @Test
    fun fullLocalFlowOpenEditPlanExportUsesOnlyLocalFiles() {
        val root = Files.createTempDirectory("effectcraft-offline").toFile()
        try {
            // Open: a valid local project validates with no network.
            val project = ProjectDocument.starter()
            assertTrue(project.validationErrors().isEmpty())

            // Edit: keyframes persist atomically to a local directory.
            val keyframes = ProjectKeyframeStore(root.resolve("keyframes"))
            keyframes.upsert(project.id, project.layers.first().id, "opacity", 10, 0.5f)
            assertEquals(1, ProjectKeyframeStore(root.resolve("keyframes")).load(project.id).size)

            // Preview: the frame fits the enforced render budget.
            RenderBudget.checkFrameDimensions(project.width, project.height)

            // Export: a 3-frame sequence writes a local ZIP only.
            val settings = RenderQueueSettings(
                width = 320, height = 180, frameRate = 30, startFrame = 0, endFrame = 2
            )
            val plan = RenderQueuePlan.create(settings)
            val output = ByteArrayOutputStream()
            val result = PngSequenceZipExporter.export(plan, output, { minimalPng(320, 180) })
            assertEquals(3, result.framesWritten)

            val entries = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            }
            assertEquals(
                listOf("sequence.json", "frame-000000.png", "frame-000001.png", "frame-000002.png"),
                entries
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
