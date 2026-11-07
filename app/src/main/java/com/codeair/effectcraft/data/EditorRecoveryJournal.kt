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
 * over the exact document string (plus the session string when session
 * state is present). A checksum mismatch, a truncated file, a format or
 * version mismatch, or a document that fails project validation
 * means the journal is torn or tampered with: it is deleted and recovery
 * returns null, leaving the committed draft untouched. A leftover write
 * temp is deleted during recovery without touching the last complete journal.
 *
 * What this journal covers beyond the document: [EditorSessionState] —
 * both draft undo/redo stacks, the current draft selection and playhead,
 * and both canonical undo/redo stacks plus the canonical selection — is
 * stored in the same envelope and covered by the same checksum and the
 * same atomic fsync-then-rename write, so document and session state are
 * never torn apart from each other. A journal written before session
 * state existed has no session field and recovers with [EditorSessionState.EMPTY].
 */
class EditorRecoveryJournal(private val root: File) {
    private data class Parsed(val projectId: String, val document: ProjectDocument, val state: EditorSessionState)

    /** A recovered journal entry: the document plus the session state stored with it. */
    data class RecoveredSession(val document: ProjectDocument, val state: EditorSessionState)

    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument) = write(project, null)

    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument, sessionState: EditorSessionState?) {
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create the recovery journal directory")
        if (!root.isDirectory) throw IOException("Recovery journal path is not a directory")
        val documentText = encode(project).toString()
        val sessionText = sessionState?.let { encodeSession(it).toString() } ?: ""
        val envelope = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", project.id)
            put("updatedAtEpochMillis", project.updatedAtEpochMillis)
            put("checksum", checksumFor(documentText, sessionText, hasSession = sessionState != null))
            // Stored as a string, not a nested object, so the checksum is
            // verified over the exact bytes that were written: re-serializing
            // a parsed object could reorder keys and break the check.
            put("document", documentText)
            if (sessionState != null) put("session", sessionText)
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
    fun recover(projectId: String, committed: ProjectDocument?): ProjectDocument? =
        recoverSession(projectId, committed)?.document

    /**
     * Like [recover], but also returns the session state journaled with the
     * document (undo/redo stacks, selection, playhead), validated against
     * the recovered document. A journal written before session state
     * existed recovers with [EditorSessionState.EMPTY].
     */
    @Synchronized
    fun recoverSession(projectId: String, committed: ProjectDocument?): RecoveredSession? {
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
        return RecoveredSession(parsed.document, parsed.state.validatedAgainst(parsed.document))
    }

    @Synchronized
    fun recoverNewest(): ProjectDocument? = recoverNewestSession()?.document

    /**
     * Like [recoverNewest], but also returns the session state journaled
     * with the newest document, validated against that document.
     */
    @Synchronized
    fun recoverNewestSession(): RecoveredSession? {
        if (!root.isDirectory) return null
        var newest: RecoveredSession? = null
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .orEmpty()
            .forEach { file ->
                val parsed = parse(file)
                if (parsed == null) {
                    file.delete()
                } else if (newest == null || parsed.document.updatedAtEpochMillis > newest!!.document.updatedAtEpochMillis) {
                    newest = RecoveredSession(parsed.document, parsed.state.validatedAgainst(parsed.document))
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
        val hasSession = envelope.has("session")
        val sessionText = if (hasSession) envelope.getString("session") else ""
        require(envelope.getString("checksum") == checksumFor(documentText, sessionText, hasSession)) { "Journal checksum mismatch" }
        val document = decode(JSONObject(documentText))
        // A session that fails to decode must not kill document recovery:
        // the checksum already proved the bytes are as written, so a bad
        // session is a writer bug, not torn storage; fall back to empty state.
        val state = if (hasSession) decodeSession(sessionText) else EditorSessionState.EMPTY
        Parsed(projectId, document, state)
    }.getOrNull()

    /**
     * Session-state checksum. Journals written before session state existed
     * carry no session field and keep the original document-only checksum,
     * so they still verify; journals with session state checksum the
     * document and session strings together, so tampering with either is torn.
     */
    private fun checksumFor(documentText: String, sessionText: String, hasSession: Boolean): String =
        if (hasSession) "$documentText\n$sessionText".sha256() else documentText.sha256()

    private fun encodeSession(state: EditorSessionState): JSONObject = JSONObject().apply {
        fun snapshots(stack: List<EditorSessionSnapshot>) = JSONArray().apply {
            stack.forEach { snapshot ->
                put(JSONObject().apply {
                    put("document", encode(snapshot.document).toString())
                    put("selectedLayerId", snapshot.selectedLayerId)
                    put("playheadFrame", snapshot.playheadFrame)
                })
            }
        }
        fun canonicalSnapshots(stack: List<CanonicalEditSnapshot>) = JSONArray().apply {
            stack.forEach { snapshot ->
                put(JSONObject().apply {
                    put("projectJson", snapshot.projectJson)
                    put("selectedLayerIds", JSONArray().apply {
                        snapshot.selectedLayerIds.forEach { put(it) }
                    })
                })
            }
        }
        put("undo", snapshots(state.undoStack))
        put("redo", snapshots(state.redoStack))
        put("selectedLayerId", state.selectedLayerId)
        put("playheadFrame", state.playheadFrame)
        put("canonicalUndo", canonicalSnapshots(state.canonicalUndoStack))
        put("canonicalRedo", canonicalSnapshots(state.canonicalRedoStack))
        put("canonicalSelectedIds", JSONArray().apply {
            state.canonicalSelectedIds.forEach { put(it) }
        })
    }

    private fun decodeSession(sessionText: String): EditorSessionState = runCatching {
        val json = JSONObject(sessionText)
        fun snapshots(key: String): List<EditorSessionSnapshot> = buildList {
            val array = json.optJSONArray(key) ?: JSONArray()
            for (index in 0 until array.length()) {
                runCatching {
                    val item = array.getJSONObject(index)
                    add(
                        EditorSessionSnapshot(
                            document = decode(JSONObject(item.getString("document"))),
                            selectedLayerId = item.optString("selectedLayerId", ""),
                            playheadFrame = item.optInt("playheadFrame", 0)
                        )
                    )
                }
            }
        }
        fun canonicalSnapshots(key: String): List<CanonicalEditSnapshot> = buildList {
            val array = json.optJSONArray(key) ?: JSONArray()
            for (index in 0 until array.length()) {
                runCatching {
                    val item = array.getJSONObject(index)
                    val ids = item.optJSONArray("selectedLayerIds") ?: JSONArray()
                    add(
                        CanonicalEditSnapshot(
                            projectJson = item.getString("projectJson"),
                            selectedLayerIds = buildSet {
                                for (i in 0 until ids.length()) add(ids.optLong(i))
                            }
                        )
                    )
                }
            }
        }
        val canonicalIds = json.optJSONArray("canonicalSelectedIds") ?: JSONArray()
        EditorSessionState(
            undoStack = snapshots("undo"),
            redoStack = snapshots("redo"),
            selectedLayerId = json.optString("selectedLayerId", ""),
            playheadFrame = json.optInt("playheadFrame", 0),
            canonicalUndoStack = canonicalSnapshots("canonicalUndo"),
            canonicalRedoStack = canonicalSnapshots("canonicalRedo"),
            canonicalSelectedIds = buildSet {
                for (i in 0 until canonicalIds.length()) add(canonicalIds.optLong(i))
            }
        )
    }.getOrDefault(EditorSessionState.EMPTY)

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
        put("audioTracks", AudioTrackJson.encode(project.audioTracks))
        put("layers", JSONArray().apply {
            project.layers.forEach { layer ->
                put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("kind", layer.kind)
                    put("opacity", layer.opacity.toDouble())
                    put("tint", layer.tint)
                    put("visible", layer.visible)
                    put("inFrame", layer.inFrame)
                    if (layer.outFrame != null) put("outFrame", layer.outFrame)
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
                        inFrame = layer.optInt("inFrame", 0),
                        outFrame = layer.optInt("outFrame", -1).takeIf { it >= 0 },
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
            audioTracks = AudioTrackJson.decode(json.optJSONArray("audioTracks")),
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
