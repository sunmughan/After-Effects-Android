package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable per-project record of which offline assets a canonical project uses.
 *
 * This is the manifest half of the M2 cluster: [OfflineAssetStore] owns the
 * content-addressed payloads, [OfflineAssetReferences] remains the legacy
 * single-project mapping for the currently cached document, and this manifest
 * is the multi-project record that garbage collection and missing-media
 * recovery read. One manifest belongs to one repository project id.
 *
 * An entry is either a single asset (one SHA-256) or an image sequence
 * (ordered frames, each deduplicated through the store). `missing` records
 * that the item's media could not be found at last check; relinking clears it.
 * Linking is idempotent: linking the same item to the same asset twice leaves
 * the serialized manifest unchanged.
 */
class ProjectAssetManifest(val projectId: String) {
    enum class Kind { SINGLE, SEQUENCE }

    data class Frame(val name: String, val sha256: String)

    data class Entry(
        val itemId: Long,
        val kind: Kind,
        val sha256: String?,
        val frames: List<Frame>,
        val missing: Boolean,
        val lastKnownPath: String?
    ) {
        /** Every payload hash this entry depends on, in entry order. */
        fun hashes(): List<String> = when (kind) {
            Kind.SINGLE -> listOfNotNull(sha256)
            Kind.SEQUENCE -> frames.map { it.sha256 }
        }
    }

    private val entriesById = mutableMapOf<Long, Entry>()

    init {
        validateProjectId(projectId)
    }

    fun link(itemId: Long, sha256: String, lastKnownPath: String? = null): Entry {
        val id = validateItemId(itemId)
        val hash = validateHash(sha256)
        val path = validatePath(lastKnownPath)
        val entry = Entry(id, Kind.SINGLE, hash, emptyList(), false, path)
        entriesById[id] = entry
        return entry
    }

    fun linkSequence(itemId: Long, frames: List<Frame>, lastKnownPath: String? = null): Entry {
        val id = validateItemId(itemId)
        require(frames.isNotEmpty()) { "An image sequence needs at least one frame" }
        val validated = frames.map { Frame(validateFrameName(it.name), validateHash(it.sha256)) }
        require(validated.map { it.name }.toSet().size == validated.size) {
            "Image sequence frame names must be unique"
        }
        val entry = Entry(id, Kind.SEQUENCE, null, validated, false, validatePath(lastKnownPath))
        entriesById[id] = entry
        return entry
    }

    fun entryFor(itemId: Long): Entry? = entriesById[validateItemId(itemId)]

    fun entries(): List<Entry> = entriesById.values.sortedBy { it.itemId }

    fun remove(itemId: Long): Boolean = entriesById.remove(validateItemId(itemId)) != null

    /** Marks an entry missing; returns false when the item has no entry. */
    fun markMissing(itemId: Long): Boolean {
        val current = entriesById[validateItemId(itemId)] ?: return false
        entriesById[itemId] = current.copy(missing = true)
        return true
    }

    /** Clears the missing flag; returns false when the item has no entry. */
    fun markPresent(itemId: Long): Boolean {
        val current = entriesById[validateItemId(itemId)] ?: return false
        entriesById[itemId] = current.copy(missing = false)
        return true
    }

    fun missingEntries(): List<Entry> = entries().filter { it.missing }

    fun allHashes(): Set<String> = entries().flatMap { it.hashes() }.toSet()

    /**
     * Rewrites every reference to [oldHash] as [newHash] and returns how many
     * entries changed. An entry counts once even when several of its sequence
     * frames pointed at the old hash. The old payload itself is never deleted
     * here: the native document path may still point at it, so garbage
     * collection owns deletion once nothing references it.
     */
    fun replaceAsset(oldHash: String, newHash: String): Int {
        val old = validateHash(oldHash)
        val fresh = validateHash(newHash)
        var changed = 0
        entriesById.forEach { (id, entry) ->
            val updated = when (entry.kind) {
                Kind.SINGLE -> if (entry.sha256 == old) entry.copy(sha256 = fresh) else null
                Kind.SEQUENCE -> {
                    val frames = entry.frames.map { if (it.sha256 == old) it.copy(sha256 = fresh) else it }
                    if (frames != entry.frames) entry.copy(frames = frames) else null
                }
            }
            if (updated != null) {
                entriesById[id] = updated
                changed++
            }
        }
        return changed
    }

    /** Restores an entry captured before an operation, or removes it when it did not exist. */
    fun restore(previous: Entry?) {
        if (previous == null) return
        entriesById[previous.itemId] = previous
    }

    fun restoreAbsent(itemId: Long) {
        entriesById.remove(validateItemId(itemId))
    }

    fun toJson(): String {
        val document = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", projectId)
            put("entries", JSONArray().apply {
                entries().forEach { entry ->
                    put(JSONObject().apply {
                        put("itemId", entry.itemId)
                        put("kind", entry.kind.name.lowercase())
                        put("missing", entry.missing)
                        if (entry.lastKnownPath != null) put("lastKnownPath", entry.lastKnownPath)
                        when (entry.kind) {
                            Kind.SINGLE -> put("sha256", entry.sha256)
                            Kind.SEQUENCE -> put("frames", JSONArray().apply {
                                entry.frames.forEach { frame ->
                                    put(JSONObject().put("name", frame.name).put("sha256", frame.sha256))
                                }
                            })
                        }
                    })
                }
            })
        }
        return document.toString()
    }

    companion object {
        private const val FORMAT = "effectcraft-android-asset-manifest"
        private const val VERSION = 1
        private val HASH = Regex("[a-f0-9]{64}")

        fun fromJson(json: String): ProjectAssetManifest {
            try {
                val document = JSONObject(json)
                require(document.optString("format") == FORMAT) { "Unsupported asset manifest format" }
                require(document.optInt("version", -1) == VERSION) { "Unsupported asset manifest version" }
                val manifest = ProjectAssetManifest(document.getString("projectId"))
                val array = document.getJSONArray("entries")
                for (index in 0 until array.length()) {
                    val raw = array.getJSONObject(index)
                    val itemId = raw.getLong("itemId")
                    require(!manifest.entriesById.containsKey(itemId)) { "Duplicate manifest entry for item $itemId" }
                    val missing = raw.optBoolean("missing", false)
                    val path = raw.optString("lastKnownPath").takeIf { it.isNotEmpty() }?.also(::validatePath)
                    when (raw.getString("kind")) {
                        "single" -> manifest.entriesById[itemId] = Entry(
                            itemId, Kind.SINGLE, validateHash(raw.getString("sha256")),
                            emptyList(), missing, path
                        )
                        "sequence" -> {
                            val framesJson = raw.getJSONArray("frames")
                            val frames = (0 until framesJson.length()).map { frameIndex ->
                                val frame = framesJson.getJSONObject(frameIndex)
                                Frame(validateFrameName(frame.getString("name")), validateHash(frame.getString("sha256")))
                            }
                            require(frames.isNotEmpty()) { "An image sequence needs at least one frame" }
                            require(frames.map { it.name }.toSet().size == frames.size) {
                                "Image sequence frame names must be unique"
                            }
                            manifest.entriesById[itemId] = Entry(itemId, Kind.SEQUENCE, null, frames, missing, path)
                        }
                        else -> error("Unknown manifest entry kind")
                    }
                }
                return manifest
            } catch (failure: Exception) {
                if (failure is IOException) throw failure
                throw IOException("Unable to read asset manifest: ${failure.message ?: "invalid document"}", failure)
            }
        }

        fun validateProjectId(value: String): String {
            require(value.isNotBlank() && value.length <= 256) { "Project ID must contain 1–256 characters" }
            return value
        }

        fun validateItemId(value: Long): Long {
            require(value > 0) { "Footage item ID must be positive" }
            return value
        }

        fun validateHash(value: String): String {
            require(value.matches(HASH)) { "Asset ID must be a lowercase SHA-256 digest" }
            return value
        }

        fun validateFrameName(value: String): String {
            require(value.isNotBlank() && value.length <= 255) { "Sequence frame name must contain 1–255 characters" }
            return value
        }

        fun validatePath(value: String?): String? {
            if (value != null) require(value.length <= 2048) { "Last-known media path is too long" }
            return value
        }
    }
}

/**
 * File-backed store for per-project manifests. Files are named by the SHA-256
 * of the project id (same pattern as [ProjectKeyframeStore]) so arbitrary
 * project ids never become filesystem paths. Writes are atomic replacements.
 */
class ProjectAssetManifestStore(
    private val root: File,
    private val replaceFile: (source: File, target: File) -> Unit = ::atomicReplaceManifest
) {
    // Instance monitors cannot coordinate a save with a rollback-capable batch
    // in another store instance. Serialize all operations sharing this root.
    private val lock = locks.computeIfAbsent(root.absoluteFile.toPath().normalize().toString()) { Any() }
    fun load(projectId: String): ProjectAssetManifest = synchronized(lock) {
        val id = ProjectAssetManifest.validateProjectId(projectId)
        val file = fileFor(id)
        if (!file.isFile) return ProjectAssetManifest(id)
        return ProjectAssetManifest.fromJson(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(manifest: ProjectAssetManifest) {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create asset manifest directory")
        if (!root.isDirectory) throw IOException("Asset manifest path is not a directory")
        val target = fileFor(manifest.projectId)
        // Unique staging files allow independent store instances/processes to
        // save the same project without sharing or truncating a fixed ".tmp".
        // Sync the complete JSON before publishing it; readers see either the
        // previous manifest or the new one, never a partially-written file.
        val temporary = File(root, "${target.name}.tmp-${java.util.UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Only lack of atomic-move support permits the fallback. Real
                // I/O/permission failures must propagate rather than be retried
                // as a potentially destructive non-atomic replacement.
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun delete(projectId: String): Boolean = synchronized(lock) {
        val file = fileFor(ProjectAssetManifest.validateProjectId(projectId))
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("Unable to remove asset manifest")
        return true
    }

    /** Project ids with a readable manifest on disk; corrupt files are skipped, not fatal. */
    fun projectIds(): List<String> = synchronized(lock) {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file -> runCatching { ProjectAssetManifest.fromJson(file.readText(Charsets.UTF_8)).projectId }.getOrNull() }
            .sorted()
    }

    /**
     * Returns the complete media-reference union for garbage collection.
     *
     * Unlike [projectIds], this safety-critical scan fails closed: a corrupt
     * or misnamed manifest must never be silently omitted, because doing so
     * could make a still-referenced asset appear unreferenced and delete it.
     */
    @Throws(IOException::class)
    fun allReferencedHashes(): Set<String> = synchronized(lock) {
        if (!root.isDirectory) return emptySet()
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: throw IOException("Unable to enumerate project asset manifests")
        val hashes = linkedSetOf<String>()
        files.sortedBy { it.name }.forEach { file ->
            val manifest = try {
                ProjectAssetManifest.fromJson(file.readText(Charsets.UTF_8))
            } catch (failure: Exception) {
                throw IOException(
                    "Refusing media garbage collection: corrupt project asset manifest ${file.name}",
                    failure
                )
            }
            val expectedName = "${manifest.projectId.sha256()}.json"
            if (file.name != expectedName) {
                throw IOException(
                    "Refusing media garbage collection: manifest filename does not match project ID in ${file.name}"
                )
            }
            hashes += manifest.allHashes()
        }
        return hashes
    }

    /**
     * Rewrites [oldHash] as [newHash] across every project manifest as a
     * rollback-capable batch. All manifests are parsed and all replacement
     * bytes are staged before the first live file is moved. If a later move
     * fails, already-published files are restored from backups in reverse
     * order; a failed rollback is attached as a suppressed exception rather
     * than being hidden.
     */
    @Throws(IOException::class)
    fun replaceAssetEverywhere(oldHash: String, newHash: String): Int = synchronized(lock) {
        val old = ProjectAssetManifest.validateHash(oldHash)
        val fresh = ProjectAssetManifest.validateHash(newHash)
        require(old != fresh) { "Replacement asset must differ from the original asset" }
        if (!root.isDirectory) return 0

        // Safety-critical scan validates every manifest and its project-id
        // filename before computing or staging any migration.
        allReferencedHashes()
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: throw IOException("Unable to enumerate project asset manifests")
        val replacements = linkedMapOf<File, String>()
        var changed = 0
        files.sortedBy { it.name }.forEach { file ->
            val manifest = ProjectAssetManifest.fromJson(file.readText(Charsets.UTF_8))
            val count = manifest.replaceAsset(old, fresh)
            if (count > 0) {
                replacements[file] = manifest.toJson()
                changed += count
            }
        }
        if (replacements.isEmpty()) return 0

        val token = java.util.UUID.randomUUID().toString()
        val staged = linkedMapOf<File, File>()
        val backups = linkedMapOf<File, File>()
        val published = mutableListOf<File>()
        val preserveBackups = mutableSetOf<File>()
        try {
            // Stage every complete new manifest and every backup before writes.
            replacements.forEach { (target, json) ->
                val stage = File(root, "${target.name}.stage-$token")
                val backup = File(root, "${target.name}.backup-$token")
                staged[target] = stage
                backups[target] = backup
                java.io.FileOutputStream(stage).use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            staged.forEach { (target, stage) ->
                replaceFile(stage, target)
                published += target
            }
            return changed
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Exception>()
            published.asReversed().forEach { target ->
                val backup = backups[target] ?: return@forEach
                val rollback = File(root, "${target.name}.rollback-$token")
                try {
                    Files.copy(backup.toPath(), rollback.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    replaceFile(rollback, target)
                } catch (rollbackFailure: Exception) {
                    rollbackFailures += rollbackFailure
                    preserveBackups += backup
                } finally {
                    rollback.delete()
                }
            }
            rollbackFailures.forEach(failure::addSuppressed)
            throw IOException("Unable to replace asset references transactionally", failure)
        } finally {
            staged.values.forEach(File::delete)
            backups.values.filterNot { it in preserveBackups }.forEach(File::delete)
        }
    }

    private fun fileFor(projectId: String): File = File(root, "${projectId.sha256()}.json")

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}


/** Same-directory replacement keeps each manifest move atomic where supported. */
private fun atomicReplaceManifest(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
