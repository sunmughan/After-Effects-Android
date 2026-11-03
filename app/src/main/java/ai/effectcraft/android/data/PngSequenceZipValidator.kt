package ai.effectcraft.android.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Verifies a staged PNG sequence ZIP before it is copied to a SAF destination.
 * Reads each frame in bounded chunks, validates PNG dimensions and the ZIP
 * entry uncompressed size/CRC, and rejects missing, extra or misnamed frames.
 */
object PngSequenceZipValidator {
    data class Result(val framesValidated: Int, val uncompressedBytes: Long)

    fun validate(archive: File, plan: RenderQueuePlan): Result {
        require(archive.isFile && archive.length() > 0L) { "PNG sequence archive is missing or empty" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            require(entries.hasMoreElements()) { "PNG sequence archive is empty" }
            val manifestEntry = entries.nextElement()
            require(manifestEntry.name == "sequence.json" && !manifestEntry.isDirectory) {
                "PNG sequence archive is missing its manifest"
            }
            val manifestBytes = readEntry(zip, manifestEntry, 64 * 1024)
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            require(manifest.optInt("schema") == 1 && manifest.optString("format") == "png-sequence") {
                "PNG sequence manifest schema/format is unsupported"
            }
            require(manifest.optInt("width") == plan.settings.width &&
                manifest.optInt("height") == plan.settings.height &&
                manifest.optInt("frameRate") == plan.settings.frameRate &&
                manifest.optInt("startFrame") == plan.settings.startFrame &&
                manifest.optInt("endFrame") == plan.settings.endFrame &&
                manifest.optInt("frameCount") == plan.frameCount) {
                "PNG sequence manifest does not match render plan"
            }

            var frame = plan.settings.startFrame
            var count = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(count < plan.frameCount) { "PNG sequence archive contains extra entries" }
                require(!entry.isDirectory) { "PNG sequence archive contains a directory entry" }
                require(entry.name == plan.zipEntryName(frame)) {
                    "PNG sequence entry " + entry.name + " does not match expected " + plan.zipEntryName(frame)
                }
                val verifiedBytes = verifyPngEntry(zip, entry, plan.settings.width, plan.settings.height)
                require(entry.size == verifiedBytes) { "ZIP entry size is inconsistent" }
                totalBytes = Math.addExact(totalBytes, verifiedBytes)
                count++
                frame++
            }
            require(count == plan.frameCount) { "PNG sequence archive contains " + count + " of " + plan.frameCount + " frames" }
            return Result(count, totalBytes)
        }
    }

    private fun verifyPngEntry(zip: ZipFile, entry: ZipEntry, expectedWidth: Int, expectedHeight: Int): Long {
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

    private fun readEntry(zip: ZipFile, entry: ZipEntry, maxBytes: Int): ByteArray {
        val crc = CRC32()
        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(output.size() + read <= maxBytes) { "PNG sequence manifest exceeds its size limit" }
                crc.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        val bytes = output.toByteArray()
        require(entry.size == bytes.size.toLong() && entry.crc == crc.value) { "PNG sequence manifest size/CRC mismatch" }
        return bytes
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            require(read > 0) { "PNG ZIP entry is truncated" }
            offset += read
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
