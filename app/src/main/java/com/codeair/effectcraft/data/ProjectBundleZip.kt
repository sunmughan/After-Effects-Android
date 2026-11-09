package com.codeair.effectcraft.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Unpacks an offline project bundle containing project.json and optional media.
 *
 * ZIPs are untrusted input: normalized traversal/absolute paths and duplicate
 * names are rejected, extraction is restricted to a fresh/empty destination,
 * and entry count + expanded byte limits bound memory and disk consumption.
 */
object ProjectBundleZip {
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
    const val MAX_ENTRIES = 2_000

    data class Unpacked(
        val projectDocument: ByteArray,
        val projectFileName: String,
        val mediaDir: File,
        val mediaFiles: List<String>
    )

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    fun unpack(zipBytes: ByteArray, destDir: File): Unpacked {
        require(isZip(zipBytes)) { "Not a ZIP bundle (missing PK signature)" }
        require(!Files.isSymbolicLink(destDir.toPath())) { "Import destination must not be a symbolic link" }
        val created = destDir.mkdirs()
        require(created || destDir.isDirectory) {
            "Cannot create import directory ${destDir.absolutePath}"
        }
        require(destDir.listFiles()?.isEmpty() == true) {
            "Import destination must be an empty directory"
        }

        val root = destDir.canonicalFile
        val candidates = mutableListOf<Pair<String, ByteArray>>()
        val mediaFiles = mutableListOf<String>()
        val names = mutableSetOf<String>()
        var totalBytes = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries++
                    require(entries <= MAX_ENTRIES) {
                        "ZIP bundle has more than $MAX_ENTRIES entries"
                    }
                    val name = sanitizeEntryName(entry.name)
                    if (name != null) {
                        require(names.add(name)) { "ZIP bundle contains duplicate entry: $name" }
                        if (!entry.isDirectory) {
                            val expanded = copyEntry(zip, name, root, candidates, mediaFiles)
                            totalBytes += expanded
                            require(totalBytes <= MAX_TOTAL_BYTES) {
                                "ZIP bundle exceeds the ${MAX_TOTAL_BYTES / 1024 / 1024} MB import limit"
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val project = pickProjectDocument(candidates)
                ?: throw IllegalArgumentException(
                    "ZIP bundle has no project.json: include an EffectCraft project or an After Effects export"
                )
            return Unpacked(project.second, project.first, root, mediaFiles)
        } catch (failure: Throwable) {
            // Never leave a partially extracted bundle that could be mistaken
            // for a successful import on a later attempt.
            root.listFiles()?.forEach { it.deleteRecursively() }
            if (created) root.delete()
            throw failure
        }
    }

    private fun copyEntry(
        zip: ZipInputStream,
        name: String,
        root: File,
        candidates: MutableList<Pair<String, ByteArray>>,
        mediaFiles: MutableList<String>
    ): Long {
        val target = File(root, name)
        val canonicalTarget = target.canonicalFile
        val rootPrefix = root.path + File.separator
        require(canonicalTarget.path.startsWith(rootPrefix)) {
            "ZIP entry escapes import destination: $name"
        }
        val projectCandidate = name.endsWith(".json", ignoreCase = true)
        val buffer = ByteArray(8_192)
        var size = 0L
        if (projectCandidate) {
            val out = ByteArrayOutputStream()
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
            val parent = canonicalTarget.parentFile
            require(parent.mkdirs() || parent.isDirectory) {
                "Cannot create directory for $name"
            }
            require(parent.canonicalFile.path.startsWith(rootPrefix)) {
                "ZIP entry parent escapes import destination: $name"
            }
            FileOutputStream(canonicalTarget).use { output ->
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

    private fun pickProjectDocument(
        candidates: List<Pair<String, ByteArray>>
    ): Pair<String, ByteArray>? {
        candidates.firstOrNull { it.first == "project.json" }?.let { return it }
        candidates.firstOrNull { it.first.endsWith("/project.json") }?.let { return it }
        candidates.firstOrNull { !it.first.contains('/') }?.let { return it }
        return null
    }

    private fun sanitizeEntryName(rawName: String): String? {
        val normalized = rawName.replace('\\\\', '/')
        if (normalized.startsWith("/") ||
            normalized.startsWith("//") ||
            (normalized.length >= 2 && normalized[1] == ':')
        ) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }
}
