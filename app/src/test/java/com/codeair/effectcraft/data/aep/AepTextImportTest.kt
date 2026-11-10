package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.aep.AepTextDoc.Justification
import com.codeair.effectcraft.data.aep.AepTextDoc.ParagraphRun
import com.codeair.effectcraft.data.aep.AepTextDoc.StyleRun
import com.codeair.effectcraft.data.aep.AepTextDoc.TextBox
import com.codeair.effectcraft.data.aep.AepTextDoc.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Source Text value: base style, runs, paragraphs, box, and losses. */
class AepTextImportTest {

    private fun para(length: Int, justification: Justification = Justification.Left, direction: Long = 0): ParagraphRun =
        ParagraphRun(
            length = length, justification = justification,
            firstLineIndent = 0.0, startIndent = 0.0, endIndent = 0.0,
            spaceBefore = 0.0, spaceAfter = 0.0, leadingType = 0,
            autoHyphenate = true, hangingRoman = false, everyLineComposer = false,
            direction = direction
        )

    private fun run(
        length: Int,
        fontSize: Double = 72.0,
        fillArgb: DoubleArray = doubleArrayOf(1.0, 1.0, 0.0, 0.0),
        fontIndex: Int = 0
    ): StyleRun = StyleRun.SAMPLED.copy(length = length, fontSize = fontSize, fillArgb = fillArgb, fontIndex = fontIndex)

    private fun doc(
        text: String = "Hello",
        runs: List<StyleRun> = listOf(run(6)),
        paragraphs: List<ParagraphRun> = listOf(para(6)),
        textBox: TextBox? = null,
        kerning: List<AepTextDoc.KernRun> = emptyList(),
        lineOrientation: Long? = null,
        fonts: List<String> = listOf("MyriadPro-Regular")
    ): TextDocument = TextDocument(
        text = text, rawText = "$text\r",
        fontSize = runs.firstOrNull()?.fontSize ?: 72.0,
        fillArgb = runs.firstOrNull()?.fillArgb ?: doubleArrayOf(1.0, 1.0, 1.0, 1.0),
        fontIndex = 0, font = fonts.getOrNull(0), fonts = fonts,
        paragraphs = paragraphs, styleRuns = runs, textBox = textBox,
        incompleteRuns = 0, undecodedStyleFields = emptyList(),
        undecodedParagraphFields = emptyList(), kerning = kerning,
        lineOrientation = lineOrientation
    )

    private fun losses(col: AepImportReport.Collector): List<String> =
        col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true))
            .losses.flatMap { it.details }

    @Test
    fun uniformTextImportsWithBaseStyleOnly() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(doc(), "t", col)
        assertEquals("Hello", out.getString("text"))
        assertEquals("MyriadPro-Regular", out.getString("font"))
        assertEquals(72.0, out.getDouble("size"), 0.0)
        assertEquals("[1,0,0,1]", out.getJSONArray("fill").toString())
        assertEquals("Left", out.getString("justify"))
        assertFalse(out.has("runs"))
        assertFalse(out.has("paragraphs"))
        assertTrue(out.isNull("box_size"))
        assertTrue(losses(col).isEmpty())
    }

    @Test
    fun twoRunsImportAsRunsWithFirstRunBase() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(
            doc(
                text = "Hello\rWorld", runs = listOf(run(6), run(6, 24.0, doubleArrayOf(1.0, 0.0, 0.0, 1.0))),
                paragraphs = listOf(para(6), para(6, Justification.Center))
            ),
            "t", col
        )
        assertEquals("Hello\rWorld", out.getString("text"))
        assertEquals(72.0, out.getDouble("size"), 0.0)
        val runs = out.getJSONArray("runs")
        assertEquals(2, runs.length())
        assertEquals(6, runs.getJSONObject(0).getInt("len"))
        assertEquals(24.0, runs.getJSONObject(1).getJSONObject("style").getDouble("size"), 0.0)
        assertEquals("[0,0,1,1]", runs.getJSONObject(1).getJSONObject("style").getJSONArray("fill").toString())
        val paras = out.getJSONArray("paragraphs")
        assertEquals(2, paras.length())
        assertEquals("Center", paras.getJSONObject(1).getString("justify"))
        assertTrue(losses(col).isEmpty())
    }

    @Test
    fun clippedFillIsReportedAndClamped() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(
            doc(runs = listOf(run(6, fillArgb = doubleArrayOf(1.0, 2.0, 0.0, 0.0)))),
            "t", col
        )
        assertEquals("[1,0,0,1]", out.getJSONArray("fill").toString())
        val details = losses(col)
        assertEquals(1, details.size)
        assertTrue(details[0].contains("fill colour channel is outside 0..1"))
    }

    @Test
    fun boxTextImportsItsBox() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(doc(textBox = TextBox(10.0, 20.0, 300.0, 150.0)), "t", col)
        assertEquals("[300,150]", out.getJSONArray("box_size").toString())
        assertEquals("[10,20]", out.getJSONArray("box_pos").toString())
        assertTrue(losses(col).isEmpty())
    }

    @Test
    fun rtlParagraphSwapsLeftAndRight() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(doc(paragraphs = listOf(para(6, Justification.Left, 1))), "t", col)
        assertEquals("Right", out.getString("justify"))
        assertEquals("Rtl", out.getString("direction"))
        assertTrue(losses(col).isEmpty())
    }

    @Test
    fun verticalOrientationSetsVerticalAndUnknownOrientationIsALoss() {
        val col = AepImportReport.Collector()
        val out = AepTextImport.textDoc(doc(lineOrientation = 2), "t", col)
        assertTrue(out.getBoolean("vertical"))
        assertTrue(losses(col).isEmpty())

        val col2 = AepImportReport.Collector()
        val out2 = AepTextImport.textDoc(doc(lineOrientation = 5), "t", col2)
        assertFalse(out2.getBoolean("vertical"))
        assertTrue(losses(col2).any { it.contains("line orientation 5 is not decoded") })
    }

    @Test
    fun manualKerningIsALoss() {
        val col = AepImportReport.Collector()
        AepTextImport.textDoc(
            doc(kerning = listOf(AepTextDoc.KernRun(4, 50.0))), "t", col
        )
        assertTrue(losses(col).any { it.contains("manual kerning is not imported") })
    }
}
