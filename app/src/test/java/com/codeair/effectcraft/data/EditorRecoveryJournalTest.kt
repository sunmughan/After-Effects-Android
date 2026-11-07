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
import java.security.MessageDigest

class EditorRecoveryJournalTest {
    private fun edited(project: ProjectDocument, name: String, extraLayer: Boolean): ProjectDocument {
        val layers = if (extraLayer) project.layers + LayerDocument.newShape(project.layers.size + 1) else project.layers
        return project.copy(name = name, layers = layers, updatedAtEpochMillis = project.updatedAtEpochMillis + 60_000L)
    }

    private fun snapshot(json: String, selected: Set<Long> = emptySet()) =
        CanonicalEditSnapshot(json, selected)

    private val canonA = """{"id":"canon-a","layers":[]}"""
    private val canonB = """{"id":"canon-b","layers":[]}"""

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Test
    fun fullStateRoundTripRestoresDocumentUndoSelectionAndPlayhead() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-roundtrip").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            val undo = listOf(snapshot(canonA, setOf(3L)), snapshot(canonB, setOf(3L, 7L)))
            val redo = listOf(snapshot(canonA, setOf(3L)))
            EditorRecoveryJournal(root).writeFull(journaled, undo, redo, setOf(7L, 9L), 42)

            val restored = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull(restored)
            assertEquals("Unsaved revision", restored!!.document.name)
            assertEquals(undo, restored.undoStack)
            assertEquals(redo, restored.redoStack)
            assertEquals(setOf(7L, 9L), restored.selectedLayerIds)
            assertEquals(42, restored.playheadFrame)

            // The plain document path keeps working alongside the full state.
            assertEquals("Unsaved revision", EditorRecoveryJournal(root).recover(committed.id, committed)?.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoredUndoStackIsReappliableThroughCanonicalHistory() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-reapply").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            val undo = listOf(snapshot(canonA, setOf(3L)), snapshot(canonB, setOf(3L, 7L)))
            EditorRecoveryJournal(root).writeFull(journaled, undo, emptyList(), setOf(7L), 10)

            val restored = EditorRecoveryJournal(root).recoverFull(committed.id, committed)!!
            val current = snapshot("""{"id":"canon-current","layers":[]}""", setOf(7L))
            val step = CanonicalEditHistory.undo(restored.undoStack, restored.redoStack, current)
            assertNotNull("Undo must work on the restored stacks", step)
            assertEquals(canonB, step!!.snapshot.projectJson)
            assertEquals(setOf(3L, 7L), step.snapshot.selectedLayerIds)
            assertEquals(1, step.undoStack.size)
            assertEquals(listOf(current), step.redoStack)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun versionOneJournalMigratesOnReadWithEmptyHistory() {
        val root = Files.createTempDirectory("effectcraft-journal-v1-migrate").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            EditorRecoveryJournal(root).writeFull(journaled, emptyList(), emptyList(), emptySet(), 0)
            // Rewrite the envelope as v1: no history fields, old checksum rule.
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            val documentText = envelope.getString("document")
            val v1 = JSONObject().apply {
                put("format", "effectcraft-android-recovery-journal")
                put("version", 1)
                put("projectId", envelope.getString("projectId"))
                put("updatedAtEpochMillis", envelope.getLong("updatedAtEpochMillis"))
                put("checksum", sha256(documentText))
                put("document", documentText)
            }
            journalFile.writeText(v1.toString(), Charsets.UTF_8)

            val restored = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull("A v1 journal must migrate on read, not be discarded", restored)
            assertEquals("Unsaved revision", restored!!.document.name)
            assertTrue(restored.undoStack.isEmpty())
            assertTrue(restored.redoStack.isEmpty())
            assertTrue(restored.selectedLayerIds.isEmpty())
            assertEquals(0, restored.playheadFrame)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedUndoStackIsTornAndJournalDeleted() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-tampered").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            EditorRecoveryJournal(root).writeFull(journaled, listOf(snapshot(canonA)), emptyList(), setOf(7L), 5)
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            // Rewrite an undo entry without updating the checksum.
            val tampered = envelope.getJSONArray("undoStack")
            tampered.getJSONObject(0).put("projectJson", """{"id":"tampered","layers":[]}""")
            journalFile.writeText(envelope.toString(), Charsets.UTF_8)

            assertNull(EditorRecoveryJournal(root).recoverFull(committed.id, committed))
            assertFalse("A journal with tampered history must be removed", journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun versionTwoJournalMissingHistoryFieldsIsTorn() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-missing").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            EditorRecoveryJournal(root).writeFull(journaled, listOf(snapshot(canonA)), emptyList(), setOf(7L), 5)
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
            envelope.remove("selection")
            journalFile.writeText(envelope.toString(), Charsets.UTF_8)

            assertNull(EditorRecoveryJournal(root).recoverFull(committed.id, committed))
            assertFalse(journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleVersionTwoJournalIsDeleted() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-stale").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Old unsaved revision", false)
            EditorRecoveryJournal(root).writeFull(journaled, listOf(snapshot(canonA)), emptyList(), setOf(7L), 5)
            val newerCommitted = committed.copy(name = "Saved revision", updatedAtEpochMillis = 500_000L)

            assertNull(EditorRecoveryJournal(root).recoverFull(committed.id, newerCommitted))
            assertTrue("A stale v2 journal must not linger after an explicit save", root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doubleCrashBeforeSaveStillRecoversFullState() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-double-crash").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            val undo = listOf(snapshot(canonA, setOf(3L)))
            EditorRecoveryJournal(root).writeFull(journaled, undo, emptyList(), setOf(3L), 12)

            // First crash: recover without saving. The journal must survive.
            val first = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull(first)
            assertEquals(undo, first!!.undoStack)
            assertEquals(12, first.playheadFrame)
            assertTrue(root.listFiles()!!.any { it.name.endsWith(".journal") })

            // Second crash before any explicit save: the same full state returns.
            val second = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull(second)
            assertEquals("Unsaved revision", second!!.document.name)
            assertEquals(undo, second.undoStack)
            assertEquals(setOf(3L), second.selectedLayerIds)
            assertEquals(12, second.playheadFrame)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun crashMidWriteLeavesPreviousCompleteJournal() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-midwrite").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            val undo = listOf(snapshot(canonA, setOf(3L)))
            EditorRecoveryJournal(root).writeFull(journaled, undo, emptyList(), setOf(3L), 12)
            val journalFile = root.listFiles()!!.single { it.name.endsWith(".journal") }
            // A crash during the next write leaves a partial temp beside the
            // last complete journal; recovery must drop the temp, not the journal.
            File(root, "${journalFile.name}.tmp").writeText("{ partial", Charsets.UTF_8)

            val recovered = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull(recovered)
            assertEquals("Unsaved revision", recovered!!.document.name)
            assertEquals(undo, recovered.undoStack)
            assertEquals(12, recovered.playheadFrame)
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
            assertTrue(journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverNewestFullRestoresNewestProjectState() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-newest").toFile()
        try {
            val older = ProjectDocument.starter().copy(name = "Older project", updatedAtEpochMillis = 7_000L)
            val newer = ProjectDocument.starter().copy(name = "Newer project", updatedAtEpochMillis = 9_000L)
            EditorRecoveryJournal(root).writeFull(older, listOf(snapshot(canonA)), emptyList(), setOf(1L), 3)
            EditorRecoveryJournal(root).writeFull(newer, listOf(snapshot(canonB, setOf(5L))), emptyList(), setOf(5L), 8)

            val restored = EditorRecoveryJournal(root).recoverNewestFull()
            assertNotNull(restored)
            assertEquals("Newer project", restored!!.document.name)
            assertEquals(listOf(snapshot(canonB, setOf(5L))), restored.undoStack)
            assertEquals(setOf(5L), restored.selectedLayerIds)
            assertEquals(8, restored.playheadFrame)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsNegativePlayheadAndWritesNothing() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-playhead").toFile()
        try {
            val project = ProjectDocument.starter().copy(updatedAtEpochMillis = 1_000L)
            assertTrue(runCatching {
                EditorRecoveryJournal(root).writeFull(project, emptyList(), emptyList(), emptySet(), -1)
            }.isFailure)
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun undoStackIsCappedAtFiftyEntriesOnRestore() {
        val root = Files.createTempDirectory("effectcraft-journal-v2-cap").toFile()
        try {
            val committed = ProjectDocument.starter().copy(name = "Committed baseline", updatedAtEpochMillis = 1_000L)
            val journaled = edited(committed, "Unsaved revision", false)
            val oversized = (0 until 60).map { snapshot("""{"id":"canon-$it","layers":[]}""") }
            EditorRecoveryJournal(root).writeFull(journaled, oversized, emptyList(), emptySet(), 0)

            val restored = EditorRecoveryJournal(root).recoverFull(committed.id, committed)
            assertNotNull(restored)
            assertEquals(CanonicalEditHistory.MAX_ENTRIES, restored!!.undoStack.size)
            assertEquals(oversized.takeLast(CanonicalEditHistory.MAX_ENTRIES), restored.undoStack)
        } finally {
            root.deleteRecursively()
        }
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
}
