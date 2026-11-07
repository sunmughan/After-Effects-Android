package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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

            // A fresh repository instance models app/process restart after a
            // canonical timeline/keyframe edit has been durably promoted.
            val reopened = CanonicalProjectRepository(root)
            assertEquals(updated, reopened.load(created.id))
            assertEquals(edited.documentSha256, reopened.metadata(created.id)?.documentSha256)

            assertTrue(repo.delete(created.id))
            assertNull(repo.load(created.id))
            assertNull(repo.metadata(created.id))
            assertFalse(repo.delete(created.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedDocumentPromotionRollsBackToDurableProjectPairOnFreshLoad() {
        val root = Files.createTempDirectory("effectcraft-repo-update-death").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Crash-safe update", document, "ecproj")
            val documentFile = File(root, "${created.id}.ecproj")
            val sidecarFile = File(root, "${created.id}.json")
            val backupDocument = File(root, "${created.id}.ecproj.update-bak")
            val backupSidecar = File(root, "${created.id}.json.update-bak")
            backupDocument.writeText(documentFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            backupSidecar.writeText(sidecarFile.readText(Charsets.UTF_8), Charsets.UTF_8)

            // Simulate process death after new document bytes are promoted but
            // before the new SHA-256 sidecar is promoted.
            documentFile.writeText(updated, Charsets.UTF_8)
            val reopened = CanonicalProjectRepository(root)
            assertEquals(document, reopened.load(created.id))
            assertEquals(created.documentSha256, reopened.metadata(created.id)?.documentSha256)
            assertFalse(backupDocument.exists())
            assertFalse(backupSidecar.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedRollbackPromotionIsRecoveredAgainByFreshLoad() {
        val root = Files.createTempDirectory("effectcraft-repo-recovery-death").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Recovery itself interrupted", document, "ecproj")
            val documentFile = File(root, "${created.id}.ecproj")
            val sidecarFile = File(root, "${created.id}.json")
            val backupDocument = File(root, "${created.id}.ecproj.update-bak")
            val backupSidecar = File(root, "${created.id}.json.update-bak")

            // Durable rollback pair survives the first recovery attempt.
            backupDocument.writeText(document, Charsets.UTF_8)
            backupSidecar.writeText(sidecarFile.readText(Charsets.UTF_8), Charsets.UTF_8)

            // Simulate recovery dying after it restored the old document but
            // before it restored the old sidecar (which still describes v2).
            documentFile.writeText(document, Charsets.UTF_8)
            sidecarFile.writeText(
                JSONObject(sidecarFile.readText(Charsets.UTF_8))
                    .put("documentSha256", sha256ForTest(updated)).toString(),
                Charsets.UTF_8
            )

            val reopened = CanonicalProjectRepository(root)
            assertEquals(document, reopened.load(created.id))
            assertEquals(created.documentSha256, reopened.metadata(created.id)?.documentSha256)
            assertFalse(backupDocument.exists())
            assertFalse(backupSidecar.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedUpdateWithLeftoverBackupsKeepsNewPairAndCleansBackups() {
        val root = Files.createTempDirectory("effectcraft-repo-update-committed").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Committed update", document, "ecproj")
            repo.update(created.id, updated)
            File(root, "${created.id}.ecproj.update-bak").writeText(document, Charsets.UTF_8)
            File(root, "${created.id}.json.update-bak").writeText(
                JSONObject(File(root, "${created.id}.json").readText(Charsets.UTF_8))
                    .put("documentSha256", created.documentSha256).toString(),
                Charsets.UTF_8
            )

            val reopened = CanonicalProjectRepository(root)
            assertEquals(updated, reopened.load(created.id))
            assertFalse(File(root, "${created.id}.ecproj.update-bak").exists())
            assertFalse(File(root, "${created.id}.json.update-bak").exists())
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
    fun sidecarIdMustMatchRepositoryFilenameForLookupListingAndMigration() {
        val root = Files.createTempDirectory("effectcraft-repo-sidecar-id").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val valid = repo.create("Valid", document, "ecproj")
            val mismatched = repo.create("Mismatched", updated, "ecproj")
            val sidecar = File(root, "${mismatched.id}.json")
            sidecar.writeText(
                JSONObject(sidecar.readText(Charsets.UTF_8)).put("id", valid.id).toString(),
                Charsets.UTF_8
            )

            assertNull(repo.metadata(mismatched.id))
            assertEquals(listOf(valid), repo.list())
            val migration = repo.migrateAll().single { it.id == mismatched.id }
            assertEquals("Project metadata ID does not match its filename", migration.error)
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
    fun tamperedCanonicalDocumentIsRejectedByRepositoryIntegrityCheck() {
        val root = Files.createTempDirectory("effectcraft-repo-integrity").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Integrity", document, "ecproj")
            // Simulate external modification/corruption after the atomic import.
            File(root, "${created.id}.ecproj").writeText(updated, Charsets.UTF_8)

            assertNull("tampered document must not load", repo.load(created.id))
            assertNull("tampered sidecar/document pair must not be trusted", repo.metadata(created.id))
            assertFalse("corrupt entry must not appear in the picker", repo.list().any { it.id == created.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacySidecarWithoutDigestBackfillsDigestDuringMigration() {
        val root = Files.createTempDirectory("effectcraft-repo-integrity-legacy").toFile()
        try {
            val repo = CanonicalProjectRepository(root)
            val created = repo.create("Legacy digest", document, "ecproj")
            val sidecar = File(root, "${created.id}.json")
            val legacy = JSONObject(sidecar.readText(Charsets.UTF_8)).apply {
                remove("version")
                remove("documentSha256")
            }
            sidecar.writeText(legacy.toString(), Charsets.UTF_8)

            val result = repo.migrateAll().single()
            assertNull(result.error)
            assertEquals(0, result.fromVersion)
            assertNotNull(repo.metadata(created.id)?.documentSha256?.takeIf { it.matches(Regex("[a-f0-9]{64}")) })
            assertEquals(document, repo.load(created.id))
            assertTrue(JSONObject(sidecar.readText(Charsets.UTF_8)).has("documentSha256"))
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

    private fun sha256ForTest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
