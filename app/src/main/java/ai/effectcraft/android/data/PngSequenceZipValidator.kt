package ai.effectcraft.android.data

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Enumeration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Verifies a staged PNG sequence ZIP before it is copied to a SAF destination.
 * Reads each frame in bounded chunks, validates PNG dimensions and the ZIP
 * entry's uncompressed size/CRC, and rejects missing, extra or misnamed frames.
 */
object PngSequenceZipValidator {
    data class Result(val framesValidated: Int, val uncompressedBytes: Long)

    fun validate(archive: File, plan: RenderQueuePlan): Result {
        require(archive.isFile && archive.length() > 0L) { "PNG sequence archive is missing or empty" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var frame = plan.settings.startFrame
            var count = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(count < plan.frameCount) { "PNG sequence archive contains extra entries" }
                require(!entry.isDirectory) { "PNG sequence archive contains a directory entry" }
                require(entry.name == plan.zipEntryName(frame)) {
                    "PNG sequence entry ${entry.name} does not match expected ${plan.zipEntryName(frame)}"
                }
                val verifiedBytes = verifyEntry(zip, entry, plan.settings.width, plan.settings.height)
                require(entry.size == verifiedBytes) { "ZIP entry size is inconsistent" }
                totalBytes = Math.addExact(totalBytes, verifiedBytes)
                count++
                frame++
            }
            require(count == plan.frameCount) { "PNG sequence archive contains $count of ${plan.frameCount} frames" }
            return Result(count, totalBytes)
        }
    }

    private fun verifyEntry(zip: ZipFile, entry: ZipEntry, expectedWidth: Int, expectedHeight: Int): Long {
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val header = ByteArray(24)
            readFully(input, header)
            require(header.take(8).toByteArray().contentEquals(PNG_SIGNATURE)) { "ZIP entry is not a PNG" }
            require(header[12] == 0x49.toByte() && header[13] == 0x48.toByte() &&
                header[14] == 0x44.toByte() && header[15] == 0x52.toByte()) { "PNG entry is missing IHDR" }
            val dimensions = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.BIG_ENDIAN)
            require(dimensions.int == expectedWidth && dimensions.int == expectedHeight) {
                "PNG entry dimensions do not match render plan"
            }
            crc.update(header)
            var size = header.size.toLong()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                crc.update(buffer, 0, read)
                size = Math.addExact(size, read.toLong())
            }
            require(entry.crc == crc.value) { "ZIP entry CRC mismatch" }
            return size
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            require(read > 0) { "PNG ZIP entry is truncated" }
            offset += read
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
}
