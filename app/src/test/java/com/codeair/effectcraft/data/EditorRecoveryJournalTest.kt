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
