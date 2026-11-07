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
 * latest unsaved editor state, plus the canonical undo/redo history
 * ([CanonicalEditSnapshot] entries, bounded to [CanonicalEditHistory.MAX_ENTRIES]),
 * the canonical layer selection, and the playhead frame. The editor writes it
 * on every project change; an explicit save discards it, so a journal only
 * ever represents edits that have not reached the committed draft yet.
 *
 * When it is written: by the editor on each project change, through an atomic
 * temp file that is fsynced before rename, exactly like the draft and
 * keyframe stores. A crash during the write therefore leaves either the
 * previous complete journal or a leftover temp, never a half-promoted journal.
 *
 * How recovery replays or discards it: [recoverFull] returns the journaled
 * document with its undo/redo stacks, selection, and playhead only when the
 * document is strictly newer than the committed draft; the caller restores
 * all four in memory and the committed draft stays untouched until the next
 * explicit save. A journal that is older than or equal to the committed
 * draft is stale and is deleted. [recoverNewestFull] covers a project that
 * was never saved at all. Recovery never writes the committed draft: replay
 * is in-memory restoration, and only an explicit save persists it. A
 * recovered journal is kept until [discard] so a second crash before an
 * explicit save can still recover the same edits.
 *
 * How a torn journal is handled: the envelope carries a SHA-256 checksum.
 * Version 1 covered the document string only; version 2 covers the
 * document string plus the exact stored undo text, redo text, selection
 * text, and playhead frame, so tampering with any journaled field is
 * detected. A checksum mismatch, a truncated file, a format or version
 * mismatch, a missing v2 field, a history entry whose project source is
 * not a JSON object, a negative playhead, or a document that fails project
 * validation means the journal is torn or tampered with: it is deleted and
 * recovery returns null, leaving the committed draft untouched. A leftover
 * write temp is deleted during recovery without touching the last complete
 * journal.
 *
 * Version 1 journals stay readable: [parse] migrates them on read, returning
 * the document with empty undo/redo stacks, an empty selection, and playhead
 * frame 0, since v1 never stored those fields. All new writes use version 2.
 *
 * What this journal does not cover: keyframe tracks are in-memory or
 * separately stored state and are not journaled.
 */
class EditorRecoveryJournal(private val root: File) {
    /**
     * Everything one recovery restores: the document plus the editor state
     * that belonged to it. The undo/redo entries are [CanonicalEditSnapshot]
     * values, so undo and redo keep working after restore exactly as they
     * did before the crash: each entry's project source is committed back
     * through the same canonical path the editor uses live.
     */
    data class RestoredEditorState(
        val document: ProjectDocument,
        val undoStack: List<CanonicalEditSnapshot>,
        val redoStack: List<CanonicalEditSnapshot>,
        val selectedLayerIds: Set<Long>,
        val playheadFrame: Int
    )

    private data class Parsed(val projectId: String, val state: RestoredEditorState)

    /** Writes the document with empty history, selection, and playhead 0. */
    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument) {
        writeFull(project, emptyList(), emptyList(), emptySet(), 0)
    }

    /**
     * Writes the document together with its canonical undo/redo history,
     * selection, and playhead frame. The stacks are capped at
     * [CanonicalEditHistory.MAX_ENTRIES] on write, newest entries kept.
     */
    @Synchronized
    @Throws(IOException::class)
    fun writeFull(
        project: ProjectDocument,
        undoStack: List<CanonicalEditSnapshot>,
        redoStack: List<CanonicalEditSnapshot>,
        selectedLayerIds: Set<Long>,
        playheadFrame: Int
    ) {
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        require(playheadFrame >= 0) { "Playhead frame cannot be negative" }
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create the recovery journal directory")
        if (!root.isDirectory) throw IOException("Recovery journal path is not a directory")
        val documentText = encode(project).toString()
        val undoText = encodeSnapshots(undoStack).toString()
        val redoText = encodeSnapshots(redoStack).toString()
        val selectionText = JSONArray().apply { selectedLayerIds.forEach { put(it) } }.toString()
        val envelope = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", project.id)
            put("updatedAtEpochMillis", project.updatedAtEpochMillis)
            put("checksum", checksumV2(documentText, undoText, redoText, selectionText, playheadFrame))
            // Stored as strings, not nested objects, so the checksum is
            // verified over the exact bytes that were written: re-serializing
            // a parsed object could reorder keys and break the check.
            put("document", documentText)
            put("undoStack", JSONArray(undoText))
            put("redoStack", JSONArray(redoText))
            put("selection", JSONArray(selectionText))
            put("playheadFrame", playheadFrame)
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
     * stale. Torn and stale journals are deleted. See [recoverFull] for the
     * full restored state.
     */
    @Synchronized
    fun recover(projectId: String, committed: ProjectDocument?): ProjectDocument? =
        recoverFull(projectId, committed)?.document

    /**
     * Returns the journaled document with its undo/redo stacks, selection,
     * and playhead when it is strictly newer than [committed], or null when
     * there is no journal, it is torn, or it is stale. Torn and stale
     * journals are deleted. A recovered journal is kept until [discard] so
     * a second crash before an explicit save can still recover the same edits.
     */
    @Synchronized
    fun recoverFull(projectId: String, committed: ProjectDocument?): RestoredEditorState? {
        discardLeftoverTemp(projectId)
        val file = fileFor(projectId)
        if (!file.isFile) return null
        val parsed = parse(file) ?: run {
            file.delete()
            return null
        }
        if (parsed.projectId != projectId || parsed.state.document.id != projectId) {
            file.delete()
            return null
        }
        if (committed != null) {
            // Wall-clock timestamps have millisecond precision. Two edits can
            // legitimately share a timestamp, so equality alone must not hide
            // a different journaled document after process death.
            if (parsed.state.document.updatedAtEpochMillis < committed.updatedAtEpochMillis ||
                parsed.state.document == committed
            ) {
                file.delete()
                return null
            }
        }
        return parsed.state
    }

    /**
     * Returns the newest valid journaled document across projects, deleting
     * torn journals as they are found. The editor uses this when no committed
     * draft exists, or when the newest journal is newer than the latest
     * committed draft, so a never-saved project still survives process death.
     */
    @Synchronized
    fun recoverNewest(): ProjectDocument? = recoverNewestFull()?.document

    /**
     * Returns the newest valid journaled state across projects, deleting
     * torn journals as they are found. See [recoverNewest].
     */
    @Synchronized
    fun recoverNewestFull(): RestoredEditorState? {
        if (!root.isDirectory) return null
        var newest: RestoredEditorState? = null
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .orEmpty()
            .forEach { file ->
                val parsed = parse(file)
                if (parsed == null) {
                    file.delete()
                } else if (newest == null || parsed.state.document.updatedAtEpochMillis > newest!!.document.updatedAtEpochMillis) {
                    newest = parsed.state
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
        val version = envelope.optInt("version", -1)
        require(version == 1 || version == VERSION) { "Unsupported journal version" }
        val projectId = envelope.getString("projectId")
        val documentText = envelope.getString("document")
        if (version == 1) {
            // Version 1 checksummed the document string only and stored no
            // history: migrate on read with empty editor state.
            require(envelope.getString("checksum") == documentText.sha256()) { "Journal checksum mismatch" }
            val document = decode(JSONObject(documentText))
            Parsed(projectId, RestoredEditorState(document, emptyList(), emptyList(), emptySet(), 0))
        } else {
            require(
                envelope.has("undoStack") && envelope.has("redoStack") &&
                    envelope.has("selection") && envelope.has("playheadFrame")
            ) { "Journal v2 envelope is missing history fields" }
            val undoText = envelope.getJSONArray("undoStack").toString()
            val redoText = envelope.getJSONArray("redoStack").toString()
            val selectionText = envelope.getJSONArray("selection").toString()
            val playheadFrame = envelope.getInt("playheadFrame")
            require(playheadFrame >= 0) { "Journal playhead frame is negative" }
            require(
                envelope.getString("checksum") ==
                    checksumV2(documentText, undoText, redoText, selectionText, playheadFrame)
            ) { "Journal checksum mismatch" }
            val document = decode(JSONObject(documentText))
            val undoStack = decodeSnapshots(envelope.getJSONArray("undoStack"))
            val redoStack = decodeSnapshots(envelope.getJSONArray("redoStack"))
            val selectionArray = envelope.getJSONArray("selection")
            val selection = (0 until selectionArray.length()).map { selectionArray.getLong(it) }.toSet()
            Parsed(projectId, RestoredEditorState(document, undoStack, redoStack, selection, playheadFrame))
        }
    }.getOrNull()

    private fun encodeSnapshots(stack: List<CanonicalEditSnapshot>): JSONArray = JSONArray().apply {
        stack.takeLast(CanonicalEditHistory.MAX_ENTRIES).forEach { snapshot ->
            put(JSONObject().apply {
                put("projectJson", snapshot.projectJson)
                put("selectedLayerIds", JSONArray().apply {
                    snapshot.selectedLayerIds.forEach { put(it) }
                })
            })
        }
    }

    private fun decodeSnapshots(array: JSONArray): List<CanonicalEditSnapshot> {
        val snapshots = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val projectJson = entry.getString("projectJson")
            require(projectJson.isNotBlank()) { "Journal history entry has no project source" }
            // A history entry is only re-appliable when its source is a JSON
            // object: anything else is torn, not a snapshot to undo into.
            JSONObject(projectJson)
            val idsArray = entry.optJSONArray("selectedLayerIds") ?: JSONArray()
            val ids = (0 until idsArray.length()).map { idsArray.getLong(it) }.toSet()
            CanonicalEditSnapshot(projectJson, ids)
        }
        return snapshots.takeLast(CanonicalEditHistory.MAX_ENTRIES)
    }

    /**
     * Version 2 checksum over the exact stored strings, in field order,
     * joined with a separator that cannot appear in JSON text. Any change
     * to the document, either history stack, the selection, or the playhead
     * breaks it.
     */
    private fun checksumV2(
        documentText: String,
        undoText: String,
        redoText: String,
        selectionText: String,
        playheadFrame: Int
    ): String = (documentText + "\u0000" + undoText + "\u0000" + redoText + "\u0000" +
        selectionText + "\u0000" + playheadFrame).sha256()

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
                        visible = layer.optBoolean("visible", true)
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
        private const val VERSION = 2
        private const val SUFFIX = ".journal"
    }
}
