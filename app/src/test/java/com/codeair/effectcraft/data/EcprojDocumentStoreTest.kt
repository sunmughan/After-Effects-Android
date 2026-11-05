package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EcprojDocumentStoreTest {
    @Test
    fun completeInterruptedCanonicalEditIsPromotedOnFreshLoad() {
        val root = Files.createTempDirectory("effectcraft-ecproj-recovery").toFile()
        try {
            val original = """{"name":"Before","compositions":[]}"""
            val edited = """{"name":"Timeline edited","compositions":[]}"""
            EcprojDocumentStore(root).save(original)

            // Simulate process death after the new bytes reached the temp
            // file but before the atomic rename.
            File(root, "latest.ecproj.tmp").writeText(edited, Charsets.UTF_8)
            val reopened = EcprojDocumentStore(root)
            assertEquals(edited, reopened.load())
            assertFalse(File(root, "latest.ecproj.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedInterruptedEditIsDiscardedWithoutReplacingCommittedCopy() {
        val root = Files.createTempDirectory("effectcraft-ecproj-recovery-invalid").toFile()
        try {
            val committed = """{"name":"Committed","compositions":[]}"""
            EcprojDocumentStore(root).save(committed)
            File(root, "latest.ecproj.tmp").writeText("{truncated", Charsets.UTF_8)

            assertEquals(committed, EcprojDocumentStore(root).load())
            assertFalse(File(root, "latest.ecproj.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
