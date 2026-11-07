package com.codeair.effectcraft.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the last SAF-imported canonical EffectCraft document byte-for-byte.
 * This is an import/validation cache only; it is not an editor serializer.
 */
class EcprojDocumentStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "canonical-projects"))

    private val lock = locks.computeIfAbsent(directory.absoluteFile.toPath().normalize().toString()) { Any() }
    private val document = File(directory, "latest.ecproj")
    private val temporary = File(directory, "latest.ecproj.tmp")

    @Throws(IOException::class)
    fun save(source: String): File = synchronized(lock) {
        require(source.isNotBlank()) { "Project document is empty" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create canonical project cache")
        }
        // Timeline/transform edits are committed here on every canonical
        // edit. Flush and fsync before promotion so a process/device crash
        // cannot leave a renamed but unwritten project copy.
        FileOutputStream(temporary).use { output ->
            output.write(source.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Only a filesystem that explicitly lacks atomic rename permits
            // fallback. Permission/storage errors must not overwrite the last
            // committed canonical document with a non-atomic move.
            Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        document
    }

    fun load(): String? = synchronized(lock) {
        recoverInterruptedWrite()
        document.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }

    /**
     * A complete fsynced canonical edit may be left in the temp if the
     * process dies between write and rename. Promote only a non-empty JSON
     * object; malformed/torn temps are discarded without touching the last
     * committed document.
     */
    private fun recoverInterruptedWrite() {
        if (!temporary.isFile) return
        val valid = runCatching {
            val source = temporary.readText(Charsets.UTF_8)
            require(source.isNotBlank())
            JSONObject(source)
        }.isSuccess
        if (!valid) {
            temporary.delete()
            return
        }
        try {
            Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // Keep the complete temp for another startup recovery attempt.
            }
        } catch (_: IOException) {
            // A transient permission/storage failure is not evidence that
            // atomic rename is unsupported. Keep the temp for retry and leave
            // the previously committed document untouched.
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
