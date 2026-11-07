package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable autosave journal for unsaved editor edits.
 *
 * What is journaled: one complete [ProjectDocument] snapshot per project, the
 * latest unsaved editor state. The editor writes it on every project change;
 * an explicit save discards it, so a journal only ever represents edits that
 * have not reached the committed draft yet.
 *
 * When it is written: by the editor on each project change, through an atomic
 * temp file that is fsynced before rename, exactly like the draft and
 * keyframe stores. A crash during the write therefore leaves either the
 * previous complete journal or a leftover temp, never a half-promoted journal.
 *
 * How recovery replays or discards it: [recover] returns the journaled
 * document only when it is strictly newer than the committed draft; the
 * caller uses it as the working project and the committed draft stays
 * untouched until the next explicit save. A journal that is older than or
 * equal to the committed draft is stale and is deleted. [recoverNewest]
 * covers a project that was never saved at all. Recovery never writes the
 * committed draft: replay is in-memory restoration, and only an explicit
 * save persists it.
 *
 * How a torn journal is handled: the envelope carries a SHA-256 checksum
 * over the exact document string. A checksum mismatch, a truncated file, a
 * format or version mismatch, or a document that fails project validation
 * means the journal is torn or tampered with: it is deleted and recovery
 * returns null, leaving the committed draft untouched. A leftover write
 * temp is deleted during recovery without touching the last complete journal.
 *
 * What this journal does not cover: undo/redo history, selection, playhead,
 * and keyframe tracks are in-memory or separately stored state and are not
 * journaled. After recovery the document is intact but the undo stack is empty.
 */
class EditorRecoveryJournal(private val root: File) {
    private data class Parsed(val projectId: String, val document: ProjectDocument)

    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument) {
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create the recovery journal directory")
        if (!root.isDirectory) throw IOException("Recovery journal path is not a directory")
        val documentText = encode(project).toString()
        val envelope = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", project.id)
            put("updatedAtEpochMillis", project.updatedAtEpochMillis)
            put("checksum", documentText.sha256())
            // Stored as a string, not a nested object, so the checksum is
            // verified over the exact bytes that were written: re-serializing
            // a parsed object could reorder keys and break the check.
            put("document", documentText)
        }
        val target = fileFor(project.id)
        val temporary = File(root, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Returns the journaled document when it is strictly newer than
     * [committed], or null when there is no journal, it is torn, or it is
     * stale. Torn and stale journals are deleted. A recovered journal is
     * kept until [discard] so a second crash before an explicit save can
     * still recover the same edits.
     */
    @Synchronized
    fun recover(projectId: String, committed: ProjectDocument?): ProjectDocument? {
        discardLeftoverTemp(projectId)
        val file = fileFor(projectId)
        if (!file.isFile) return null
        val parsed = parse(file) ?: run {
            file.delete()
            return null
        }
        if (parsed.projectId != projectId || parsed.document.id != projectId) {
            file.delete()
            return null
        }
        if (committed != null) {
            // Wall-clock timestamps have millisecond precision. Two edits can
            // legitimately share a timestamp, so equality alone must not hide
            // a different journaled document after process death.
            if (parsed.document.updatedAtEpochMillis < committed.updatedAtEpochMillis ||
                parsed.document == committed
            ) {
                file.delete()
                return null
            }
        }
        return parsed.document
    }

    /**
     * Returns the newest valid journaled document across projects, deleting
     * torn journals as they are found. The editor uses this when no committed
     * draft exists, or when the newest journal is newer than the latest
     * committed draft, so a never-saved project still survives process death.
     */
    @Synchronized
    fun recoverNewest(): ProjectDocument? {
        if (!root.isDirectory) return null
        var newest: ProjectDocument? = null
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .orEmpty()
            .forEach { file ->
                val parsed = parse(file)
                if (parsed == null) {
                    file.delete()
                } else if (newest == null || parsed.document.updatedAtEpochMillis > newest!!.updatedAtEpochMillis) {
                    newest = parsed.document
                }
            }
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        return newest
    }

    /** Removes the journal for [projectId], as after an explicit save. */
    @Synchronized
    fun discard(projectId: String) {
        fileFor(projectId).delete()
        discardLeftoverTemp(projectId)
    }

    /**
     * Removes journals and write temps for every project except [projectId].
     * The editor calls this when the working project changes so a stale
     * journal for an abandoned session can never resurrect later.
     */
    @Synchronized
    fun discardAllExcept(projectId: String) {
        if (!root.isDirectory) return
        val keep = fileFor(projectId).name
        root.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.endsWith(SUFFIX) && file.name != keep) file.delete()
            if (file.name.endsWith(".tmp") && file.name != "$keep.tmp") file.delete()
        }
    }

    private fun discardLeftoverTemp(projectId: String) {
        File(root, "${fileFor(projectId).name}.tmp").delete()
    }

    private fun parse(file: File): Parsed? = runCatching {
        val envelope = JSONObject(file.readText(Charsets.UTF_8))
        require(envelope.optString("format") == FORMAT) { "Unsupported journal format" }
        require(envelope.optInt("version", -1) == VERSION) { "Unsupported journal version" }
        val projectId = envelope.getString("projectId")
        val documentText = envelope.getString("document")
        require(envelope.getString("checksum") == documentText.sha256()) { "Journal checksum mismatch" }
        Parsed(projectId, decode(JSONObject(documentText)))
    }.getOrNull()

    private fun fileFor(projectId: String): File = File(root, "${projectId.sha256()}$SUFFIX")

    private fun encode(project: ProjectDocument): JSONObject = JSONObject().apply {
        put("format", "effectcraft-android-draft")
        put("formatVersion", 1)
        put("id", project.id)
        put("name", project.name)
        put("width", project.width)
        put("height", project.height)
        put("frameRate", project.frameRate)
        put("durationFrames", project.durationFrames)
        put("updatedAtEpochMillis", project.updatedAtEpochMillis)
        put("layers", JSONArray().apply {
            project.layers.forEach { layer ->
                put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("kind", layer.kind)
                    put("opacity", layer.opacity.toDouble())
                    put("tint", layer.tint)
                    put("visible", layer.visible)
                    put("clips", ClipJson.encode(layer.clips))
                })
            }
        })
    }

    private fun decode(json: JSONObject): ProjectDocument {
        require(json.optString("format") == "effectcraft-android-draft") { "Unsupported draft format" }
        require(json.optInt("formatVersion") == 1) { "Unsupported draft version" }
        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = buildList {
            for (index in 0 until layersJson.length()) {
                val layer = layersJson.getJSONObject(index)
                add(
                    LayerDocument(
                        id = layer.getString("id"),
                        name = layer.getString("name"),
                        kind = layer.getString("kind"),
                        opacity = layer.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                        tint = layer.optLong("tint", 0xFF9278F6),
                        visible = layer.optBoolean("visible", true),
                        clips = ClipJson.decode(layer.optJSONArray("clips"))
                    )
                )
            }
        }
        val project = ProjectDocument(
            id = json.getString("id"),
            name = json.optString("name", "Untitled Composition"),
            width = json.optInt("width", 1080),
            height = json.optInt("height", 1920),
            frameRate = json.optInt("frameRate", 30),
            durationFrames = json.optInt("durationFrames", 150),
            layers = layers,
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis", 0L)
        )
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        return project
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FORMAT = "effectcraft-android-recovery-journal"
        private const val VERSION = 1
        private const val SUFFIX = ".journal"
    }
}
