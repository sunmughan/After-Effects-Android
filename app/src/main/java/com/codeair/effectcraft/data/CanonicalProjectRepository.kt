package com.codeair.effectcraft.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
            ?: throw IOException("Canonical project $checkedId has no readable document")
        val sidecar = sidecarFile(checkedId)
        if (!sidecar.isFile) throw IOException("Canonical project $checkedId has no metadata sidecar")
        val backupDocument = updateBackupDocumentFile(checkedId, previous.format)
        val backupSidecar = updateBackupSidecarFile(checkedId)
        if (backupDocument.exists() || backupSidecar.exists()) {
            throw IOException("Canonical project $checkedId has an unresolved interrupted update")
        }

        // Keep a durable rollback pair before touching the committed files.
        // A process death between document and sidecar promotion otherwise
        // leaves the old digest pointing at new bytes and hides the project.
        writeDurably(backupDocument, previousBytes)
        writeDurably(backupSidecar, sidecar.readText(Charsets.UTF_8))
        val updated = previous.copy(
            documentSha256 = document.sha256(),
            updatedAtMillis = System.currentTimeMillis()
        )
        try {
            writeDocument(checkedId, previous.format, document)
            writeSidecar(updated)
        } catch (failure: Throwable) {
            runCatching { restoreInterruptedUpdate(checkedId, previous.format) }
            throw failure
        }
        // If deletion is interrupted, the next metadata/load/list call sees
        // a consistent new pair and only cleans these redundant backups.
        backupDocument.delete()
        backupSidecar.delete()
        return updated
    }

    @Synchronized
    @Throws(IOException::class)
    fun load(id: String): String? {
        val checkedId = validateId(id)
        val metadata = metadata(checkedId) ?: return null
        val file = documentFile(metadata.id, metadata.format)
        if (!file.isFile) return null
        val document = file.readText(Charsets.UTF_8)
        if (document.sha256() != metadata.documentSha256) {
            throw IOException("Canonical project document integrity check failed")
        }
        return document
    }

    @Synchronized
    fun metadata(id: String): Metadata? {
        val checkedId = validateId(id)
        recoverInterruptedUpdate(checkedId)
        val file = sidecarFile(checkedId)
        if (!file.isFile) return null
        return runCatching { parseSidecar(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** Sidecar-driven listing: newest first, display name breaks timestamp ties. */
    @Synchronized
    fun list(): List<Metadata> {
        if (!root.isDirectory) return emptyList()
        val sidecars = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        sidecars.forEach { file -> runCatching { recoverInterruptedUpdate(file.nameWithoutExtension) } }
        return sidecars
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

    private fun parseSidecar(json: String, verifyDocumentIntegrity: Boolean = true): Metadata {
        val raw = JSONObject(json)
        require(raw.optString("format") == SIDECAR_FORMAT) { "Unsupported project metadata format" }
        val version = if (raw.has("version")) raw.getInt("version") else 0
        val id = validateId(raw.getString("id"))
        val format = raw.optString("projectFormat").takeIf { it.isNotBlank() }
            ?.let(::validateFormat) ?: FORMAT_ECPROJ
        val declaredHash = raw.optString("documentSha256")
        require(declaredHash.isEmpty() || declaredHash.matches(Regex("[a-f0-9]{64}"))) {
            "Canonical project metadata contains an invalid SHA-256"
        }
        val documentFile = documentFile(id, format)
        val actualHash = documentFile.takeIf(File::isFile)
            ?.let { it.readText(Charsets.UTF_8).sha256() }
        // A sidecar is an integrity record, not a cache hint. Never replace a
        // declared digest with the digest of possibly tampered document bytes.
        if (verifyDocumentIntegrity && declaredHash.isNotEmpty() && actualHash != null && declaredHash != actualHash) {
            throw IllegalArgumentException("Canonical project document SHA-256 mismatch")
        }
        return Metadata(
            id = id,
            displayName = raw.optString("displayName").takeIf { it.isNotBlank() } ?: "Imported project",
            format = format,
            schemaVersion = if (version == 0) CURRENT_SCHEMA_VERSION else version,
            // Legacy v0 sidecars had no digest; safely derive one only when the
            // existing document is present. migrateAll() will persist it.
            documentSha256 = declaredHash.ifEmpty { actualHash.orEmpty() },
            updatedAtMillis = raw.optLong("updatedAtMillis", 0L),
            sourceName = raw.optString("sourceName").takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Reconciles a two-file project update after process death. The document
     * is promoted before its digest sidecar; until both agree, the durable
     * previous pair is restored. A matching new pair means the transaction
     * committed and only stale backups need cleanup.
     */
    private fun recoverInterruptedUpdate(id: String) {
        val backupSidecar = updateBackupSidecarFile(id)
        for (format in listOf(FORMAT_ECPROJ, FORMAT_ECPROJX)) {
            val backupDocument = updateBackupDocumentFile(id, format)
            if (!backupDocument.exists() && !backupSidecar.exists()) continue

            val currentPairIsValid = runCatching {
                val metadata = parseSidecar(sidecarFile(id).readText(Charsets.UTF_8))
                val currentDocument = documentFile(id, metadata.format)
                metadata.id == id && metadata.format == format &&
                    currentDocument.isFile &&
                    currentDocument.readText(Charsets.UTF_8).sha256() == metadata.documentSha256
            }.getOrDefault(false)
            if (currentPairIsValid) {
                backupDocument.delete()
                backupSidecar.delete()
                return
            }

            val backupPairIsValid = runCatching {
                // Parse backup metadata independently of the currently promoted
                // document: normal integrity checking intentionally compares
                // against the live file, which is the interrupted new document.
                val metadata = parseSidecar(
                    backupSidecar.readText(Charsets.UTF_8),
                    verifyDocumentIntegrity = false
                )
                metadata.id == id && metadata.format == format &&
                    backupDocument.isFile &&
                    backupDocument.readText(Charsets.UTF_8).sha256() == metadata.documentSha256
            }.getOrDefault(false)
            if (backupPairIsValid) {
                restoreInterruptedUpdate(id, format)
                return
            }
            // Neither pair can be proven consistent. Preserve all bytes for
            // diagnosis rather than deleting a possibly recoverable project.
        }
    }

    private fun restoreInterruptedUpdate(id: String, format: String) {
        val backupDocument = updateBackupDocumentFile(id, format)
        val backupSidecar = updateBackupSidecarFile(id)
        if (!backupDocument.isFile || !backupSidecar.isFile) {
            throw IOException("Canonical project $id rollback pair is incomplete")
        }
        moveReplacing(backupDocument, documentFile(id, format))
        moveReplacing(backupSidecar, sidecarFile(id))
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun updateBackupDocumentFile(id: String, format: String): File =
        File(root, "$id.$format.update-bak")

    private fun updateBackupSidecarFile(id: String): File =
        File(root, "$id.json.update-bak")

    private fun ensureDirectory() {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create canonical project repository")
        if (!root.isDirectory) throw IOException("Canonical project repository path is not a directory")
    }

    private fun writeDocument(id: String, format: String, document: String) {
        ensureDirectory()
        val target = documentFile(id, format)
        val temporary = File(root, "${target.name}.tmp")
        writeDurably(temporary, document)
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
        writeDurably(temporary, document.toString())
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Stable-storage write used before atomic document/sidecar promotion. */
    private fun writeDurably(file: File, content: String) {
        FileOutputStream(file).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
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
