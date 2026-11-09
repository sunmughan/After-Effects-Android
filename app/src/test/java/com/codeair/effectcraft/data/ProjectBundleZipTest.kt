package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Batch 3: ZIP project bundles. A bundle is a .zip holding project.json
 * (an .ecproj document or an After Effects interchange document) plus
 * its media files. The unpacker is the trust boundary: entry names are
 * sanitized, sizes are capped, and a bundle without a project document
 * is rejected with a precise error instead of a native parse failure.
 */
class ProjectBundleZipTest {

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
        File(System.getProperty("java.io.tmpdir"), "bundle-zip-test-${System.nanoTime()}")

    @Test
    fun unpacksProjectJsonAndMedia() {
        val project = """{"schema": 2}""".toByteArray(Charsets.UTF_8)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val dir = tempDir()
        try {
            val unpacked = ProjectBundleZip.unpack(
                zipBytes(mapOf("project.json" to project, "media/clip.png" to png)),
                dir
            )

            assertEquals("project.json", unpacked.projectFileName)
            assertEquals("""{"schema": 2}""", unpacked.projectDocument.toString(Charsets.UTF_8))
            assertEquals(listOf("media/clip.png"), unpacked.mediaFiles)
            val mediaFile = File(unpacked.mediaDir, "media/clip.png")
            assertTrue(mediaFile.exists())
            assertEquals(7, mediaFile.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsBundleWithoutProjectDocument() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(
                    zipBytes(mapOf("media/clip.png" to byteArrayOf(1, 2, 3))),
                    dir
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonZipBytes() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack("""{"schema": 2}""".toByteArray(Charsets.UTF_8), dir)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun traversalEntriesNeverEscapeTheDestination() {
        val dir = tempDir()
        val escapeName = "bundle-zip-escape-${System.nanoTime()}.txt"
        val outside = File(dir.parentFile, escapeName)
        try {
            val unpacked = ProjectBundleZip.unpack(
                zipBytes(
                    mapOf(
                        "project.json" to """{"schema": 2}""".toByteArray(Charsets.UTF_8),
                        "../$escapeName" to "evil".toByteArray(Charsets.UTF_8),
                        "/absolute.txt" to "evil".toByteArray(Charsets.UTF_8)
                    )
                ),
                dir
            )

            assertEquals("project.json", unpacked.projectFileName)
            assertTrue(unpacked.mediaFiles.isEmpty())
            assertTrue(!outside.exists())
            assertTrue(!File(dir, "absolute.txt").exists())
        } finally {
            dir.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun nestedProjectJsonIsFound() {
        val dir = tempDir()
        try {
            val unpacked = ProjectBundleZip.unpack(
                zipBytes(
                    mapOf(
                        "export/project.json" to """{"schema": 2}""".toByteArray(Charsets.UTF_8),
                        "export/media/a.png" to byteArrayOf(9, 9)
                    )
                ),
                dir
            )

            assertEquals("export/project.json", unpacked.projectFileName)
            assertEquals(listOf("export/media/a.png"), unpacked.mediaFiles)
        } finally {
            dir.deleteRecursively()
        }
    }
}
