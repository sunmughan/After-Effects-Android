package ai.effectcraft.android.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the last SAF-imported canonical EffectCraft document byte-for-byte.
 * This is an import/validation cache only; it is not an editor serializer.
 */
class EcprojDocumentStore(context: Context) {
    private val directory = File(context.filesDir, "canonical-projects")
    private val document = File(directory, "latest.ecproj")

    @Throws(IOException::class)
    fun save(source: String): File {
        require(source.isNotBlank()) { "Project document is empty" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create canonical project cache")
        }
        val temporary = File(directory, "latest.ecproj.tmp")
        temporary.writeText(source, Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return document
    }

    fun load(): String? = document.takeIf(File::isFile)?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
}
