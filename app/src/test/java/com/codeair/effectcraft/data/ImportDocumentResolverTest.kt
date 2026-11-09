package com.codeair.effectcraft.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Batch 3: the import resolver. One entry point for every document the
 * picker can hand over — plain .ecproj/.ecprojx, an AE interchange
 * document, or a ZIP bundle holding either — and one canonical JSON
 * string out. A raw .aep binary is imported by the ported official
 * .aep importer, through the loss gate (analyzeAep + importAep);
 * resolve() without an allowance refuses a lossy file instead of
 * silently dropping content.
 */
class ImportDocumentResolverTest {

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "import-resolver-test-${System.nanoTime()}")

    private val interchangeJson = """
        {"format": "effectcraft-ae-interchange", "version": 1,
         "comps": [{"name": "Main", "width": 640, "height": 360,
                    "frameRate": 30, "duration": 2.0, "layers": []}]}
    """.trimIndent()

    @Test
    fun plainEcprojPassesThroughUnchanged() {
        val ecproj = """{"schema": 2, "items": {}}"""
        val out = ImportDocumentResolver.resolve(
            ecproj.toByteArray(Charsets.UTF_8), tempDir()
        )
        assertEquals(ecproj, out.canonicalJson)
        assertEquals(false, out.wasAeInterchange)
    }

    @Test
    fun plainInterchangeJsonIsConverted() {
        val out = ImportDocumentResolver.resolve(
            interchangeJson.toByteArray(Charsets.UTF_8), tempDir()
        )
        assertEquals(true, out.wasAeInterchange)
        val doc = JSONObject(out.canonicalJson)
        assertEquals(2, doc.getInt("schema"))
        assertEquals("Main", doc.getJSONObject("items").getJSONObject("1").getString("name"))
    }

    @Test
    fun zipBundleWithInterchangeIsConvertedAndMediaResolved() {
        val dir = tempDir()
        try {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val ae = """
                {"format": "effectcraft-ae-interchange", "version": 1,
                 "comps": [{"name": "Main", "width": 640, "height": 360,
                            "frameRate": 30, "duration": 2.0,
                            "layers": [{"name": "Pic", "type": "footage", "file": "media/pic.png",
                                        "width": 640, "height": 360}]}]}
            """.trimIndent()
            val out = ImportDocumentResolver.resolve(
                zipBytes(mapOf("project.json" to ae.toByteArray(Charsets.UTF_8), "media/pic.png" to png)),
                dir
            )
            assertEquals(true, out.wasAeInterchange)
            val doc = JSONObject(out.canonicalJson)
            assertEquals(2, doc.getInt("schema"))
            val items = doc.getJSONObject("items")
            val comp = items.getJSONObject("1").getJSONObject("kind")
            val layer = comp.getJSONArray("layers").getJSONObject(0)
            val footageId = layer.getJSONObject("source").getInt("item")
            val path = items.getJSONObject(footageId.toString()).getJSONObject("kind").getString("path")
            assertTrue(path.startsWith(dir.absolutePath))
            assertTrue(File(path).exists())
            assertEquals(false, items.getJSONObject(footageId.toString()).getJSONObject("kind").getBoolean("missing"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipBundleWithEcprojPassesThrough() {
        val dir = tempDir()
        try {
            val ecproj = """{"schema": 2, "items": {}}"""
            val out = ImportDocumentResolver.resolve(
                zipBytes(mapOf("project.json" to ecproj.toByteArray(Charsets.UTF_8))),
                dir
            )
            assertEquals(ecproj, out.canonicalJson)
            assertEquals(false, out.wasAeInterchange)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipWithoutProjectDocumentIsRejected() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ImportDocumentResolver.resolve(
                    zipBytes(mapOf("media/a.png" to byteArrayOf(1))),
                    dir
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawAepBinaryWithoutAProjectIsRejectedAsUnparsable() {
        val aep = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'X'.code.toByte(), 0, 0, 0, 0)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentResolver.resolve(aep, tempDir())
        }
        // A parse failure, not the old "not in this build yet" guidance.
        assertTrue(failure.message!!.contains("RIFX") || failure.message!!.contains("not a valid"))
    }

    @Test
    fun realAepResolvesThroughTheLossGate() {
        val bytes = ImportDocumentResolverTest::class.java
            .getResourceAsStream("/aep/synthetic.aep")!!.readBytes()
        assertTrue(ImportDocumentResolver.isAep(bytes))
        val report = ImportDocumentResolver.analyzeAep(bytes)
        assertTrue(report.losses.isNotEmpty())
        // resolve() without an explicit allowance refuses a lossy file.
        assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentResolver.resolve(bytes, tempDir())
        }
        val out = ImportDocumentResolver.importAep(bytes, allowLosses = true)
        assertTrue(out.wasAep)
        assertEquals(false, out.wasAeInterchange)
        assertEquals(2, JSONObject(out.canonicalJson).getInt("schema"))
        assertEquals(report.losses.sumOf { it.count }, out.aepReport!!.losses.sumOf { it.count })
    }

    @Test
    fun garbageBytesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentResolver.resolve(
                byteArrayOf(1, 2, 3, 4, 5), tempDir()
            )
        }
    }
}
