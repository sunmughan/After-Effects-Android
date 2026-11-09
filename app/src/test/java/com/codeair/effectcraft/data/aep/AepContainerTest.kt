package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Container round-trip and hostile-input tests (port of the official `container` tests). */
class AepContainerTest {

    @Test
    fun syntheticAepRoundTripIsByteIdentical() {
        val bytes = resourceBytes("/aep/synthetic.aep")
        val file = AepContainer.parse(bytes)
        assertEquals(31, file.chunks.size)
        assertEquals(2677, file.walk().count())
        assertEquals(bytes.toList(), AepContainer.write(file).toList())
    }

    @Test
    fun syntheticAepVersion() {
        val bytes = resourceBytes("/aep/synthetic.aep")
        val file = AepContainer.parse(bytes)
        assertEquals("26.2.1x2", file.fileVersion()?.text())
        assertEquals("26.2.1x2", file.savedByVersion()?.text())
        assertEquals(97, file.headRevision())
    }

    @Test
    fun walkIsDepthFirst() {
        val inner = AepContainer.Chunk.list("LST2", listOf(AepContainer.Chunk.data("leaf", byteArrayOf(1))))
        val root = AepContainer.Chunk.list(
            "LST1",
            listOf(AepContainer.Chunk.data("a", byteArrayOf(2)), inner)
        )
        val file = AepContainer.AepFile(listOf(root), byteArrayOf())
        val ids = file.walk().map { it.second.id to it.second.listType() }.toList()
        assertEquals(
            listOf("LIST" to "LST1", "a" to null, "LIST" to "LST2", "leaf" to null),
            ids
        )
    }

    @Test
    fun emptyDataChunkRoundTrips() {
        val file = AepContainer.AepFile(
            listOf(AepContainer.Chunk.data("abcd", byteArrayOf())), byteArrayOf()
        )
        val written = AepContainer.write(file)
        val parsed = AepContainer.parse(written)
        assertEquals(1, parsed.chunks.size)
        assertEquals("abcd", parsed.chunks[0].id)
    }

    @Test
    fun oddLengthDataIsPadded() {
        val file = AepContainer.AepFile(
            listOf(AepContainer.Chunk.data("abcd", byteArrayOf(1, 2, 3))), byteArrayOf()
        )
        val written = AepContainer.write(file)
        // 8-byte header + 3 bytes + 1 pad byte, inside RIFX (8) + form (4).
        assertEquals(12 + 12, written.size)
        val parsed = AepContainer.parse(written)
        assertEquals(listOf(1.toByte(), 2, 3), parsed.chunks[0].bytes()!!.toList())
    }

    @Test
    fun parseRejectsTruncatedHeader() {
        assertThrows(AepError::class.java) { AepContainer.parse(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun parseRejectsWrongMagic() {
        val bytes = AepContainer.write(
            AepContainer.AepFile(
                listOf(AepContainer.Chunk.data("abcd", byteArrayOf(1, 2, 3, 4))), byteArrayOf()
            )
        )
        "XXXX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        assertThrows(AepError::class.java) { AepContainer.parse(bytes) }
    }

    @Test
    fun parseRejectsWrongForm() {
        val bytes = AepContainer.write(
            AepContainer.AepFile(
                listOf(AepContainer.Chunk.data("abcd", byteArrayOf(1, 2, 3, 4))), byteArrayOf()
            )
        )
        "XXXX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        assertThrows(AepError::class.java) { AepContainer.parse(bytes) }
    }

    @Test
    fun parseRejectsOversizedChunk() {
        val bytes = ByteArray(16)
        "RIFX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        // Form size 100, far past the end of the 16-byte file.
        bytes[7] = 100
        "Egg!".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        assertThrows(AepError::class.java) { AepContainer.parse(bytes) }
    }

    @Test
    fun parseKeepsOverDeepNestingOpaqueInsteadOfCrashing() {
        // A file nested deeper than the 64-level limit parses fine: the
        // levels past the limit are kept as opaque lists, never a crash.
        var chunk = AepContainer.Chunk.data("leaf", byteArrayOf(1))
        repeat(70) { chunk = AepContainer.Chunk.list("LST1", listOf(chunk)) }
        val file = AepContainer.AepFile(listOf(chunk), byteArrayOf())
        val parsed = AepContainer.parse(AepContainer.write(file))
        assertEquals(1, parsed.chunks.size)
        assertTrue(parsed.walk().any { it.second.body is AepContainer.ChunkBody.OpaqueList })
        assertTrue(parsed.walk().any { it.second.body is AepContainer.ChunkBody.List })
    }

    @Test
    fun isListAndIs() {
        val list = AepContainer.Chunk.list("tdbs", emptyList())
        assertTrue(list.isList("tdbs"))
        assertTrue(list.`is`("LIST"))
        assertEquals("tdbs", list.listType())
        val data = AepContainer.Chunk.data("cdta", byteArrayOf(1))
        assertTrue(data.`is`("cdta"))
        assertEquals(null, data.listType())
    }

    internal companion object {
        fun resourceBytes(path: String): ByteArray =
            AepContainerTest::class.java.getResourceAsStream(path)
                ?.readBytes()
                ?: throw AssertionError("missing test resource $path")
    }
}
