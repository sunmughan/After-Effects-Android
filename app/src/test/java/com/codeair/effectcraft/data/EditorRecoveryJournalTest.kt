package com.codeair.effectcraft.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EditorRecoveryJournalTest {
    private fun edited(project: ProjectDocument, name: String, extraLayer: Boolean): ProjectDocument {
        val layers = if (extraLayer) project.layers + LayerDocument.newShape(project.layers.size + 1) else project.layers
        return project.copy(name = name, layers = layers, updatedAtEpochMillis = project.updatedAtEpochMillis + 60_000L)
    }

    @Test
    fun journaledUnsavedEditIsRecoveredByAFreshInstance() {
        val root = Files.createTempDirectory("effectcraft-journal").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(committed, "Unsaved revision", true))

            // A fresh instance stands in for a new process: no in-memory state survives.
            val reopened = EditorRecoveryJournal(root)
            val recovered = reopened.recover(committed.id, committed)
            assertNotNull(recovered)
            assertEquals("Unsaved revision", recovered!!.name)
            assertEquals(committed.layers.size + 1, recovered.layers.size)
            assertEquals("Shape ${committed.layers.size + 1}", recovered.layers.last().name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tornJournalIsDiscardedAndCommittedProjectSurvives() {
        val root = Files.createTempDirectory("effectcraft-journal-torn").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(committed, "Unsaved revision", false))

            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            // Rewrite the document without updating the checksum: this is what a
            // torn or tampered write looks like to recovery.
            val tamperedDocument = JSONObject(envelope.getString("document")).put("name", "Tampered revision")
            envelope.put("document", tamperedDocument.toString())
            journalFile.writeText(envelope.toString(), Charsets.UTF_8)

            val reopened = EditorRecoveryJournal(root)
            assertNull(reopened.recover(committed.id, committed))
            assertFalse("A torn journal must be removed so it cannot resurrect later", journalFile.exists())

            val truncatedFile = root.listFiles()!!.firstOrNull()
            assertNull(truncatedFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun truncatedJournalFileIsDiscarded() {
        val root = Files.createTempDirectory("effectcraft-journal-truncated").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(committed, "Unsaved revision", false))
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val bytes = journalFile.readText(Charsets.UTF_8)
            journalFile.writeText(bytes.substring(0, bytes.length / 2), Charsets.UTF_8)

            assertNull(EditorRecoveryJournal(root).recover(committed.id, committed))
            assertFalse(journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleJournalIsDiscardedWhenCommittedIsNewer() {
        val root = Files.createTempDirectory("effectcraft-journal-stale").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(committed, "Old unsaved revision", false))
            val newerCommitted = committed.copy(name = "Saved revision", updatedAtEpochMillis = 500_000L)

            assertNull(EditorRecoveryJournal(root).recover(committed.id, newerCommitted))
            assertTrue("A stale journal must not linger after an explicit save", root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameMillisecondUnsavedRevisionIsRecoveredInsteadOfDiscarded() {
        val root = Files.createTempDirectory("effectcraft-journal-same-ms").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 42_000L)
            val journaled = committed.copy(name = "Unsaved edit in same millisecond")
            EditorRecoveryJournal(root).write(journaled)

            val recovered = EditorRecoveryJournal(root).recover(committed.id, committed)
            assertNotNull("A different journal snapshot with equal wall-clock time is still unsaved", recovered)
            assertEquals("Unsaved edit in same millisecond", recovered!!.name)
            assertEquals(committed.updatedAtEpochMillis, recovered.updatedAtEpochMillis)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun identicalJournalSnapshotIsDiscardedEvenWhenTimestampMatches() {
        val root = Files.createTempDirectory("effectcraft-journal-identical").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 42_000L)
            EditorRecoveryJournal(root).write(committed)

            assertNull(EditorRecoveryJournal(root).recover(committed.id, committed))
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discardAfterSavePreventsResurrection() {
        val root = Files.createTempDirectory("effectcraft-journal-discard").toFile()
        try {
            val project = ProjectDocument.starter().copy(updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(project, "Unsaved revision", false))
            journal.discard(project.id)
            assertNull(EditorRecoveryJournal(root).recover(project.id, project))
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun leftoverWriteTempIsRemovedAndPriorJournalSurvives() {
        val root = Files.createTempDirectory("effectcraft-journal-temp").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(edited(committed, "Unsaved revision", false))
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            // A crash during a journal write leaves a partial temp beside the
            // last complete journal; recovery must drop the temp, not the journal.
            File(root, "${journalFile.name}.tmp").writeText("{ partial", Charsets.UTF_8)

            val recovered = EditorRecoveryJournal(root).recover(committed.id, committed)
            assertEquals("Unsaved revision", recovered!!.name)
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
            assertTrue(journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverNewestRestoresANeverSavedProject() {
        val root = Files.createTempDirectory("effectcraft-journal-newest").toFile()
        try {
            val project = ProjectDocument.starter().copy(name = "Never saved", updatedAtEpochMillis = 7_000L)
            EditorRecoveryJournal(root).write(project)
            val recovered = EditorRecoveryJournal(root).recoverNewest()
            assertNotNull(recovered)
            assertEquals("Never saved", recovered!!.name)
            assertEquals(project.id, recovered.id)
            assertEquals(project.layers.size, recovered.layers.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sessionStateRoundTripsThroughAFreshInstance() {
        val root = Files.createTempDirectory("effectcraft-journal-session").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val current = edited(committed, "Unsaved revision", false)
            val secondLayerId = current.layers[1].id
            val state = EditorSessionState(
                undoStack = listOf(EditorSessionSnapshot(committed, committed.layers[0].id, 7)),
                redoStack = listOf(EditorSessionSnapshot(committed, secondLayerId, 9)),
                selectedLayerId = secondLayerId,
                playheadFrame = 42,
                canonicalUndoStack = listOf(CanonicalEditSnapshot("{\"v\":1}", setOf(3L))),
                canonicalRedoStack = listOf(CanonicalEditSnapshot("{\"v\":2}", setOf(5L))),
                canonicalSelectedIds = setOf(3L, 5L)
            )
            EditorRecoveryJournal(root).write(current, state)

            val recovered = EditorRecoveryJournal(root).recoverSession(committed.id, committed)
            assertNotNull(recovered)
            assertEquals("Unsaved revision", recovered!!.document.name)
            assertEquals(1, recovered.state.undoStack.size)
            assertEquals(7, recovered.state.undoStack[0].playheadFrame)
            assertEquals(committed.layers[0].id, recovered.state.undoStack[0].selectedLayerId)
            assertEquals(1, recovered.state.redoStack.size)
            assertEquals(9, recovered.state.redoStack[0].playheadFrame)
            assertEquals(secondLayerId, recovered.state.selectedLayerId)
            assertEquals(42, recovered.state.playheadFrame)
            assertEquals(listOf(CanonicalEditSnapshot("{\"v\":1}", setOf(3L))), recovered.state.canonicalUndoStack)
            assertEquals(listOf(CanonicalEditSnapshot("{\"v\":2}", setOf(5L))), recovered.state.canonicalRedoStack)
            assertEquals(setOf(3L, 5L), recovered.state.canonicalSelectedIds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyJournalWithoutSessionRecoversWithEmptyState() {
        val root = Files.createTempDirectory("effectcraft-journal-legacy").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            EditorRecoveryJournal(root).write(edited(committed, "Unsaved revision", false))

            val recovered = EditorRecoveryJournal(root).recoverSession(committed.id, committed)
            assertNotNull(recovered)
            assertEquals("Unsaved revision", recovered!!.document.name)
            assertEquals(EditorSessionState.EMPTY, recovered.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedSessionIsTornAndJournalDeleted() {
        val root = Files.createTempDirectory("effectcraft-journal-session-torn").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val state = EditorSessionState(selectedLayerId = committed.layers[1].id, playheadFrame = 42)
            EditorRecoveryJournal(root).write(edited(committed, "Unsaved revision", false), state)
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            // Rewrite the session without updating the checksum: torn storage.
            envelope.put("session", JSONObject(envelope.getString("session")).put("playheadFrame", 99).toString())
            journalFile.writeText(envelope.toString(), Charsets.UTF_8)

            assertNull(EditorRecoveryJournal(root).recoverSession(committed.id, committed))
            assertFalse("A journal with a tampered session must be removed", journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun outOfRangePlayheadStaleSelectionAndForeignSnapshotAreRepaired() {
        val root = Files.createTempDirectory("effectcraft-journal-session-repair").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val current = edited(committed, "Unsaved revision", false)
            val foreign = ProjectDocument.starter().copy(name = "Another project", updatedAtEpochMillis = 2_000L)
            val state = EditorSessionState(
                undoStack = listOf(
                    EditorSessionSnapshot(foreign, foreign.layers[0].id, 3),
                    EditorSessionSnapshot(committed, "gone-layer", 9999)
                ),
                selectedLayerId = "gone-layer",
                playheadFrame = 9999
            )
            EditorRecoveryJournal(root).write(current, state)

            val recovered = EditorRecoveryJournal(root).recoverSession(committed.id, committed)
            assertNotNull(recovered)
            assertEquals("The foreign-project snapshot must be dropped", 1, recovered!!.state.undoStack.size)
            assertEquals(current.durationFrames - 1, recovered.state.undoStack[0].playheadFrame)
            assertEquals(current.layers[0].id, recovered.state.undoStack[0].selectedLayerId)
            assertEquals(current.durationFrames - 1, recovered.state.playheadFrame)
            assertEquals(current.layers[0].id, recovered.state.selectedLayerId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun undoStackIsCappedAtFiftyOnRecovery() {
        val root = Files.createTempDirectory("effectcraft-journal-session-cap").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val current = edited(committed, "Unsaved revision", false)
            val stack = (1..60).map { n ->
                EditorSessionSnapshot(committed.copy(name = "Undo $n"), committed.layers[0].id, n)
            }
            EditorRecoveryJournal(root).write(current, EditorSessionState(undoStack = stack))

            val recovered = EditorRecoveryJournal(root).recoverSession(committed.id, committed)
            assertNotNull(recovered)
            assertEquals(50, recovered!!.state.undoStack.size)
            assertEquals("Undo 11", recovered.state.undoStack.first().document.name)
            assertEquals("Undo 60", recovered.state.undoStack.last().document.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsInvalidProjects() {
        val root = Files.createTempDirectory("effectcraft-journal-invalid").toFile()
        try {
            val invalid = ProjectDocument.starter().copy(name = "", updatedAtEpochMillis = 1_000L)
            assertTrue(runCatching { EditorRecoveryJournal(root).write(invalid) }.isFailure)
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun trimmedAndSplitClipsSurviveJournalRoundTrip() {
        val root = Files.createTempDirectory("effectcraft-journal-clips").toFile()
        try {
            val committed = ProjectDocument.starter().copy(updatedAtEpochMillis = 1_000L)
            val layer = committed.layers.first()
            val trimmed = ClipTimeline.trimClip(layer.clips, layer.clips.single().id, 10, 120, committed.durationFrames)
            val split = ClipTimeline.splitClip(
                (trimmed as ClipEditOutcome.Changed).clips, layer.clips.single().id, 60, committed.durationFrames
            ) { "split-clip" }
            val editedClips = (split as ClipEditOutcome.Changed).clips
            val edited = committed.copy(
                layers = listOf(layer.copy(clips = editedClips)) + committed.layers.drop(1),
                updatedAtEpochMillis = 2_000L
            )
            EditorRecoveryJournal(root).write(edited)

            val recovered = EditorRecoveryJournal(root).recover(committed.id, committed)
            assertNotNull(recovered)
            assertEquals(editedClips, recovered!!.layers.first().clips)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun journalWithoutClipsDecodesToEmptyClipLists() {
        val root = Files.createTempDirectory("effectcraft-journal-noclips").toFile()
        try {
            val committed = ProjectDocument.starter().copy(updatedAtEpochMillis = 1_000L)
            val journal = EditorRecoveryJournal(root)
            journal.write(committed)
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            // Strip the clips arrays (old documents never had them) and
            // re-sign the envelope so the checksum still verifies.
            val document = JSONObject(envelope.getString("document"))
            val layers = document.getJSONArray("layers")
            for (index in 0 until layers.length()) layers.getJSONObject(index).remove("clips")
            envelope.put("document", document.toString())
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(document.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            envelope.put("checksum", digest)
            journalFile.writeText(envelope.toString(), Charsets.UTF_8)

            val recovered = EditorRecoveryJournal(root).recover(committed.id, committed)
            assertNotNull(recovered)
            assertTrue(recovered!!.layers.all { it.clips.isEmpty() })
        } finally {
            root.deleteRecursively()
        }
    }
}
