package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

class CanonicalProjectRepositoryTest {
    private val document = """{"name":"Demo","compositions":[]}"""
    private val updated = """{"name":"Demo v2","compositions":[]}"""

    @Test
    fun createsUpdatesListsAndDeletesProjects() {
        val root = Files.createTempDirectory("effectcraft-repo").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Demo project", document, "ecproj", "Demo.ecproj")
            assertEquals("Demo project", created.displayName)
            assertEquals("ecproj", created.format)
            assertEquals(CanonicalProjectRepository.CURRENT_SCHEMA_VERSION, created.schemaVersion)
            assertEquals(document, repo.load(created.id))
            assertEquals(created, repo.metadata(created.id))

            val edited = repo.update(created.id, updated)
            assertEquals("Demo project", edited.displayName)
            assertEquals(updated, repo.load(created.id))
            assertEquals(listOf(edited), repo.list())

            assertTrue(repo.delete(created.id))
            assertNull(repo.load(created.id))
            assertNull(repo.metadata(created.id))
            assertFalse(repo.delete(created.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatesDocumentsAndRejectsUnknownIdsOrFormats() {
        val root = Files.createTempDirectory("effectcraft-repo-validate").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            assertTrue(runCatching { repo.create("Bad", "not json", "ecproj") }.isFailure)
            assertTrue(runCatching { repo.create("Bad", "", "ecproj") }.isFailure)
            assertTrue(runCatching { repo.create("Bad", document, "efkproj") }.isFailure)
            assertTrue(runCatching { repo.create("", document, "ecproj") }.isFailure)
            assertTrue(runCatching { repo.update("missing-id", document) }.isFailure)
            assertTrue(runCatching { repo.create("XML", "<EffectCraftProject></EffectCraftProject>", "ecprojx") }.isFailure.not())
            assertTrue(runCatching { repo.create("XML bad", "<OtherRoot/>", "ecprojx") }.isFailure)
            assertTrue(runCatching { repo.create("XML bad 2", """{"a":1}""", "ecprojx") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun updateFailureRestoresPreviousDocument() {
        val root = Files.createTempDirectory("effectcraft-repo-rollback").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Rollback", document, "ecproj")
            assertTrue(runCatching { repo.update(created.id, "broken {{{") }.isFailure)
            assertEquals(document, repo.load(created.id))
            assertEquals(document, repo.load(created.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacySidecarAndReportsFutureVersions() {
        val root = Files.createTempDirectory("effectcraft-repo-migrate").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Legacy", document, "ecproj")
            // Simulate a v0 sidecar: same fields, no version key.
            val sidecar = File(root, "${created.id}.json")
            val legacy = JSONObject(sidecar.readText(Charsets.UTF_8)).apply { remove("version") }
            sidecar.writeText(legacy.toString(), Charsets.UTF_8)

            val results = repo.migrateAll()
            assertEquals(1, results.size)
            assertEquals(0, results.single().fromVersion)
            assertEquals(CanonicalProjectRepository.CURRENT_SCHEMA_VERSION, results.single().toVersion)
            assertNull(results.single().error)
            assertEquals(document, repo.load(created.id))
            assertEquals(CanonicalProjectRepository.CURRENT_SCHEMA_VERSION, repo.metadata(created.id)?.schemaVersion)

            // A future-version sidecar must be reported, never silently accepted.
            val future = repo.create("Future", document, "ecproj")
            val futureSidecar = File(root, "${future.id}.json")
            futureSidecar.writeText(
                JSONObject(futureSidecar.readText(Charsets.UTF_8))
                    .put("version", CanonicalProjectRepository.CURRENT_SCHEMA_VERSION + 5).toString(),
                Charsets.UTF_8
            )
            val second = repo.migrateAll()
            val futureResult = second.single { it.id == future.id }
            assertNotNull(futureResult.error)
            assertTrue(futureResult.error!!.contains("newer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacySingleDocumentImportsAsRepositoryEntry() {
        val root = Files.createTempDirectory("effectcraft-repo-legacy").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val imported = repo.importLegacyDocument(document, "Old latest.ecproj")
            assertEquals(document, repo.load(imported.id))
            assertEquals(1, repo.list().size)
        } finally {
            root.deleteRecursively()
        }
    }
}
