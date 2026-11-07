package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

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
    @Test
    fun independentStoresSerializeConcurrentCanonicalWrites() {
        val root = Files.createTempDirectory("effectcraft-ecproj-concurrent").toFile()
        val workers = 6
        val writesPerWorker = 12
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { worker ->
                pool.submit {
                    start.await()
                    val store = EcprojDocumentStore(root)
                    repeat(writesPerWorker) { sequence ->
                        store.save("""{"writer":$worker,"sequence":$sequence}""")
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }

            val committed = EcprojDocumentStore(root).load()
            assertTrue("Concurrent saves must leave a committed document", committed != null)
            val parsed = JSONObject(committed!!)
            assertTrue(parsed.getInt("writer") in 0 until workers)
            assertTrue(parsed.getInt("sequence") in 0 until writesPerWorker)
            assertFalse(File(root, "latest.ecproj.tmp").exists())
        } finally {
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }

}
