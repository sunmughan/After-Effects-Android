package com.codeair.effectcraft.data

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams rendered PNG frames into a ZIP without retaining a sequence of bitmaps.
 *
 * The caller owns SAF document cleanup if cancellation/failure leaves a partial
 * ZIP. Prefer rendering to an app-private temporary file first when the target
 * provider cannot delete a partially written document.
 */
object PngSequenceZipExporter {
    data class Result(val framesWritten: Int, val bytesOfPng: Long)

    fun export(
        plan: RenderQueuePlan,
        output: OutputStream,
        renderPng: (frame: Int) -> ByteArray,
        isCancelled: () -> Boolean = { false },
        onProgress: (completedFrames: Int, percent: Int) -> Unit = { _, _ -> }
    ): Result {
        require(plan.settings.outputFormat == RenderOutputFormat.PNG_SEQUENCE_ZIP) {
            "Unsupported render output format"
        }
        var framesWritten = 0
        var pngBytes = 0L
        ZipOutputStream(output).use { zip ->
            val manifest = sequenceManifest(plan)
            zip.putNextEntry(ZipEntry("sequence.json").apply { time = 0L })
            zip.write(manifest)
            zip.closeEntry()
            for (frame in plan.frames) {
                checkNotCancelled(isCancelled)
                val png = renderPng(frame)
                checkNotCancelled(isCancelled)
                validatePng(png, plan.settings.width, plan.settings.height)
                zip.putNextEntry(ZipEntry(plan.zipEntryName(frame)).apply { time = 0L })
                zip.write(png)
                zip.closeEntry()
                framesWritten++
                pngBytes = Math.addExact(pngBytes, png.size.toLong())
                onProgress(framesWritten, plan.progressPercent(framesWritten))
            }
            zip.finish()
        }
        return Result(framesWritten, pngBytes)
    }

    internal fun sequenceManifest(plan: RenderQueuePlan): ByteArray = buildString {
        append("{\"schema\":1,\"format\":\"png-sequence\",")
        append("\"width\":").append(plan.settings.width).append(',')
        append("\"height\":").append(plan.settings.height).append(',')
        append("\"frameRate\":").append(plan.settings.frameRate).append(',')
        append("\"startFrame\":").append(plan.settings.startFrame).append(',')
        append("\"endFrame\":").append(plan.settings.endFrame).append(',')
        append("\"frameCount\":").append(plan.frameCount).append('}')
    }.toByteArray(Charsets.UTF_8)

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("PNG sequence export cancelled")
    }

    private fun validatePng(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int) {
        require(bytes.size >= 24) { "Rendered PNG is truncated" }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(signature.indices.all { bytes[it] == signature[it] }) { "Renderer returned a non-PNG frame" }
        require(bytes[12] == 0x49.toByte() && bytes[13] == 0x48.toByte() &&
            bytes[14] == 0x44.toByte() && bytes[15] == 0x52.toByte()) { "PNG is missing its IHDR" }
        val header = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
        val width = header.int
        val height = header.int
        require(width == expectedWidth && height == expectedHeight) {
            "Rendered PNG dimensions " + width + "×" + height + " do not match " + expectedWidth + "×" + expectedHeight
        }
    }
}
