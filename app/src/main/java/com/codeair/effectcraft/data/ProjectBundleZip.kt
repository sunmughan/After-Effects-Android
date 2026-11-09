package com.codeair.effectcraft.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a ZIP project bundle: project.json (an .ecproj document or
 * an After Effects interchange document) plus its media files.
 *
 * This is the trust boundary for untrusted archives: entry names are
 * sanitized (no absolute paths, no ".." segments, so nothing escapes
 * the destination), entry counts and sizes are capped (zip-bomb
 * defence), and a bundle without a project document is rejected
 * with a precise error instead of a native parse failure.
 */
object ProjectBundleZip {
    /** Largest total uncompressed size accepted for one bundle. */
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

    /** Largest single entry accepted. */
    const val MAX_ENTRY_BYTES = 200L * 1024 * 1024

    /** Largest entry count accepted. */
    const val MAX_ENTRIES = 2000

    data class Unpacked(
        /** The project document's raw bytes (UTF-8 JSON). */
        val projectDocument: ByteArray,
        /** The entry the project document came from, e.g. "project.json". */
        val projectFileName: String,
        /** Directory the media files were extracted into. */
        val mediaDir: File,
        /** Extracted media entries, as paths relative to [mediaDir]. */
        val mediaFiles: List<String>
    )

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * Unpacks [zipBytes] into [destDir] (created if needed).
     * @throws IllegalArgumentException for anything that is not a usable bundle.
     */
    fun unpack(zipBytes: ByteArray, destDir: File): Unpacked {
        require(isZip(zipBytes)) { "Not a ZIP bundle (missing PK signature)" }
        require(destDir.mkdirs() || destDir.isDirectory) {
            "Cannot create import directory ${destDir.absolutePath}"
        }
        val candidates = mutableListOf<Pair<String, ByteArray>>()
        val mediaFiles = mutableListOf<String>()
        var totalBytes = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries++
                require(entries <= MAX_ENTRIES) { "ZIP bundle has more than $MAX_ENTRIES entries" }
                val rawName = entry.name
                val name = sanitizeEntryName(rawName)
                if (!entry.isDirectory && name != null) {
                    totalBytes += copyEntry(zip, name, destDir, candidates, mediaFiles)
                    require(totalBytes <= MAX_TOTAL_BYTES) {
                        "ZIP bundle exceeds the ${MAX_TOTAL_BYTES / 1024 / 1024} MB import limit"
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val project = pickProjectDocument(candidates)
            ?: throw IllegalArgumentException(
                "ZIP bundle has no project.json: it must contain an EffectCraft " +
                    "project or an After Effects export (project.json) at its root"
            )
        return Unpacked(project.second, project.first, destDir, mediaFiles)
    }

    /**
     * Copies one entry: JSON project candidates stay in memory, media
     * goes straight to disk. Returns the entry's uncompressed size.
     */
    private fun copyEntry(
        zip: ZipInputStream,
        name: String,
        destDir: File,
        candidates: MutableList<Pair<String, ByteArray>>,
        mediaFiles: MutableList<String>
    ): Long {
        val isProjectCandidate = name.endsWith(".json", ignoreCase = true)
        val buffer = ByteArray(8192)
        var size = 0L
        if (isProjectCandidate) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                size += read
                require(size <= CanonicalProjectDocument.MAX_IMPORT_BYTES) {
                    "Project document $name exceeds the ${CanonicalProjectDocument.MAX_IMPORT_BYTES / 1024 / 1024} MB import limit"
                }
                out.write(buffer, 0, read)
            }
            candidates += name to out.toByteArray()
        } else {
            val target = File(destDir, name)
            target.parentFile?.mkdirs()
            require(target.parentFile?.isDirectory == true) {
                "Cannot create directory for $name"
            }
            FileOutputStream(target).use { output ->
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= MAX_ENTRY_BYTES) {
                        "ZIP entry $name exceeds the ${MAX_ENTRY_BYTES / 1024 / 1024} MB entry limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
            mediaFiles += name
        }
        return size
    }

    /**
     * Root project.json wins, then any project.json, then any root
     * *.json. Returns the (entry name, bytes) pair or null.
     */
    private fun pickProjectDocument(
        candidates: List<Pair<String, ByteArray>>
    ): Pair<String, ByteArray>? {
        candidates.firstOrNull { it.first == "project.json" }?.let { return it }
        candidates.firstOrNull { it.first.endsWith("/project.json") }?.let { return it }
        candidates.firstOrNull { !it.first.contains('/') }?.let { return it }
        return null
    }

    /**
     * Returns a safe relative path, or null when the entry must be
     * skipped (absolute path or any ".." segment).
     */
    private fun sanitizeEntryName(rawName: String): String? {
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.length >= 2 && normalized[1] == ':') return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }
}
