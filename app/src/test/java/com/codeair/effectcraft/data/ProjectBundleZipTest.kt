package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectBundleZipTest {
    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    private val project = """{"schema": 2}""".toByteArray(Charsets.UTF_8)

    @Test
    fun unpacksProjectJsonAndMedia() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val dir = tempDir()
        try {
            val unpacked = ProjectBundleZip.unpack(
                zipBytes(listOf("project.json" to project, "media/clip.png" to png)),
                dir
            )
            assertEquals("project.json", unpacked.projectFileName)
            assertEquals("""{"schema": 2}""", unpacked.projectDocument.toString(Charsets.UTF_8))
            assertEquals(listOf("media/clip.png"), unpacked.mediaFiles)
            assertEquals(7, File(unpacked.mediaDir, "media/clip.png").length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsBundleWithoutProjectDocumentAndCleansPartialExtraction() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(zipBytes(listOf("media/clip.png" to byteArrayOf(1, 2, 3))), dir)
            }
            assertFalse(File(dir, "media/clip.png").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonZipBytes() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(project, dir)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun traversalAndAbsoluteEntriesNeverEscapeDestination() {
        val dir = tempDir()
        val escapeName = "bundle-zip-escape-${System.nanoTime()}.txt"
        val outside = File(dir.parentFile, escapeName)
        try {
            val unpacked = ProjectBundleZip.unpack(
                zipBytes(listOf(
                    "project.json" to project,
                    "../$escapeName" to "evil".toByteArray(),
                    "/absolute.txt" to "evil".toByteArray(),
                    "C:/drive.txt" to "evil".toByteArray()
                )),
                dir
            )
            assertEquals("project.json", unpacked.projectFileName)
            assertTrue(unpacked.mediaFiles.isEmpty())
            assertFalse(outside.exists())
            assertFalse(File(dir, "absolute.txt").exists())
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
                zipBytes(listOf(
                    "export/project.json" to project,
                    "export/media/a.png" to byteArrayOf(9, 9)
                )),
                dir
            )
            assertEquals("export/project.json", unpacked.projectFileName)
            assertEquals(listOf("export/media/a.png"), unpacked.mediaFiles)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateNormalizedEntriesAreRejectedAndCleaned() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(
                    zipBytes(listOf(
                        "project.json" to project,
                        "media/a.png" to byteArrayOf(1),
                        "media/./a.png" to byteArrayOf(2)
                    )),
                    dir
                )
            }
            assertFalse(File(dir, "media/a.png").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nonEmptyDestinationIsNeverOverwritten() {
        val dir = tempDir()
        dir.mkdirs()
        val sentinel = File(dir, "keep.txt")
        sentinel.writeText("owner data")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(zipBytes(listOf("project.json" to project)), dir)
            }
            assertEquals("owner data", sentinel.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun symbolicLinkDestinationIsRejected() {
        val real = tempDir()
        val link = File(real.parentFile, "bundle-zip-link-${System.nanoTime()}")
        real.mkdirs()
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                ProjectBundleZip.unpack(zipBytes(listOf("project.json" to project)), link)
            }
        } finally {
            link.delete()
            real.deleteRecursively()
        }
    }
}
