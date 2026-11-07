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
 * Envelope version 2: the document plus the canonical undo/redo history
 * ([CanonicalEditSnapshot] entries, bounded to [CanonicalEditHistory.MAX_ENTRIES]),
 * the canonical layer selection, and the playhead frame, all covered by one
 * SHA-256 checksum over the exact stored strings ([checksumV2]). When the
 * caller also journals the full [EditorSessionState] — both draft undo/redo
 * stacks, the current draft selection and playhead, and both canonical
 * stacks plus the canonical selection — it is stored as an extra `session`
 * field, also covered by the checksum, and the v2 history fields are
 * derived from that same state, so the two views of one journal never
 * disagree. A journal written before session state existed has no session
 * field and recovers with the state derived from the v2 fields.
 *
 * How recovery replays or discards it: [recoverFull] returns the journaled
 * document with its undo/redo stacks, selection, and playhead only when the
 * document is strictly newer than the committed draft; [recoverSession]
 * additionally returns the full session state (draft stacks included when
 * the journal carries them), validated against the recovered document. The
 * caller restores all of it in memory and the committed draft stays
 * untouched until the next explicit save. A journal that is older than or
 * equal to the committed draft is stale and is deleted. [recoverNewestFull]
 * and [recoverNewestSession] cover a project that was never saved at all.
 * Recovery never writes the committed draft: replay is in-memory
 * restoration, and only an explicit save persists it. A recovered journal
 * is kept until [discard] so a second crash before an explicit save can
 * still recover the same edits.
 *
 * How a torn journal is handled: the envelope carries a SHA-256 checksum.
 * Version 1 covered the document string only (or document plus session,
 * for journals written with session state); version 2 covers the document
 * string plus the exact stored undo text, redo text, selection text, and
 * playhead frame — and the session text when one is stored — so tampering
 * with any journaled field is detected. A checksum mismatch, a truncated
 * file, a format or version mismatch, a missing v2 field, a history entry
 * whose project source is not a JSON object, a negative playhead, or a
 * document that fails project validation means the journal is torn or
 * tampered with: it is deleted and recovery returns null, leaving the
 * committed draft untouched. A leftover write temp is deleted during
 * recovery without touching the last complete journal.
 *
 * Version 1 journals stay readable: [parse] migrates them on read, returning
 * the document with empty undo/redo stacks, an empty selection, and playhead
 * frame 0 (plus the decoded session state when a v1 journal carries one),
 * since v1 never stored the v2 history fields. All new writes use version 2.
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

    /** A recovered journal entry: the document plus the session state stored with it. */
    data class RecoveredSession(val document: ProjectDocument, val state: EditorSessionState)

    private data class Parsed(
        val projectId: String,
        val document: ProjectDocument,
        val state: EditorSessionState,
        val full: RestoredEditorState
    )

    /** Writes the document with empty history, selection, and playhead 0. */
    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument) {
        writeFull(project, emptyList(), emptyList(), emptySet(), 0)
    }

    /**
     * Writes the document together with the full session state: both draft
     * undo/redo stacks, the current draft selection and playhead, and both
     * canonical stacks plus the canonical selection. The canonical parts
     * are also stored as the v2 history fields, so [recoverFull] works on
     * the same journal. A null [sessionState] writes the document with
     * empty history, like [write].
     */
    @Synchronized
    @Throws(IOException::class)
    fun write(project: ProjectDocument, sessionState: EditorSessionState?) {
        if (sessionState == null) {
            writeFull(project, emptyList(), emptyList(), emptySet(), 0)
            return
        }
        writeEnvelope(
            project = project,
            undoStack = sessionState.canonicalUndoStack,
            redoStack = sessionState.canonicalRedoStack,
            selectedLayerIds = sessionState.canonicalSelectedIds,
            playheadFrame = sessionState.playheadFrame,
            sessionText = encodeSession(sessionState).toString()
        )
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
        writeEnvelope(project, undoStack, redoStack, selectedLayerIds, playheadFrame, sessionText = null)
    }

    @Throws(IOException::class)
    private fun writeEnvelope(
        project: ProjectDocument,
        undoStack: List<CanonicalEditSnapshot>,
        redoStack: List<CanonicalEditSnapshot>,
        selectedLayerIds: Set<Long>,
        playheadFrame: Int,
        sessionText: String?
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
            put("checksum", checksumV2(documentText, undoText, redoText, selectionText, playheadFrame, sessionText))
            // Stored as strings, not nested objects, so the checksum is
            // verified over the exact bytes that were written: re-serializing
            // a parsed object could reorder keys and break the check.
            put("document", documentText)
            put("undoStack", undoText)
            put("redoStack", redoText)
            put("selection", selectionText)
            put("playheadFrame", playheadFrame)
            if (sessionText != null) put("session", sessionText)
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
     * stale. Torn and stale journals are deleted. See [recoverFull] and
     * [recoverSession] for the full restored state.
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
        return parsed.full
    }

    /**
     * Like [recover], but also returns the session state journaled with the
     * document (undo/redo stacks, selection, playhead), validated against
     * the recovered document. A journal written without session state
     * recovers with the state derived from its v2 history fields (empty
     * draft stacks); a journal written before session state existed
     * recovers with [EditorSessionState.EMPTY].
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
            if (parsed.document.updatedAtEpochMillis < committed.updatedAtEpochMillis ||
                parsed.document == committed
            ) {
                file.delete()
                return null
            }
        }
        return RecoveredSession(parsed.document, parsed.state.validatedAgainst(parsed.document))
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
                } else if (newest == null || parsed.document.updatedAtEpochMillis > newest!!.document.updatedAtEpochMillis) {
                    newest = parsed.full
                }
            }
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        return newest
    }

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
        val version = envelope.optInt("version", -1)
        require(version == 1 || version == VERSION) { "Unsupported journal version" }
        val projectId = envelope.getString("projectId")
        val documentText = envelope.getString("document")
        if (version == 1) {
            // Version 1 checksummed the document string only, or document
            // plus session for journals written with session state, and
            // stored no history: migrate on read with empty editor state
            // (plus the decoded session when one was stored).
            val hasSession = envelope.has("session")
            val sessionText = if (hasSession) envelope.getString("session") else ""
            require(
                envelope.getString("checksum") == checksumFor(documentText, sessionText, hasSession)
            ) { "Journal checksum mismatch" }
            val document = decode(JSONObject(documentText))
            val state = if (hasSession) decodeSession(sessionText) else EditorSessionState.EMPTY
            val full = RestoredEditorState(
                document,
                state.canonicalUndoStack,
                state.canonicalRedoStack,
                state.canonicalSelectedIds,
                state.playheadFrame
            )
            Parsed(projectId, document, state, full)
        } else {
            require(
                envelope.has("undoStack") && envelope.has("redoStack") &&
                    envelope.has("selection") && envelope.has("playheadFrame")
            ) { "Journal v2 envelope is missing history fields" }
            val undoText = envelope.getString("undoStack")
            val redoText = envelope.getString("redoStack")
            val selectionText = envelope.getString("selection")
            val playheadFrame = envelope.getInt("playheadFrame")
            require(playheadFrame >= 0) { "Journal playhead frame is negative" }
            val sessionText = if (envelope.has("session")) envelope.getString("session") else null
            require(
                envelope.getString("checksum") ==
                    checksumV2(documentText, undoText, redoText, selectionText, playheadFrame, sessionText)
            ) { "Journal checksum mismatch" }
            val document = decode(JSONObject(documentText))
            val undoStack = decodeSnapshots(JSONArray(undoText))
            val redoStack = decodeSnapshots(JSONArray(redoText))
            val selectionArray = JSONArray(selectionText)
            val selection = (0 until selectionArray.length()).map { selectionArray.getLong(it) }.toSet()
            val full = RestoredEditorState(document, undoStack, redoStack, selection, playheadFrame)
            // A session that fails to decode must not kill document recovery:
            // the checksum already proved the bytes are as written, so a bad
            // session is a writer bug, not torn storage; fall back to the
            // state derived from the v2 fields.
            val state = if (sessionText != null) {
                decodeSession(sessionText)
            } else {
                EditorSessionState(
                    playheadFrame = playheadFrame,
                    canonicalUndoStack = undoStack,
                    canonicalRedoStack = redoStack,
                    canonicalSelectedIds = selection
                )
            }
            Parsed(projectId, document, state, full)
        }
    }.getOrNull()

    /**
     * Session-state checksum for version 1 journals. Journals written
     * before session state existed carry no session field and keep the
     * original document-only checksum, so they still verify; journals with
     * session state checksum the document and session strings together, so
     * tampering with either is torn.
     */
    private fun checksumFor(documentText: String, sessionText: String, hasSession: Boolean): String =
        if (hasSession) "$documentText\n$sessionText".sha256() else documentText.sha256()

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
     * — or the session text when one is stored — breaks it. A null
     * [sessionText] reproduces the session-less v2 checksum exactly, so
     * journals written without session state verify unchanged.
     */
    private fun checksumV2(
        documentText: String,
        undoText: String,
        redoText: String,
        selectionText: String,
        playheadFrame: Int,
        sessionText: String? = null
    ): String {
        val joined = documentText + "\u0000" + undoText + "\u0000" + redoText + "\u0000" +
            selectionText + "\u0000" + playheadFrame
        return (if (sessionText == null) joined else joined + "\u0000" + sessionText).sha256()
    }

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
        private const val VERSION = 2
        private const val SUFFIX = ".journal"
    }
}
