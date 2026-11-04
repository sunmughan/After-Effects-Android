package com.codeair.effectcraft.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Editable, multi-project repository adapter for canonical EffectCraft
 * documents. This is the adapter half of the M2 cluster: the existing
 * [EcprojDocumentStore] keeps only the single latest import, while this
 * repository keeps every imported project as its own document plus a
 * versioned metadata sidecar, so projects survive side by side and can be
 * listed, updated and deleted independently.
 *
 * Schema rules, enforced here and nowhere else:
 * - The document bytes are never mutated by versioning: the schema version
 *   lives in the sidecar, so a migration cannot corrupt an upstream .ecproj.
 * - [CURRENT_SCHEMA_VERSION] is the only accepted sidecar version. A sidecar
 *   without a version is a legacy (v0) record and migrates to v1 with defaults.
 *   A sidecar claiming a newer version is rejected, never silently accepted.
 * - Structural validation ([validateDocument]) checks that an .ecproj parses
 *   as a JSON object and an .ecprojx carries the EffectCraft XML root. It is
 *   not a substitute for native schema validation: imports still pass the JNI
 *   gate before they reach this repository, and .ecprojx XML→JSON
 *   normalization still belongs to [CanonicalProjectDocument].
 * - The sidecar list is the source of truth for [list]: a document file
 *   without a sidecar is inert and never listed.
 */
class CanonicalProjectRepository(private val root: File) {
    data class Metadata(
        val id: String,
        val displayName: String,
        val format: String,
        val schemaVersion: Int,
        val documentSha256: String,
        val updatedAtMillis: Long,
        val sourceName: String?
    )

    data class MigrationResult(
        val id: String,
        val fromVersion: Int?,
        val toVersion: Int?,
        val error: String?
    )

    @Synchronized
    @Throws(IOException::class)
    fun create(displayName: String, document: String, format: String, sourceName: String? = null): Metadata {
        val name = validateDisplayName(displayName)
        val checkedFormat = validateFormat(format)
        validateDocument(document, checkedFormat)
        ensureDirectory()
        val id = UUID.randomUUID().toString()
        val metadata = Metadata(
            id, name, checkedFormat, CURRENT_SCHEMA_VERSION,
            document.sha256(), System.currentTimeMillis(), sourceName?.takeIf { it.isNotBlank() }
        )
        writeDocument(id, checkedFormat, document)
        try {
            writeSidecar(metadata)
        } catch (failure: Throwable) {
            documentFile(id, checkedFormat).delete()
            throw failure
        }
        return metadata
    }

    /** Imports the old single-document cache as a repository entry. */
    @Synchronized
    @Throws(IOException::class)
    fun importLegacyDocument(document: String, displayName: String): Metadata =
        create(displayName, document, FORMAT_ECPROJ, null)

    @Synchronized
    @Throws(IOException::class)
    fun update(id: String, document: String): Metadata {
        val checkedId = validateId(id)
        val previous = metadata(checkedId) ?: throw IOException("Canonical project $checkedId is not in the repository")
        validateDocument(document, previous.format)
        val previousBytes = load(checkedId)
        val updated = previous.copy(
            documentSha256 = document.sha256(),
            updatedAtMillis = System.currentTimeMillis()
        )
        writeDocument(checkedId, previous.format, document)
        try {
            writeSidecar(updated)
        } catch (failure: Throwable) {
            // Roll back the document so a failed metadata write never leaves a
            // document/sidecar pair that disagrees about the project content.
            if (previousBytes != null) writeDocument(checkedId, previous.format, previousBytes)
            throw failure
        }
        return updated
    }

    @Synchronized
    fun load(id: String): String? {
        val metadata = metadata(validateId(id)) ?: return null
        val file = documentFile(metadata.id, metadata.format)
        return file.takeIf(File::isFile)?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
    }

    @Synchronized
    fun metadata(id: String): Metadata? {
        val file = sidecarFile(validateId(id))
        if (!file.isFile) return null
        return runCatching { parseSidecar(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** Sidecar-driven listing: newest first, display name breaks timestamp ties. */
    @Synchronized
    fun list(): List<Metadata> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file -> runCatching { parseSidecar(file.readText(Charsets.UTF_8)) }.getOrNull() }
            .sortedWith(compareByDescending<Metadata> { it.updatedAtMillis }.thenBy { it.displayName })
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val checkedId = validateId(id)
        val metadata = metadata(checkedId) ?: return false
        var removed = false
        if (documentFile(checkedId, metadata.format).exists()) {
            if (!documentFile(checkedId, metadata.format).delete()) throw IOException("Unable to remove canonical project document")
            removed = true
        }
        if (sidecarFile(checkedId).exists()) {
            if (!sidecarFile(checkedId).delete()) throw IOException("Unable to remove canonical project metadata")
            removed = true
        }
        return removed
    }

    /**
     * Migrates every legacy (version-less) sidecar to [CURRENT_SCHEMA_VERSION]
     * and reports — without migrating — any sidecar that claims a newer
     * version or is corrupt. Migration only fills sidecar defaults; document
     * bytes are never rewritten.
     */
    @Synchronized
    fun migrateAll(): List<MigrationResult> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                val raw = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
                    ?: return@mapNotNull MigrationResult(file.nameWithoutExtension, null, null, "Corrupt project metadata")
                val id = raw.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (raw.has("version")) {
                    val version = raw.optInt("version", -1)
                    if (version == CURRENT_SCHEMA_VERSION) null
                    else MigrationResult(id, version, null, "Project metadata version $version requires a newer app version")
                } else {
                    val migrated = parseSidecar(raw.toString())
                    writeSidecar(migrated)
                    MigrationResult(id, 0, CURRENT_SCHEMA_VERSION, null)
                }
            }
    }

    /** Structural check only; native schema validation remains the JNI import gate. */
    fun validateDocument(document: String, format: String) {
        val checkedFormat = validateFormat(format)
        require(document.isNotBlank()) { "Project document is empty" }
        when (checkedFormat) {
            FORMAT_ECPROJ -> JSONObject(document)
            FORMAT_ECPROJX -> {
                val probe = document.trimStart('\uFEFF').trimStart()
                require(probe.startsWith("<$XML_ROOT") || (probe.startsWith("<?xml") && probe.contains("<$XML_ROOT"))) {
                    "EffectCraft XML project must use the <$XML_ROOT> root"
                }
            }
            else -> error("Unsupported project format")
        }
    }

    private fun parseSidecar(json: String): Metadata {
        val raw = JSONObject(json)
        require(raw.optString("format") == SIDECAR_FORMAT) { "Unsupported project metadata format" }
        val version = if (raw.has("version")) raw.getInt("version") else 0
        val id = validateId(raw.getString("id"))
        val documentHash = raw.optString("documentSha256")
        val documentFile = raw.optString("projectFormat").takeIf { it.isNotBlank() }?.let { format ->
            documentFile(id, validateFormat(format))
        }
        val recomputed = documentFile?.takeIf(File::isFile)?.let { it.readText(Charsets.UTF_8).sha256() }
        return Metadata(
            id = id,
            displayName = raw.optString("displayName").takeIf { it.isNotBlank() } ?: "Imported project",
            format = raw.optString("projectFormat").takeIf { it.isNotBlank() }?.let(::validateFormat) ?: FORMAT_ECPROJ,
            schemaVersion = if (version == 0) CURRENT_SCHEMA_VERSION else version,
            documentSha256 = recomputed ?: documentHash,
            updatedAtMillis = raw.optLong("updatedAtMillis", 0L),
            sourceName = raw.optString("sourceName").takeIf { it.isNotEmpty() }
        )
    }

    private fun ensureDirectory() {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create canonical project repository")
        if (!root.isDirectory) throw IOException("Canonical project repository path is not a directory")
    }

    private fun writeDocument(id: String, format: String, document: String) {
        ensureDirectory()
        val target = documentFile(id, format)
        val temporary = File(root, "${target.name}.tmp")
        temporary.writeText(document, Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeSidecar(metadata: Metadata) {
        ensureDirectory()
        val target = sidecarFile(metadata.id)
        val temporary = File(root, "${target.name}.tmp")
        val document = JSONObject().apply {
            put("format", SIDECAR_FORMAT)
            put("version", metadata.schemaVersion)
            put("id", metadata.id)
            put("displayName", metadata.displayName)
            put("projectFormat", metadata.format)
            put("documentSha256", metadata.documentSha256)
            put("updatedAtMillis", metadata.updatedAtMillis)
            if (metadata.sourceName != null) put("sourceName", metadata.sourceName)
        }
        temporary.writeText(document.toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun documentFile(id: String, format: String): File =
        File(root, "$id.$format")

    private fun sidecarFile(id: String): File = File(root, "$id.json")

    private fun validateId(value: String): String {
        require(value.isNotBlank() && value.length <= 64 && value.matches(Regex("[A-Za-z0-9-]+"))) {
            "Project repository ID is invalid"
        }
        return value
    }

    private fun validateDisplayName(value: String): String {
        require(value.isNotBlank() && value.length <= 255) { "Project display name must contain 1–255 characters" }
        return value
    }

    private fun validateFormat(value: String): String {
        require(value == FORMAT_ECPROJ || value == FORMAT_ECPROJX) { "Unsupported canonical project format: $value" }
        return value
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val FORMAT_ECPROJ = "ecproj"
        const val FORMAT_ECPROJX = "ecprojx"
        private const val SIDECAR_FORMAT = "effectcraft-android-project-repository"
        private const val XML_ROOT = "EffectCraftProject"
    }
}
