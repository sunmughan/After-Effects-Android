package ai.effectcraft.android

import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.ProjectDocument
import android.content.Context
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the same filesystem reconciliation used on application startup.
 * The temp file simulates process death between serialization and rename.
 */
class DraftProjectStoreRecoveryInstrumentedTest {
    private fun isolatedStore(): Pair<Context, DraftProjectStore> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "projects")
        directory.listFiles()?.forEach { it.delete() }
        return context to DraftProjectStore(context)
    }

    @Test
    fun completeNewerAtomicWriteTempIsPromotedOnLoad() {
        val (_, store) = isolatedStore()
        val committed = ProjectDocument.starter().copy(name = "Committed revision")
        val target = store.save(committed)
        val committedTimestamp = store.load(committed.id)!!.updatedAtEpochMillis
        val temporary = File(target.parentFile, "${target.name}.tmp")

        val interrupted = JSONObject(target.readText(Charsets.UTF_8))
            .put("name", "Recovered revision")
            .put("updatedAtEpochMillis", committedTimestamp + 1L)
        temporary.writeText(interrupted.toString(2), Charsets.UTF_8)

        val recovered = store.load(committed.id)
        assertNotNull(recovered)
        assertEquals("Recovered revision", recovered!!.name)
        assertEquals(committedTimestamp + 1L, recovered.updatedAtEpochMillis)
        assertFalse("Promoted temp must no longer exist", temporary.exists())
        assertEquals("Recovered revision", JSONObject(target.readText(Charsets.UTF_8)).getString("name"))
    }

    @Test
    fun incompleteAtomicWriteTempCannotReplaceCommittedRevision() {
        val (_, store) = isolatedStore()
        val committed = ProjectDocument.starter().copy(name = "Last committed revision")
        val target = store.save(committed)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText("{\"format\":", Charsets.UTF_8)

        val recovered = store.load(committed.id)
        assertNotNull(recovered)
        assertEquals("Last committed revision", recovered!!.name)
        assertEquals("Last committed revision", JSONObject(target.readText(Charsets.UTF_8)).getString("name"))
        assertFalse("Invalid temp is discarded", temporary.exists())
    }
}
