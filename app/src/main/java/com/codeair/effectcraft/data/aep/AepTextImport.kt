package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.aep.AepTextDoc.AutoKern
import com.codeair.effectcraft.data.aep.AepTextDoc.Caps
import com.codeair.effectcraft.data.aep.AepTextDoc.FontBaseline
import com.codeair.effectcraft.data.aep.AepTextDoc.Justification
import com.codeair.effectcraft.data.aep.AepTextDoc.ParagraphRun
import com.codeair.effectcraft.data.aep.AepTextDoc.StyleRun
import com.codeair.effectcraft.data.aep.AepTextDoc.TextDocument
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source Text import: the [TextDocument] decoded from a layer's `btdk` list
 * becomes the canonical TextDoc value of the layer's Source Text property.
 *
 * A typed port of the official EffectCraft `import/text.rs` (`text_doc`,
 * `char_style`, `para_style`, `justify`, `report_text_extras`). The base
 * style and paragraph settings are the first run's and the first paragraph's;
 * `runs` and `paragraphs` are written only when the text is not uniform, as
 * the canonical TextDoc does. Fonts are kept by name; whether a font is
 * installed on this device is not checked here (the official importer reports
 * missing fonts separately — this build has no font registry to check).
 */
object AepTextImport {

    /** The longest text kept (characters); longer text is cut and reported. */
    const val MAX_TEXT_CHARS = 1 shl 20

    /** U+0003 (END OF TEXT): After Effects' other paragraph break besides `\r`. */
    const val ETX = '\u0003'

    /** The first paragraph of a stored text, at most 64 characters. */
    fun firstParagraph(raw: String): String =
        raw.split('\r', ETX).firstOrNull().orEmpty().take(64)

    // ------------------------------------------------------------------ helpers

    private fun finite(v: Double, lo: Double, hi: Double): Double? =
        if (v.isFinite()) v.coerceIn(lo, hi) else null

    /** `v` within `lo..=hi`: outside is clamped, not-a-number takes [default]; both reported. */
    private fun fit(v: Double, lo: Double, hi: Double, default: Double, field: String, what: String, col: AepImportReport.Collector): Double {
        if (!v.isFinite()) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: $field is not a number, $default is used"
            )
            return default
        }
        val fitted = v.coerceIn(lo, hi)
        if (kotlin.math.abs(fitted - v) > 1e-9) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: $field $v is outside $lo..$hi, $fitted is used"
            )
        }
        return fitted
    }

    /** ARGB 0..1 floats to straight RGBA 0..1, or null when a channel is not a number. */
    private fun rgba(argb: DoubleArray): DoubleArray? {
        val (a, r, g, b) = argb
        fun c(v: Double): Double? = if (v.isFinite()) v.coerceIn(0.0, 1.0) else null
        val cr = c(r) ?: return null
        val cg = c(g) ?: return null
        val cb = c(b) ?: return null
        val ca = c(a) ?: return null
        return doubleArrayOf(cr, cg, cb, ca)
    }

    /** Reports a colour whose channels [rgba] clipped to `0..=1`. */
    private fun clipped(argb: DoubleArray, which: String, what: String, col: AepImportReport.Collector) {
        val outside = argb.filter { it < -1e-9 || it > 1.0 + 1e-9 }.map { it.toString() }
        if (outside.isNotEmpty()) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: a $which colour channel is outside 0..1 (${outside.joinToString(", ")}), clipped"
            )
        }
    }

    private fun reportControlCharacters(text: String, what: String, col: AepImportReport.Collector) {
        val seen = ArrayList<Char>()
        for (c in text) {
            if (c.isISOControl() && c != '\r' && c != '\n' && c != '\t') {
                if (!seen.contains(c) && seen.size < 8) seen.add(c)
            }
        }
        if (seen.isNotEmpty()) {
            val list = seen.joinToString(", ") { "U+%04X".format(it.code) }
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: the text contains control characters whose meaning is not decoded ($list); they are kept as they are"
            )
        }
    }

    // ------------------------------------------------------------------ style

    /** The stored justification is relative to the paragraph direction. */
    private fun justify(j: Justification, rtl: Boolean, what: String, col: AepImportReport.Collector): String =
        when (j) {
            Justification.Left -> if (rtl) "Right" else "Left"
            Justification.Right -> if (rtl) "Left" else "Right"
            Justification.Center -> "Center"
            Justification.FullLeft -> if (rtl) "JustifyLastRight" else "JustifyLastLeft"
            Justification.FullRight -> if (rtl) "JustifyLastLeft" else "JustifyLastRight"
            Justification.FullCenter -> "JustifyLastCenter"
            Justification.FullAll -> "JustifyAll"
            is Justification.Other -> {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: justification code ${j.code} is not decoded, imported as left"
                )
                "Left"
            }
        }

    private fun charStyle(doc: TextDocument, run: StyleRun, what: String, col: AepImportReport.Collector): JSONObject {
        val font = doc.fontOf(run)
        if (font == null) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: a style run names no readable font, the default font is used"
            )
        }
        val fill = rgba(run.fillArgb)
        if (fill == null) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: a fill colour is not valid, the default is used"
            )
        } else {
            clipped(run.fillArgb, "fill", what, col)
        }
        val stroke = run.strokeArgb?.let { rgba(it) }
        if (run.strokeArgb != null && stroke == null) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: a stroke colour is not valid, the default is used"
            )
        } else if (run.strokeArgb != null) {
            clipped(run.strokeArgb, "stroke", what, col)
        }
        val style = JSONObject()
            .put("font", font ?: "Inter")
            .put("style", "Regular")
            .put("size", fit(run.fontSize, 0.1, 10_000.0, 72.0, "the font size", what, col))
            .put("fill", jsonArray(fill ?: doubleArrayOf(1.0, 1.0, 1.0, 1.0)))
            .put("stroke", jsonArray(stroke ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)))
            .put("stroke_width", fit(run.strokeWidth, 0.0, 10_000.0, 0.0, "the stroke width", what, col))
            .put("apply_fill", run.applyFill)
            .put("apply_stroke", run.applyStroke)
            .put("tracking", fit(run.tracking, -100_000.0, 100_000.0, 0.0, "the tracking", what, col))
            .put("leading", if (run.autoLeading) JSONObject.NULL else run.leading?.let { finite(it, 0.0, 100_000.0) } ?: JSONObject.NULL)
            .put("baseline_shift", fit(run.baselineShift, -100_000.0, 100_000.0, 0.0, "the baseline shift", what, col))
            .put("h_scale", fit(run.horizontalScale * 100.0, 1.0, 10_000.0, 100.0, "the horizontal scale", what, col))
            .put("v_scale", fit(run.verticalScale * 100.0, 1.0, 10_000.0, 100.0, "the vertical scale", what, col))
            .put("tsume", fit(run.tsume * 100.0, 0.0, 100.0, 0.0, "the tsume", what, col))
            .put("faux_bold", run.fauxBold)
            .put("faux_italic", run.fauxItalic)
            .put("all_caps", run.caps == Caps.All)
            .put("small_caps", run.caps == Caps.Small)
            .put(
                "baseline", when (run.baseline) {
                    FontBaseline.Normal -> "Normal"
                    FontBaseline.Superscript -> "Superscript"
                    FontBaseline.Subscript -> "Subscript"
                    FontBaseline.Other -> {
                        col.lose(
                            AepImportReport.LossCategory.TextLayers,
                            "$what: superscript / subscript code ${run.baselineCode} is not decoded, imported as normal"
                        )
                        "Normal"
                    }
                }
            )
            .put(
                "kerning", when (run.autoKern) {
                    AutoKern.Metrics -> "Metrics"
                    AutoKern.Optical -> "Optical"
                    AutoKern.None -> 0.0
                    AutoKern.Other -> {
                        col.lose(
                            AepImportReport.LossCategory.TextLayers,
                            "$what: kerning code ${run.autoKernCode} is not decoded, metrics kerning used"
                        )
                        "Metrics"
                    }
                }
            )
            .put("ligatures", run.ligatures)
            .put("tate_chu_yoko", run.baselineDirection == 3L)
            .put("vertical_roman_upright", false)
        if (run.caps == Caps.Other) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: capitalisation code ${run.capsCode} is not decoded, imported as normal"
            )
        }
        return style
    }

    /** The paragraph settings of one stored paragraph. */
    private fun paraStyle(p: ParagraphRun, what: String, col: AepImportReport.Collector): JSONObject {
        val px = { v: Double -> finite(v, -100_000.0, 100_000.0) ?: 0.0 }
        if (p.leadingType != 0L) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: the Japanese leading model is not imported, Roman leading used"
            )
        }
        val rtl = p.direction == 1L
        return JSONObject()
            .put("justify", justify(p.justification, rtl, what, col))
            .put("indent_left", px(p.startIndent))
            .put("indent_right", px(p.endIndent))
            .put("indent_first", px(p.firstLineIndent))
            .put("space_before", px(p.spaceBefore))
            .put("space_after", px(p.spaceAfter))
            .put(
                "direction", when (p.direction) {
                    0L -> "Ltr"
                    1L -> "Rtl"
                    else -> {
                        col.lose(
                            AepImportReport.LossCategory.TextLayers,
                            "$what: paragraph direction ${p.direction} is not decoded, left to right used"
                        )
                        "Ltr"
                    }
                }
            )
            .put("composer", if (p.everyLineComposer) "EveryLine" else "SingleLine")
            .put("hanging_punctuation", p.hangingRoman)
    }

    /** Character settings EffectCraft has no field for, reported once per text. */
    private fun reportCharacterExtras(doc: TextDocument, what: String, col: AepImportReport.Collector) {
        val runs = doc.styleRuns
        if (runs.any { it.noBreak }) {
            col.lose(AepImportReport.LossCategory.TextLayers, "$what: No Break is not imported")
        }
        if (runs.any { it.applyStroke && it.lineJoin != 0L }) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: the stroke line join is not imported, miter used"
            )
        }
        if (runs.any { it.digitSet > 1 || it.digitSet < 0 }) {
            col.lose(AepImportReport.LossCategory.TextLayers, "$what: the digit set is not imported")
        }
        if (doc.lineOrientation != null) {
            val tcy = runs.any { it.baselineDirection == 3L }
            if (tcy || runs.any { it.baselineDirection != 1L && it.baselineDirection != 3L }) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: the baseline direction of vertical text is not imported"
                )
            }
        }
    }

    // ------------------------------------------------------------------ document

    /**
     * The Source Text value of [doc], with what cannot be mapped reported for
     * the layer [what]. The base style and paragraph are the first run's and
     * the first paragraph's; `runs` and `paragraphs` are written only when the
     * text is not uniform.
     */
    fun textDoc(doc: TextDocument, what: String, col: AepImportReport.Collector): JSONObject {
        val stored = doc.rawText.removeSuffix("\r")
        // Positions are character indices (Unicode scalar values), as in
        // After Effects; Kotlin strings count UTF-16 units, so cut and count
        // by code point.
        val codePoints = stored.codePoints().toArray()
        val kept = codePoints.take(MAX_TEXT_CHARS)
        val text = String(
            kept.map { if (it == ETX.code) '\r'.code else it }.toIntArray(), 0, kept.size
        )
        if (kept.size < codePoints.size) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: the text is longer than $MAX_TEXT_CHARS characters and was cut"
            )
        }
        reportControlCharacters(text, what, col)
        val chars = kept.size

        // Character runs, cut to the text (the last stored run also covers the final `\r`).
        val runs = ArrayList<JSONObject>()
        var at = 0
        for (run in doc.styleRuns) {
            if (at >= chars) break
            val len = minOf(run.length, chars - at)
            runs.add(
                JSONObject()
                    .put("len", len)
                    .put("style", charStyle(doc, run, what, col))
            )
            at += len
        }
        if (runs.isEmpty() && doc.styleRuns.isNotEmpty()) {
            // Empty text: the first run's style is the style text typed into it gets.
            runs.add(
                JSONObject()
                    .put("len", 0)
                    .put("style", charStyle(doc, doc.styleRuns.first(), what, col))
            )
        }
        if (runs.isNotEmpty() && at < chars) {
            val last = runs.last()
            last.put("len", last.getInt("len") + (chars - at))
        }
        if (runs.isEmpty()) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: the text has no readable character style, the default style is used"
            )
        }

        // Paragraph settings, one per stored paragraph.
        val paras = doc.paragraphs.map { paraStyle(it, what, col) }

        // The base style and paragraph are the first run's and the first
        // paragraph's; runs and paragraphs stay empty while the text is
        // uniform, so single-style documents serialize as before.
        val baseStyle = if (runs.isNotEmpty()) {
            runs.first().getJSONObject("style")
        } else {
            charStyle(doc, StyleRun.SAMPLED, what, col)
        }
        val basePara = paras.firstOrNull()

        val out = JSONObject()
            .put("text", text)
            .put("font", baseStyle.getString("font"))
            .put("style", "Regular")
            .put("size", baseStyle.getDouble("size"))
            .put("fill", baseStyle.getJSONArray("fill"))
            .put("stroke", baseStyle.getJSONArray("stroke"))
            .put("stroke_width", baseStyle.getDouble("stroke_width"))
            .put("apply_fill", baseStyle.getBoolean("apply_fill"))
            .put("apply_stroke", baseStyle.getBoolean("apply_stroke"))
            .put("stroke_over_fill", doc.styleRuns.firstOrNull()?.strokeOverFill ?: true)
            .put("line_join", "Miter")
            .put("tracking", baseStyle.getDouble("tracking"))
            .put("leading", baseStyle.opt("leading"))
            .put("justify", basePara?.getString("justify") ?: "Left")
            .put("baseline_shift", baseStyle.getDouble("baseline_shift"))
            .put("h_scale", baseStyle.getDouble("h_scale"))
            .put("v_scale", baseStyle.getDouble("v_scale"))
            .put("faux_bold", baseStyle.getBoolean("faux_bold"))
            .put("faux_italic", baseStyle.getBoolean("faux_italic"))
            .put("all_caps", baseStyle.getBoolean("all_caps"))
            .put("small_caps", baseStyle.getBoolean("small_caps"))
            .put("box_size", JSONObject.NULL)
            .put("box_pos", jsonArray(doubleArrayOf(0.0, 0.0)))
            .put("tsume", baseStyle.getDouble("tsume"))
            .put("baseline", baseStyle.getString("baseline"))
            .put("kerning", baseStyle.opt("kerning"))
            .put("ligatures", baseStyle.getBoolean("ligatures"))
            .put("tate_chu_yoko", baseStyle.getBoolean("tate_chu_yoko"))
            .put("vertical_roman_upright", false)
            .put("indent_left", basePara?.getDouble("indent_left") ?: 0.0)
            .put("indent_right", basePara?.getDouble("indent_right") ?: 0.0)
            .put("indent_first", basePara?.getDouble("indent_first") ?: 0.0)
            .put("space_before", basePara?.getDouble("space_before") ?: 0.0)
            .put("space_after", basePara?.getDouble("space_after") ?: 0.0)
            .put("direction", basePara?.getString("direction") ?: "Ltr")
            .put("composer", basePara?.getString("composer") ?: "EveryLine")
            .put("hanging_punctuation", basePara?.getBoolean("hanging_punctuation") ?: false)
            .put("vertical", doc.lineOrientation == 2L)

        // Stroke over fill is one switch for the whole text (per run in AE).
        if (doc.styleRuns.isNotEmpty()) {
            val first = doc.styleRuns.first().strokeOverFill
            if (doc.styleRuns.any { it.strokeOverFill != first && it.applyStroke }) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: stroke over fill differs between characters, the first character's order is used"
                )
            }
        }
        if (doc.kerning.isNotEmpty()) {
            col.lose(AepImportReport.LossCategory.TextLayers, "$what: manual kerning is not imported")
        }
        when (val orientation = doc.lineOrientation) {
            null -> Unit
            2L -> Unit // vertical, right to left: `vertical` above
            else -> col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: line orientation $orientation is not decoded, imported as horizontal"
            )
        }
        reportCharacterExtras(doc, what, col)

        doc.textBox?.let { box ->
            val finiteBox = listOf(box.x, box.y, box.width, box.height)
                .all { it.isFinite() && kotlin.math.abs(it) <= AepEffectKeys.LIMIT }
            if (finiteBox) {
                out.put("box_pos", jsonArray(doubleArrayOf(box.x, box.y)))
                out.put("box_size", jsonArray(doubleArrayOf(maxOf(box.width, 1.0), maxOf(box.height, 1.0))))
            } else {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: the text box is not valid, imported as point text"
                )
            }
        }

        // Runs and paragraphs are kept only when the text is not uniform.
        val distinctRuns = mergeRuns(runs)
        if (distinctRuns.size > 1) out.put("runs", JSONArray(distinctRuns))
        if (paras.size > 1) out.put("paragraphs", JSONArray(paras))

        if (doc.incompleteRuns > 0) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: ${doc.incompleteRuns} damaged text style or paragraph runs (their missing values were taken from the run before)"
            )
        }
        if (doc.undecodedStyleFields.isNotEmpty()) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: character style fields not imported (they differ from the default): /${doc.undecodedStyleFields.joinToString(" /")}"
            )
        }
        if (doc.undecodedParagraphFields.isNotEmpty()) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$what: paragraph fields not imported (they differ from the default): /${doc.undecodedParagraphFields.joinToString(" /")}"
            )
        }
        return out
    }

    /** Adjacent runs with the same style merge into one (as `set_runs` does). */
    private fun mergeRuns(runs: List<JSONObject>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (run in runs) {
            val len = run.getInt("len")
            if (len <= 0) continue
            val last = out.lastOrNull()
            if (last != null && last.getJSONObject("style").toString() == run.getJSONObject("style").toString()) {
                last.put("len", last.getInt("len") + len)
            } else {
                out.add(JSONObject(run.toString()))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ extras

    /** Whether a group holds any stored value (a set, keyed or expression property). */
    private fun hasStored(group: AepTypedView.PropGroup, depth: Int): Boolean {
        if (depth > 32) return true
        return group.entries().any { entry ->
            when (val node = entry.node) {
                is AepTypedView.PropNode.Group -> hasStored(node.group, depth + 1)
                is AepTypedView.PropNode.Property -> {
                    val p = node.property
                    p.flags() == 1L || p.isAnimated() || p.expression() != null
                }
                else -> false
            }
        }
    }

    /**
     * The parts of a text layer besides its first text document that are not
     * imported. Animators are imported by AepTextAnimImport (their own losses
     * are recorded there); Path Options and More Options are reported when
     * they hold stored values.
     */
    fun reportTextExtras(layer: AepTypedView.Layer, what: String, col: AepImportReport.Collector) {
        val sourceText = layer.sourceTextProperty()
        if (sourceText != null) {
            if (sourceText.isAnimated() || layer.textDocumentCount() > 1) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: Source Text keyframes are not imported (the first text document is)"
                )
            }
            if (sourceText.expression() != null) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: the Source Text expression is not imported"
                )
            }
        }
        val textProps = layer.properties()?.get("ADBE Text Properties") as? AepTypedView.PropNode.Group
            ?: return
        for (entry in textProps.group.entries()) {
            val group = (entry.node as? AepTypedView.PropNode.Group)?.group ?: continue
            when (entry.matchName) {
                "ADBE Text Animators" -> {
                    // Imported by AepTextAnimImport; nothing to report here.
                }
                "ADBE Text Path Options" -> {
                    if (hasStored(group, 0)) {
                        col.lose(
                            AepImportReport.LossCategory.TextLayers,
                            "$what: Path Options are not imported"
                        )
                    }
                }
                "ADBE Text More Options" -> {
                    if (hasStored(group, 0)) {
                        col.lose(
                            AepImportReport.LossCategory.TextLayers,
                            "$what: More Options are not imported"
                        )
                    }
                }
            }
        }
    }

    private fun jsonArray(values: DoubleArray): JSONArray {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr
    }
}
