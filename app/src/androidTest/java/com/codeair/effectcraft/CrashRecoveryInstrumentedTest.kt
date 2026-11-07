package com.codeair.effectcraft

import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.DraftProjectStore
import com.codeair.effectcraft.data.EditorRecoveryJournal
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Crash simulation at the store level: each test builds committed state,
 * plants the on-disk traces a crash would leave, then uses brand new store
 * and journal instances (no in-memory state survives) to prove recovery.
 * This is the process-death half of M6-L4; the activity-recreation half
 * lives in EditorLifecycleInstrumentedTest.
 */
class CrashRecoveryInstrumentedTest {
    private fun isolated(): Triple<DraftProjectStore, EditorRecoveryJournal, File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projects = File(context.filesDir, "projects")
        projects.listFiles()?.forEach { it.delete() }
        val journalDir = File(context.filesDir, "recovery-journal")
        journalDir.listFiles()?.forEach { it.delete() }
        return Triple(DraftProjectStore(context), EditorRecoveryJournal(journalDir), journalDir)
    }

    @Test
    fun crashDuringEditingIsRecoveredByFreshInstances() {
        val (store, journal, _) = isolated()
        val committed = ProjectDocument.starter().copy(name = "Committed baseline")
        val target = store.save(committed)
        val stored = store.load(committed.id)!!
        val edited = stored.copy(
            name = "Unsaved revision",
            layers = stored.layers + LayerDocument.newShape(stored.layers.size + 1),
            updatedAtEpochMillis = stored.updatedAtEpochMillis + 60_000L
        )
        journal.write(edited)

        // Simulate process death: fresh instances, nothing in memory.
        val (freshStore, freshJournal, _) = isolatedStoresOnly()
        val reloaded = freshStore.load(committed.id)
        assertEquals("Committed baseline", reloaded!!.name)
        assertEquals(stored.layers.size, reloaded.layers.size)
        val recovered = freshJournal.recover(committed.id, reloaded)
        assertNotNull(recovered)
        assertEquals("Unsaved revision", recovered!!.name)
        assertEquals(stored.layers.size + 1, recovered.layers.size)
        assertEquals("Shape ${stored.layers.size + 1}", recovered.layers.last().name)
        // Journal replay restores the working project; it must not rewrite
        // the committed draft until an explicit save happens.
        assertEquals("Committed baseline", JSONObject(target.readText(Charsets.UTF_8)).getString("name"))
    }

    @Test
    fun crashDuringJournalWriteLeavesCommittedDraftIntact() {
        val (store, journal, journalDir) = isolated()
        val committed = ProjectDocument.starter().copy(name = "Last committed revision")
        val target = store.save(committed)
        val stored = store.load(committed.id)!!
        journal.write(stored.copy(name = "Unsaved revision", updatedAtEpochMillis = stored.updatedAtEpochMillis + 60_000L))
        val journalFile = journalDir.listFiles()!!.single { it.name.endsWith(".journal") }
        val bytes = journalFile.readText(Charsets.UTF_8)
        journalFile.writeText(bytes.substring(0, bytes.length / 2), Charsets.UTF_8)
        File(journalDir, "${journalFile.name}.tmp").writeText("{ partial", Charsets.UTF_8)

        val (freshStore, freshJournal, _) = isolatedStoresOnly()
        assertNull(freshJournal.recover(committed.id, freshStore.load(committed.id)))
        assertFalse("A torn journal must be removed", journalFile.exists())
        assertFalse("A torn journal temp must be removed", journalDir.listFiles()!!.any { it.name.endsWith(".tmp") })
        val loaded = freshStore.load(committed.id)
        assertEquals("Last committed revision", loaded!!.name)
        assertEquals("Last committed revision", JSONObject(target.readText(Charsets.UTF_8)).getString("name"))
    }

    @Test
    fun staleJournalAfterExplicitSaveDoesNotResurrectOldEdits() {
        val (store, journal, journalDir) = isolated()
        val committed = ProjectDocument.starter().copy(name = "Committed baseline")
        store.save(committed)
        val stored = store.load(committed.id)!!
        journal.write(stored.copy(name = "Old unsaved revision"))
        val savedAgain = store.save(stored.copy(name = "Saved revision"))
        val current = store.load(committed.id)!!
        assertEquals("Saved revision", current.name)
        assertTrue(savedAgain.exists())

        val (_, freshJournal, _) = isolatedStoresOnly()
        assertNull(freshJournal.recover(committed.id, current))
        assertTrue("A stale journal must be deleted on recovery", journalDir.listFiles().isNullOrEmpty())
        assertEquals("Saved revision", store.load(committed.id)!!.name)
    }

    @Test
    fun journaledEditWinsOverPromotedAtomicTempAfterCrash() {
        val (store, journal, _) = isolated()
        val committed = ProjectDocument.starter().copy(name = "Committed baseline")
        val target = store.save(committed)
        val stored = store.load(committed.id)!!
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(
            JSONObject(target.readText(Charsets.UTF_8))
                .put("name", "Temp revision")
                .put("updatedAtEpochMillis", stored.updatedAtEpochMillis + 30_000L)
                .toString(2),
            Charsets.UTF_8
        )
        journal.write(
            stored.copy(
                name = "Journal revision",
                layers = stored.layers + LayerDocument.newShape(stored.layers.size + 1),
                updatedAtEpochMillis = stored.updatedAtEpochMillis + 60_000L
            )
        )

        val (freshStore, freshJournal, _) = isolatedStoresOnly()
        val promoted = freshStore.load(committed.id)
        assertEquals("Temp revision", promoted!!.name)
        val recovered = freshJournal.recover(committed.id, promoted)
        assertEquals("Journal revision", recovered!!.name)
        assertEquals(stored.layers.size + 1, recovered.layers.size)
    }

    @Test
    fun neverSavedProjectIsRecoveredFromJournalAlone() {
        val (_, journal, _) = isolated()
        val project = ProjectDocument.starter().copy(name = "Never saved project")
        journal.write(project)

        val (_, freshJournal, _) = isolatedStoresOnly()
        val recovered = freshJournal.recoverNewest()
        assertNotNull(recovered)
        assertEquals("Never saved project", recovered!!.name)
        assertEquals(project.id, recovered.id)
        assertEquals(project.layers.size, recovered.layers.size)
    }

    private fun isolatedStoresOnly(): Triple<DraftProjectStore, EditorRecoveryJournal, File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val journalDir = File(context.filesDir, "recovery-journal")
        return Triple(DraftProjectStore(context), EditorRecoveryJournal(journalDir), journalDir)
    }
}
