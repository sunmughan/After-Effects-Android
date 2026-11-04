package ai.effectcraft.android

import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.ProjectDocument
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Process-death recovery for draft projects: a killed mid-edit process must
 * recover to the last atomically saved revision. A fresh store instance is
 * the stand-in for a new process: it shares only the on-disk directory, no
 * in-memory state.
 */
class DraftProjectStoreProcessDeathTest {

    private fun isolatedStore(): DraftProjectStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "projects").listFiles()?.forEach { it.delete() }
        return DraftProjectStore(context)
    }

    @Test
    fun killedMidEditProcessRecoversLastAtomicallySavedRevision() {
        val store = isolatedStore()
        val committed = ProjectDocument.starter().copy(name = "Committed revision")
        store.save(committed)
        val revised = committed.copy(name = "Revised revision")
        store.save(revised)

        // An edit that is never passed to save() exists only in the dead
        // process's memory; the assertions below prove the restarted store
        // still reports the last saved revision, not a phantom edit.

        val restarted = DraftProjectStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val recovered = restarted.load(committed.id)
        assertNotNull(recovered)
        assertEquals("Revised revision", recovered!!.name)
        assertEquals(revised.layers.size, recovered.layers.size)
        assertEquals("Revised revision", restarted.loadLatest()!!.name)
    }

    @Test
    fun corruptCommittedDraftDoesNotPoisonTheCatalog() {
        val store = isolatedStore()
        val saved = ProjectDocument.starter().copy(name = "Good revision")
        store.save(saved)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "projects/aaaaaaaa-corrupt.efx-draft")
            .writeText("{not json", Charsets.UTF_8)

        val restarted = DraftProjectStore(context)
        val recovered = restarted.load(saved.id)
        assertNotNull(recovered)
        assertEquals("Good revision", recovered!!.name)
        assertEquals(1, restarted.listSavedProjects().size)
    }

    @Test
    fun interruptedWriteTempForAnotherNameNeverOverwritesCommittedRevision() {
        val store = isolatedStore()
        val committed = ProjectDocument.starter().copy(name = "Last committed revision")
        val target = store.save(committed)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        // A fully valid temp whose project ID does not match its filename:
        // recovery must discard it, never promote it over the committed file.
        val foreign = JSONObject(target.readText(Charsets.UTF_8))
            .put("id", "00000000-0000-0000-0000-000000000000")
            .put("name", "Foreign revision")
        temporary.writeText(foreign.toString(2), Charsets.UTF_8)

        val recovered = DraftProjectStore(InstrumentationRegistry.getInstrumentation().targetContext)
            .load(committed.id)
        assertNotNull(recovered)
        assertEquals("Last committed revision", recovered!!.name)
    }
}
