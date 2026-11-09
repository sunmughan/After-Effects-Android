package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.aepx` detection and XML reading (port of the official `aepx` tests). */
class AepxReaderTest {

    private val doc = """<?xml version="1.0" encoding="UTF-8"?>
<AfterEffectsProject>
  <abcd bdata="01020304"/>
  <LST1><abcd bdata="01"/></LST1>
  <string>Hello &amp; goodbye &#65;</string>
  <numS><string>42</string></numS>
  <ppSn><string>1.5</string></ppSn>
</AfterEffectsProject>
""".toByteArray(Charsets.UTF_8)

    @Test
    fun isAepxDetectsXmlAndRejectsRifx() {
        assertTrue(AepxReader.isAepx(doc))
        assertTrue(AepxReader.isAepx("\n  <AfterEffectsProject/>".toByteArray(Charsets.UTF_8)))
        assertTrue(AepxReader.isAepx(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + doc))
        assertFalse(AepxReader.isAepx(AepContainerTest.resourceBytes("/aep/synthetic.aep")))
        assertFalse(AepxReader.isAepx(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun parseAnyReadsAepxAndAep() {
        val file = AepxReader.parseAny(doc)
        assertEquals(5, file.chunks.size)
        assertEquals(listOf(1.toByte(), 2, 3, 4), file.chunks[0].bytes()!!.toList())
        val list = file.chunks[1]
        assertTrue(list.isList("LST1"))
        assertEquals(1, list.children().size)
        assertEquals(listOf(1.toByte()), list.children()[0].bytes()!!.toList())
        val utf8 = file.chunks[2]
        assertTrue(utf8.`is`("Utf8"))
        assertEquals("Hello & goodbye A", String(utf8.bytes()!!, Charsets.UTF_8))
        val numS = file.chunks[3]
        assertEquals(listOf(0.toByte(), 0, 0, 42), numS.bytes()!!.toList())
        val ppSn = file.chunks[4]
        assertEquals(1.5, java.nio.ByteBuffer.wrap(ppSn.bytes()!!).double, 0.0)

        val aep = AepxReader.parseAny(AepContainerTest.resourceBytes("/aep/synthetic.aep"))
        assertEquals(31, aep.chunks.size)
    }

    @Test
    fun parseRejectsDoctype() {
        val bad = """<?xml version="1.0"?>
<!DOCTYPE AfterEffectsProject [ <!ENTITY x "y"> ]>
<AfterEffectsProject/>""".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsWrongRoot() {
        val bad = "<SomethingElse/>".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsUnclosedTag() {
        val bad = "<AfterEffectsProject><abcd bdata=\"01\">".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsMismatchedEndTag() {
        val bad = "<AfterEffectsProject><abcd/></wxyz></AfterEffectsProject>".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsBadHex() {
        val bad = "<AfterEffectsProject><abcd bdata=\"zz\"/></AfterEffectsProject>".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsOddHex() {
        val bad = "<AfterEffectsProject><abcd bdata=\"012\"/></AfterEffectsProject>".toByteArray(Charsets.UTF_8)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun parseRejectsNonUtf8() {
        val bad = "<AfterEffectsProject><string>\u00ff</string></AfterEffectsProject>"
            .toByteArray(Charsets.ISO_8859_1)
        assertThrows(AepError::class.java) { AepxReader.readAepx(bad) }
    }

    @Test
    fun emptyDocumentParses() {
        val file = AepxReader.readAepx("<AfterEffectsProject/>".toByteArray(Charsets.UTF_8))
        assertEquals(0, file.chunks.size)
    }
}
