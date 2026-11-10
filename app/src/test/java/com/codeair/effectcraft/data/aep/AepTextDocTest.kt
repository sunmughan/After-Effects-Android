package com.codeair.effectcraft.data.aep

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the `btdk` PostScript dictionary and the verified text fields. */
class AepTextDocTest {

    /** A PostScript string: `(` + BOM + UTF-16BE + `)`, with `\`, `(`, `)` escaped. */
    private fun utf16Str(s: String): ByteArray {
        val raw = ByteArray(2 + s.length * 2)
        raw[0] = 0xFE.toByte()
        raw[1] = 0xFF.toByte()
        s.forEachIndexed { i, c ->
            raw[2 + i * 2] = (c.code shr 8).toByte()
            raw[2 + i * 2 + 1] = c.code.toByte()
        }
        val out = ByteArrayOutputStream()
        out.write('('.code)
        for (b in raw) {
            if (b == '\\'.code.toByte() || b == '('.code.toByte() || b == ')'.code.toByte()) {
                out.write('\\'.code)
            }
            out.write(b.toInt())
        }
        out.write(')'.code)
        return out.toByteArray()
    }

    private fun btdk(
        text: String,
        fontName: String,
        fontSize: Double,
        fillArgb: List<Double>,
        styleRuns: List<Triple<Int, Double, List<Double>>> = listOf(Triple(text.length, fontSize, fillArgb)),
        paragraphs: List<Pair<Int, Int>> = listOf(text.length to 0),
        box: List<Double>? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(bytes: ByteArray) = out.write(bytes)
        fun write(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        write("/0 << /1 << /0 [ << /0 << /0 << /0 ")
        write(utf16Str(fontName))
        // Closes the font entry, the fonts array and the /1 dict; the /0
        // dict stays open for the frame dictionary below, then closes.
        write(" >> >> >> ] >>")
        if (box != null) {
            // The frame dictionary: top["0"]["8"]["0"][0]["0"], with /2/0 == 1
            // and /1/0 the 16 points tracing the rectangle.
            val coords = listOf(
                box[0], box[1], box[0] + box[2], box[1], box[0] + box[2], box[1] + box[3],
                box[0], box[1] + box[3]
            )
            val points = (coords + coords + coords + coords).joinToString(" ")
            write(" /8 << /0 [ << /0 << /1 << /0 [ $points ] >> /2 << /0 1 >> >> >> ] >>")
        }
        write(" >> /1 << /1 [ << /0 << /0 ")
        write(utf16Str(text))
        write(" /5 << /0 [ ")
        for ((len, justification) in paragraphs) {
            write("<< /0 << /0 << /5 << /0 $justification >> >> >> /1 $len >> ")
        }
        write("] >> /6 << /0 [ ")
        for ((len, size, fill) in styleRuns) {
            write("<< /0 << /0 << /6 << /0 0 /1 $size /53 << /0 << /1 [ ${fill.joinToString(" ")} ] >> >> >> >> >> /1 $len >> ")
        }
        write("] >> >> >> ] >>")
        return out.toByteArray()
    }

    @Test
    fun parseTextDictReadsDictsArraysStringsNamesAndNumbers() {
        val body = "/a << /b 2 /c true >> /d [ 1 2.5 /name ] /e (plain)".toByteArray(Charsets.US_ASCII)
        val top = AepTextDoc.parseTextDict(body)
        assertEquals(2.0, top.get("a")?.get("b")?.asF64()!!, 0.0)
        assertEquals(true, (top.get("a")?.get("c") as AepTextDoc.TextValue.Bool).value)
        val arr = top.get("d") as AepTextDoc.TextValue.Array
        assertEquals(2.5, arr.at(1)?.asF64()!!, 0.0)
        assertEquals("name", (arr.at(2) as AepTextDoc.TextValue.Name).value)
        // A string without the BOM stays bytes, not text.
        assertTrue(top.get("e") is AepTextDoc.TextValue.Bytes)
    }

    @Test
    fun utf16StringDecodesWithBom() {
        val body = "/0 ".toByteArray(Charsets.US_ASCII) + utf16Str("Héllo")
        val top = AepTextDoc.parseTextDict(body)
        assertEquals("Héllo", top.get("0")?.asStr())
    }

    @Test
    fun textDocumentDecodesHello() {
        val doc = AepTextDoc.TextDocument.parse(
            btdk("Hello\r", "MyriadPro-Regular", 72.0, listOf(1.0, 1.0, 0.0, 0.0))
        )
        assertNotNull(doc)
        doc!!
        assertEquals("Hello", doc.text)
        assertEquals("Hello\r", doc.rawText)
        assertEquals(72.0, doc.fontSize, 0.0)
        assertEquals(listOf(1.0, 1.0, 0.0, 0.0), doc.fillArgb.toList())
        assertEquals("MyriadPro-Regular", doc.font)
        assertEquals(listOf("MyriadPro-Regular"), doc.fonts)
        assertEquals(1, doc.paragraphs.size)
        assertEquals(AepTextDoc.Justification.Left, doc.paragraphs[0].justification)
        assertEquals(1, doc.styleRuns.size)
        assertEquals(6, doc.styleRuns[0].length)
        assertEquals(0, doc.incompleteRuns)
        assertTrue(doc.undecodedStyleFields.isEmpty())
        assertNull(doc.textBox)
    }

    @Test
    fun textDocumentDecodesTwoRunsAndTwoParagraphs() {
        val doc = AepTextDoc.TextDocument.parse(
            btdk(
                "Hello\rWorld\r", "Inter-Regular", 48.0, listOf(1.0, 1.0, 1.0, 1.0),
                styleRuns = listOf(
                    Triple(6, 48.0, listOf(1.0, 1.0, 0.0, 0.0)),
                    Triple(6, 24.0, listOf(1.0, 0.0, 0.0, 1.0))
                ),
                paragraphs = listOf(6 to 0, 6 to 2)
            )
        )
        assertNotNull(doc)
        doc!!
        assertEquals("Hello\rWorld", doc.text)
        assertEquals(listOf("Hello", "World"), doc.paragraphTexts())
        assertEquals(2, doc.styleRuns.size)
        assertEquals(48.0, doc.styleRuns[0].fontSize, 0.0)
        assertEquals(24.0, doc.styleRuns[1].fontSize, 0.0)
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), doc.styleRuns[1].fillArgb.toList())
        assertEquals(AepTextDoc.Justification.Center, doc.paragraphs[1].justification)
    }

    @Test
    fun textDocumentDecodesBoxText() {
        val doc = AepTextDoc.TextDocument.parse(
            btdk("Box\r", "Inter-Regular", 36.0, listOf(1.0, 0.0, 0.0, 0.0), box = listOf(10.0, 20.0, 300.0, 150.0))
        )
        assertNotNull(doc)
        val box = doc!!.textBox
        assertNotNull(box)
        assertEquals(10.0, box!!.x, 0.0)
        assertEquals(20.0, box.y, 0.0)
        assertEquals(300.0, box.width, 0.0)
        assertEquals(150.0, box.height, 0.0)
    }

    @Test
    fun textDocumentReturnsNullWithoutText() {
        // A dict with no run at /1/1[0]/0 has no readable text.
        val body = "/0 << /1 [ ] >>".toByteArray(Charsets.US_ASCII)
        assertNull(AepTextDoc.TextDocument.parse(body))
    }

    @Test
    fun firstParagraphTakes64Chars() {
        assertEquals("Hello", AepTextImport.firstParagraph("Hello\rWorld\r"))
        assertEquals("A".repeat(64), AepTextImport.firstParagraph("A".repeat(100) + "\rB"))
    }
}
