package com.codeair.effectcraft.data.aep

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AepFootageResolver]: a stored path from another machine resolves against
 * the folder the project lives in. */
class AepFootageResolverTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("aep-footage-", "")
        assertTrue(dir.delete())
        assertTrue(dir.mkdir())
        return dir
    }

    @Test
    fun windowsPathResolvesByBasename() {
        val dir = tempDir()
        val shot = File(dir, "shot_0001.png")
        assertTrue(shot.createNewFile())
        assertEquals(shot, AepFootageResolver.resolve("E:\\Projekte\\ae-corpus\\scratch\\seq\\shot_0001.png", listOf(dir)))
    }

    @Test
    fun forwardSlashesAndDriveLetterResolve() {
        val dir = tempDir()
        val shot = File(dir, "clip.mp4")
        assertTrue(shot.createNewFile())
        assertEquals(shot, AepFootageResolver.resolve("D:/media/clip.mp4", listOf(dir)))
    }

    @Test
    fun longestSuffixWinsOverBasename() {
        val dir = tempDir()
        val bare = File(dir, "shot.png")
        assertTrue(bare.createNewFile())
        val nested = File(File(dir, "seq"), "shot.png")
        assertTrue(nested.parentFile.mkdir())
        assertTrue(nested.createNewFile())
        // Both exist; the longer suffix (seq/shot.png) is the precise match.
        assertEquals(nested, AepFootageResolver.resolve("E:/x/seq/shot.png", listOf(dir)))
    }

    @Test
    fun storedPathAsIsWins() {
        val dir = tempDir()
        val shot = File(dir, "real.png")
        assertTrue(shot.createNewFile())
        assertEquals(shot, AepFootageResolver.resolve(shot.absolutePath, listOf(File("/nowhere"))))
    }

    @Test
    fun sequenceFolderResolves() {
        val dir = tempDir()
        val seq = File(dir, "seq")
        assertTrue(seq.mkdir())
        assertEquals(seq, AepFootageResolver.resolve("E:/Projekte/ae-corpus/scratch/seq", listOf(dir)))
    }

    @Test
    fun missingReturnsNull() {
        val dir = tempDir()
        assertNull(AepFootageResolver.resolve("E:/gone/clip.mp4", listOf(dir)))
        assertNull(AepFootageResolver.resolve("", listOf(dir)))
        assertNull(AepFootageResolver.resolve("E:/gone/clip.mp4", emptyList()))
    }

    @Test
    fun resolverForReturnsAbsolutePaths() {
        val dir = tempDir()
        val shot = File(dir, "a.png")
        assertTrue(shot.createNewFile())
        val resolver = AepFootageResolver.resolverFor(listOf(dir))
        assertEquals(shot.absolutePath, resolver("E:/x/a.png"))
        assertNull(resolver("E:/x/missing.png"))
    }
}
