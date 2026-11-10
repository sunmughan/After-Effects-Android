package com.codeair.effectcraft.data.aep

/**
 * Text documents (`LIST btdk`) and the fields a text layer carries.
 *
 * A typed port of the official EffectCraft `container/textdoc.rs`: the body of
 * a `btdk` list is not a chunk sequence but a PostScript-like dictionary —
 * `/key value` pairs, `<< >>` dictionaries, `[ ]` arrays, numbers, `true` /
 * `false`, `/names`, and strings as `(` + BOM `fe ff` + UTF-16BE + `)` with
 * `\` escapes. [parseTextDict] reads it (depth limited, never throws) and
 * [TextDocument.parse] pulls out the verified fields.
 */
object AepTextDoc {

    // ------------------------------------------------------------------ values

    sealed class TextValue {
        data class Dict(val pairs: List<Pair<String, TextValue>>) : TextValue()
        data class Array(val items: List<TextValue>) : TextValue()
        data class Str(val value: String) : TextValue()

        class Bytes(val value: ByteArray) : TextValue() {
            override fun equals(other: Any?): Boolean =
                other is Bytes && value.contentEquals(other.value)

            override fun hashCode(): Int = value.contentHashCode()
        }

        data class Name(val value: String) : TextValue()
        data class Whole(val value: Long) : TextValue()
        data class Real(val value: Double) : TextValue()
        data class Bool(val value: Boolean) : TextValue()
        data class Token(val value: String) : TextValue()

        /** The value of a dictionary key (the last one when a key repeats). */
        fun get(key: String): TextValue? =
            (this as? Dict)?.pairs?.asReversed()?.firstOrNull { it.first == key }?.second

        /** An array element. */
        fun at(index: Int): TextValue? = (this as? Array)?.items?.getOrNull(index)

        fun asStr(): String? = (this as? Str)?.value
        fun asF64(): Double? = when (this) {
            is Whole -> value.toDouble()
            is Real -> value
            else -> null
        }

        fun asI64(): Long? = (this as? Whole)?.value

        fun keyText(): String = when (this) {
            is Name -> value
            is Str -> value
            is Token -> value
            is Whole -> value.toString()
            is Real -> rustFloat(value)
            is Bool -> value.toString()
            is Bytes -> String(value, Charsets.UTF_8)
            is Dict, is Array -> ""
        }
    }

    /** Rust `{}` formatting of an f64: 1.0 prints as "1". */
    private fun rustFloat(v: Double): String =
        if (v.isFinite() && v == kotlin.math.floor(v) && kotlin.math.abs(v) < 9.007199254740992E15) {
            v.toLong().toString()
        } else {
            v.toString()
        }

    // ------------------------------------------------------------------ parser

    private const val MAX_DEPTH = 64

    private class Parser(private val b: ByteArray) {
        var i: Int = 0

        private fun peek(): Int? = if (i < b.size) b[i].toInt() and 0xFF else null

        private fun isWs(c: Int): Boolean = c == 0x20 || c == 0x09 || c == 0x0D || c == 0x0A

        private fun isDelimiter(c: Int): Boolean =
            isWs(c) || c == '/'.code || c == '['.code || c == ']'.code ||
                c == '<'.code || c == '>'.code || c == '('.code || c == ')'.code

        private fun skipWs() {
            while (peek()?.let { isWs(it) } == true) i++
        }

        private fun startsWith(s: ByteArray): Boolean {
            if (i + s.size > b.size) return false
            return s.indices.all { b[i + it] == s[it] }
        }

        private fun word(): ByteArray {
            val start = i
            while (peek()?.let { !isDelimiter(it) } == true) i++
            return b.copyOfRange(start, i)
        }

        fun parse(depth: Int): TextValue? {
            skipWs()
            if (depth > MAX_DEPTH) return null
            if (startsWith(byteArrayOf('<'.code.toByte(), '<'.code.toByte()))) {
                i += 2
                val pairs = ArrayList<Pair<String, TextValue>>()
                while (true) {
                    skipWs()
                    if (startsWith(byteArrayOf('>'.code.toByte(), '>'.code.toByte()))) {
                        i += 2
                        return TextValue.Dict(pairs)
                    }
                    val key = parse(depth + 1) ?: return null
                    val value = parse(depth + 1) ?: return null
                    pairs.add(key.keyText() to value)
                }
            }
            return when (peek() ?: return null) {
                '['.code -> {
                    i++
                    val items = ArrayList<TextValue>()
                    var result: TextValue? = null
                    while (result == null) {
                        skipWs()
                        if ((peek() ?: return null) == ']'.code) {
                            i++
                            result = TextValue.Array(items)
                        } else {
                            items.add(parse(depth + 1) ?: return null)
                        }
                    }
                    result
                }
                '('.code -> {
                    i++
                    val out = ArrayList<Byte>()
                    while (true) {
                        val c = peek() ?: return null
                        when {
                            c == '\\'.code -> {
                                val escaped = if (i + 1 < b.size) b[i + 1] else return null
                                out.add(escaped)
                                i += 2
                            }
                            c == ')'.code -> {
                                i++
                                break
                            }
                            else -> {
                                out.add(c.toByte())
                                i++
                            }
                        }
                    }
                    val bytes = ByteArray(out.size) { out[it] }
                    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                        TextValue.Str(decodeUtf16Be(bytes.copyOfRange(2, bytes.size)))
                    } else {
                        TextValue.Bytes(bytes)
                    }
                }
                '/'.code -> {
                    i++
                    TextValue.Name(String(word(), Charsets.UTF_8))
                }
                else -> {
                    val w = word()
                    if (w.isEmpty()) return null
                    val text = String(w, Charsets.UTF_8)
                    when (text) {
                        "true" -> TextValue.Bool(true)
                        "false" -> TextValue.Bool(false)
                        else -> text.toLongOrNull()?.let { TextValue.Whole(it) }
                            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { TextValue.Real(it) }
                            ?: TextValue.Token(text)
                    }
                }
            }
        }
    }

    private fun decodeUtf16Be(bytes: ByteArray): String {
        // Java's UTF-16BE decoder replaces malformed input with U+FFFD, as
        // the Rust decode_utf16 with unwrap_or(REPLACEMENT_CHARACTER) does;
        // a trailing odd byte becomes one more replacement character.
        val s = String(bytes, Charsets.UTF_16BE)
        return if (bytes.size % 2 == 1) "$s\uFFFD" else s
    }

    /** Parses the body of a `btdk` list into its top-level `key value` pairs. */
    fun parseTextDict(body: ByteArray): TextValue {
        val parser = Parser(body)
        val pairs = ArrayList<Pair<String, TextValue>>()
        while (true) {
            if (parser.i >= body.size) break
            val key = parser.parse(0) ?: break
            val value = parser.parse(0) ?: break
            pairs.add(key.keyText() to value)
        }
        return TextValue.Dict(pairs)
    }

    // ------------------------------------------------------------------ runs

    /** The paragraph justification (`RUN/5/0[n]/0/0/5/0`). */
    sealed class Justification {
        object Left : Justification()
        object Right : Justification()
        object Center : Justification()
        object FullLeft : Justification()
        object FullRight : Justification()
        object FullCenter : Justification()
        object FullAll : Justification()
        data class Other(val code: Long) : Justification()
    }

    data class ParagraphRun(
        val length: Int,
        val justification: Justification,
        val firstLineIndent: Double,
        val startIndent: Double,
        val endIndent: Double,
        val spaceBefore: Double,
        val spaceAfter: Double,
        val leadingType: Long,
        val autoHyphenate: Boolean,
        val hangingRoman: Boolean,
        val everyLineComposer: Boolean,
        val direction: Long
    )

    enum class Caps { Normal, Small, All, Other }
    enum class FontBaseline { Normal, Superscript, Subscript, Other }
    enum class AutoKern { None, Metrics, Optical, Other }

    data class StyleRun(
        val length: Int,
        val fontSize: Double,
        val fillArgb: DoubleArray,
        val strokeArgb: DoubleArray?,
        val tracking: Double,
        val fauxBold: Boolean,
        val fauxItalic: Boolean,
        val caps: Caps,
        val capsCode: Long,
        val autoLeading: Boolean,
        val leading: Double?,
        val fontIndex: Int,
        val baseline: FontBaseline,
        val baselineCode: Long,
        val horizontalScale: Double,
        val verticalScale: Double,
        val baselineShift: Double,
        val autoKern: AutoKern,
        val autoKernCode: Long,
        val ligatures: Boolean,
        val baselineDirection: Long,
        val tsume: Double,
        val noBreak: Boolean,
        val applyFill: Boolean,
        val applyStroke: Boolean,
        val strokeOverFill: Boolean,
        val lineJoin: Long,
        val strokeWidth: Double,
        val digitSet: Long
    ) {
        companion object {
            /** The style of the sampled untouched document (244). */
            val SAMPLED = StyleRun(
                length = 0, fontSize = 100.0, fillArgb = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
                strokeArgb = null, tracking = 0.0, fauxBold = false, fauxItalic = false,
                caps = Caps.Normal, capsCode = 0, autoLeading = false, leading = 98.0,
                fontIndex = 0, baseline = FontBaseline.Normal, baselineCode = 0,
                horizontalScale = 1.0, verticalScale = 1.0, baselineShift = 0.0,
                autoKern = AutoKern.Metrics, autoKernCode = 1, ligatures = false,
                baselineDirection = 1, tsume = 0.0, noBreak = false, applyFill = true,
                applyStroke = false, strokeOverFill = true, lineJoin = 0, strokeWidth = 1.0,
                digitSet = 0
            )
        }
    }

    data class KernRun(val length: Int, val amount: Double?)
    data class TextBox(val x: Double, val y: Double, val width: Double, val height: Double)

    // ------------------------------------------------------------------ document

    /**
     * The verified fields of a layer's text document.
     * [text] is without the trailing paragraph terminators (`\r`).
     */
    data class TextDocument(
        val text: String,
        val rawText: String,
        val fontSize: Double,
        val fillArgb: DoubleArray,
        val fontIndex: Int,
        val font: String?,
        val fonts: List<String>,
        val paragraphs: List<ParagraphRun>,
        val styleRuns: List<StyleRun>,
        val textBox: TextBox?,
        val incompleteRuns: Int,
        val undecodedStyleFields: List<String>,
        val undecodedParagraphFields: List<String>,
        val kerning: List<KernRun>,
        val lineOrientation: Long?
    ) {
        /** The paragraphs: the stored text split at `\r`, each without its terminator. */
        fun paragraphTexts(): List<String> =
            rawText.removeSuffix("\r").split("\r")

        /** The fill colour as [R, G, B]. */
        fun fillRgb(): DoubleArray = doubleArrayOf(fillArgb[1], fillArgb[2], fillArgb[3])

        /** The font (PostScript name) of a style run, or null when unreadable. */
        fun fontOf(run: StyleRun): String? =
            fonts.getOrNull(run.fontIndex)?.takeIf { it.isNotEmpty() }

        companion object {
            /** Decodes the body of a `btdk` list; null when the required fields are missing. */
            fun parse(body: ByteArray): TextDocument? {
                val top = parseTextDict(body)
                val run = top.get("1")?.get("1")?.let { (it as? TextValue.Array)?.at(0) }?.get("0")
                    ?: return null
                val rawText = run.get("0")?.asStr() ?: return null
                val style = run.get("6")?.get("0")?.let { (it as? TextValue.Array)?.at(0) }
                    ?.get("0")?.get("0")?.get("6") ?: return null
                val fillArgb = colourOf(style, "53") ?: return null
                val fontIndex = style.get("0")?.asI64()?.toInt() ?: return null
                val fontSize = style.get("1")?.asF64() ?: return null
                val fonts = ArrayList<String>()
                val fontList = top.get("0")?.get("1")?.get("0")
                if (fontList is TextValue.Array) {
                    for (f in fontList.items) {
                        fonts.add(
                            f.get("0")?.get("0")?.get("0")?.asStr() ?: ""
                        )
                    }
                }
                val notes = RunNotes()
                val paragraphs = paragraphRuns(run, notes)
                val styleRuns = styleRuns(run, notes)
                return TextDocument(
                    text = rawText.trimEnd('\r'),
                    rawText = rawText,
                    fontSize = fontSize,
                    fillArgb = fillArgb,
                    fontIndex = fontIndex,
                    font = fonts.getOrNull(fontIndex)?.takeIf { it.isNotEmpty() },
                    fonts = fonts,
                    paragraphs = paragraphs,
                    styleRuns = styleRuns,
                    textBox = textBox(top),
                    incompleteRuns = notes.incomplete,
                    undecodedStyleFields = notes.styleFields,
                    undecodedParagraphFields = notes.paragraphFields,
                    kerning = kernRuns(run),
                    lineOrientation = top.get("0")?.get("8")?.get("0")
                        ?.let { (it as? TextValue.Array)?.at(0) }
                        ?.get("0")?.get("2")?.get("1")?.asI64()
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private class RunNotes {
        var incomplete: Int = 0
        val styleFields: MutableList<String> = ArrayList()
        val paragraphFields: MutableList<String> = ArrayList()
    }

    private fun colourOf(style: TextValue, slot: String): DoubleArray? {
        val c = style.get(slot)?.get("0")?.get("1") ?: return null
        val arr = c as? TextValue.Array ?: return null
        if (arr.items.size < 4) return null
        val values = DoubleArray(4) { arr.items[it].asF64() ?: return null }
        return values
    }

    private fun usizeOf(v: TextValue?): Int? {
        val n = v?.asI64() ?: return null
        return if (n in 0..Int.MAX_VALUE) n.toInt() else null
    }

    private fun num(style: TextValue, slot: String, default: Double): Double =
        style.get(slot)?.asF64()?.takeIf { it.isFinite() } ?: default

    private fun flag(style: TextValue, slot: String, default: Boolean): Boolean =
        (style.get(slot) as? TextValue.Bool)?.value ?: default

    /** The run style slots (`RUN/6/0[n]/0/0/6/<slot>`) that are decoded. */
    private val DECODED_STYLE_SLOTS = setOf(
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "11", "12", "13",
        "18", "35", "36", "52", "53", "54", "56", "57", "58", "62", "63", "70"
    )

    /** The paragraph slots (`RUN/5/0[n]/0/0/5/<slot>`) that are decoded. */
    private val DECODED_PARAGRAPH_SLOTS = setOf(
        "0", "1", "2", "3", "4", "5", "8", "9", "21", "29", "33"
    )

    private fun floatVal(v: Double): TextValue = TextValue.Real(v)
    private fun numArray(vararg v: Double): TextValue =
        TextValue.Array(v.map { TextValue.Real(it) })

    private fun dictOf(vararg pairs: Pair<String, TextValue>): TextValue =
        TextValue.Dict(pairs.toList())

    /** The default of every run style slot that is not decoded and not neutral. */
    private fun styleDefaults(): TextValue {
        val paint = { a: Double ->
            dictOf(
                "99" to TextValue.Name("SimplePaint"),
                "0" to dictOf(
                    "0" to floatVal(1.0),
                    "1" to numArray(1.0, 1.0, 1.0, a)
                )
            )
        }
        val clazz = { name: String -> dictOf("99" to TextValue.Name(name)) }
        return dictOf(
            "37" to floatVal(2.0),
            "41" to floatVal(2.0),
            "43" to dictOf("0" to floatVal(0.5)),
            "44" to floatVal(2.0),
            "45" to floatVal(2.0),
            "46" to floatVal(7.0),
            "49" to floatVal(-1.0),
            "50" to floatVal(-1.0),
            "55" to clazz("SimpleBlender"),
            "64" to floatVal(4.0),
            "71" to floatVal(4.0),
            "77" to TextValue.Bool(true),
            "78" to TextValue.Bool(true),
            "79" to paint(0.0),
            "82" to floatVal(3.0),
            "83" to floatVal(3.0),
            "86" to clazz("SimpleCustomFeature"),
            "87" to floatVal(100.0),
            "88" to TextValue.Bool(true),
            "95" to dictOf("0" to floatVal(0.0), "1" to floatVal(0.0)),
            "96" to TextValue.Bool(true)
        )
    }

    /** The same for the paragraph slots. */
    private fun paragraphDefaults(): TextValue = dictOf(
        "6" to floatVal(1.0),
        "7" to floatVal(1.2),
        "10" to floatVal(6.0),
        "11" to floatVal(2.0),
        "12" to floatVal(2.0),
        "14" to floatVal(36.0),
        "15" to TextValue.Bool(true),
        "16" to floatVal(0.5),
        "17" to numArray(0.8, 1.0, 1.33),
        "18" to numArray(0.0, 0.0, 0.0),
        "19" to numArray(1.0, 1.0, 1.0),
        "20" to floatVal(6.0),
        "22" to floatVal(1.0),
        "23" to TextValue.Bool(true),
        "24" to floatVal(1.0),
        "26" to TextValue.Bool(true),
        "31" to floatVal(36.0),
        "35" to floatVal(1.0),
        "40" to floatVal(2.0)
    )

    private fun same(a: TextValue, b: TextValue): Boolean {
        if (a is TextValue.Dict && b is TextValue.Dict) {
            if (a.pairs.size != b.pairs.size) return false
            return a.pairs.all { (k, v) ->
                b.pairs.any { (k2, v2) -> k == k2 && same(v, v2) }
            }
        }
        if (a is TextValue.Array && b is TextValue.Array) {
            if (a.items.size != b.items.size) return false
            return a.items.zip(b.items).all { (p, q) -> same(p, q) }
        }
        val (p, q) = a.asF64() to b.asF64()
        if (p != null && q != null) return p == q
        return a == b
    }

    private fun isNeutral(v: TextValue): Boolean = when (v) {
        is TextValue.Name -> v.value == "nil"
        is TextValue.Bool -> !v.value
        is TextValue.Array -> v.items.isEmpty()
        is TextValue.Dict -> v.pairs.isEmpty()
        else -> v.asF64() == 0.0
    }

    private val NEUTRAL_PARAGRAPH_VALUES = mapOf(
        "24" to setOf(0L, 1L), "34" to setOf(0L, 7L), "35" to setOf(0L, 1L)
    )
    private val NEUTRAL_STYLE_VALUES = mapOf("68" to setOf(0L, 1L))

    private const val MAX_UNDECODED_FIELDS = 64

    private fun undecodedFields(
        actual: TextValue,
        sampled: TextValue?,
        decoded: Set<String>,
        neutral: Map<String, Set<Long>>,
        out: MutableList<String>
    ) {
        val dict = actual as? TextValue.Dict ?: return
        for ((key, value) in dict.pairs) {
            if (out.size >= MAX_UNDECODED_FIELDS) return
            if (decoded.contains(key) || out.contains(key)) continue
            if (neutral[key]?.contains(value.asI64()) == true) continue
            val differs = when (val default = (sampled as? TextValue.Dict)?.get(key)) {
                null -> !isNeutral(value)
                else -> !same(value, default)
            }
            if (differs) out.add(key)
        }
    }

    /** The paragraph runs of a text run dictionary (`RUN/5/0`). */
    private fun paragraphRuns(run: TextValue, notes: RunNotes): List<ParagraphRun> {
        val items = (run.get("5")?.get("0") as? TextValue.Array)?.items ?: return emptyList()
        val sampled = paragraphDefaults()
        val out = ArrayList<ParagraphRun>()
        for (e in items) {
            val length = usizeOf(e.get("1"))
            if (length == null) {
                notes.incomplete++
                continue
            }
            val para = e.get("0")?.get("0")?.get("5")
            val justification = when (val code = para?.get("0")?.asI64()) {
                0L -> Justification.Left
                1L -> Justification.Right
                2L -> Justification.Center
                3L -> Justification.FullLeft
                4L -> Justification.FullRight
                5L -> Justification.FullCenter
                6L -> Justification.FullAll
                null -> {
                    notes.incomplete++
                    out.lastOrNull()?.justification ?: Justification.Left
                }
                else -> Justification.Other(code)
            }
            if (para != null) {
                undecodedFields(para, sampled, DECODED_PARAGRAPH_SLOTS, NEUTRAL_PARAGRAPH_VALUES, notes.paragraphFields)
            }
            val numSlot = { slot: String ->
                para?.get(slot)?.asF64()?.takeIf { it.isFinite() } ?: 0.0
            }
            val intSlot = { slot: String, default: Long ->
                para?.get(slot)?.asI64() ?: default
            }
            val flagSlot = { slot: String, default: Boolean ->
                (para?.get(slot) as? TextValue.Bool)?.value ?: default
            }
            out.add(
                ParagraphRun(
                    length = length,
                    justification = justification,
                    firstLineIndent = numSlot("1"),
                    startIndent = numSlot("2"),
                    endIndent = numSlot("3"),
                    spaceBefore = numSlot("4"),
                    spaceAfter = numSlot("5"),
                    leadingType = intSlot("8", 0),
                    autoHyphenate = flagSlot("9", true),
                    hangingRoman = flagSlot("21", false),
                    everyLineComposer = flagSlot("29", false),
                    direction = intSlot("33", 0)
                )
            )
        }
        return out
    }

    /** The style runs of a text run dictionary (`RUN/6/0`). */
    private fun styleRuns(run: TextValue, notes: RunNotes): List<StyleRun> {
        val items = (run.get("6")?.get("0") as? TextValue.Array)?.items ?: return emptyList()
        val sampled = styleDefaults()
        val out = ArrayList<StyleRun>()
        for (e in items) {
            val length = usizeOf(e.get("1"))
            if (length == null) {
                notes.incomplete++
                continue
            }
            val prev = out.lastOrNull() ?: StyleRun.SAMPLED
            val style = e.get("0")?.get("0")?.get("6")
            if (style == null) {
                notes.incomplete++
                out.add(prev.copy(length = length))
                continue
            }
            val fontSize = style.get("1")?.asF64()
            val fillArgb = colourOf(style, "53")
            val fontIndex = usizeOf(style.get("0"))
            if (fontSize == null || fillArgb == null || fontIndex == null) {
                notes.incomplete++
            }
            undecodedFields(style, sampled, DECODED_STYLE_SLOTS, NEUTRAL_STYLE_VALUES, notes.styleFields)
            val capsCode = style.get("12")?.asI64() ?: 0
            val baselineCode = style.get("13")?.asI64() ?: 0
            val autoKernCode = style.get("11")?.asI64() ?: 1
            out.add(
                StyleRun(
                    length = length,
                    fontSize = fontSize ?: prev.fontSize,
                    fillArgb = fillArgb ?: prev.fillArgb,
                    strokeArgb = colourOf(style, "54"),
                    tracking = style.get("8")?.asF64() ?: 0.0,
                    fauxBold = (style.get("2") as? TextValue.Bool)?.value == true,
                    fauxItalic = (style.get("3") as? TextValue.Bool)?.value == true,
                    caps = when (capsCode) {
                        0L -> Caps.Normal
                        1L -> Caps.Small
                        2L -> Caps.All
                        else -> Caps.Other
                    },
                    capsCode = capsCode,
                    autoLeading = (style.get("4") as? TextValue.Bool)?.value == true,
                    leading = style.get("5")?.asF64(),
                    fontIndex = fontIndex ?: prev.fontIndex,
                    baseline = when (baselineCode) {
                        0L -> FontBaseline.Normal
                        1L -> FontBaseline.Superscript
                        2L -> FontBaseline.Subscript
                        else -> FontBaseline.Other
                    },
                    baselineCode = baselineCode,
                    horizontalScale = num(style, "6", 1.0),
                    verticalScale = num(style, "7", 1.0),
                    baselineShift = num(style, "9", 0.0),
                    autoKern = when (autoKernCode) {
                        0L -> AutoKern.None
                        1L -> AutoKern.Metrics
                        2L -> AutoKern.Optical
                        else -> AutoKern.Other
                    },
                    autoKernCode = autoKernCode,
                    ligatures = flag(style, "18", false),
                    baselineDirection = style.get("35")?.asI64() ?: 1,
                    tsume = num(style, "36", 0.0),
                    noBreak = flag(style, "52", false),
                    applyFill = flag(style, "56", true),
                    applyStroke = flag(style, "57", false),
                    strokeOverFill = flag(style, "58", true),
                    lineJoin = style.get("62")?.asI64() ?: 0,
                    strokeWidth = num(style, "63", 1.0),
                    digitSet = style.get("70")?.asI64() ?: 0
                )
            )
        }
        return out
    }

    /** The box of paragraph text from the frame dictionary `/0/8/0[0]/0`. */
    private fun textBox(top: TextValue): TextBox? {
        val frame = top.get("0")?.get("8")?.get("0")
            ?.let { (it as? TextValue.Array)?.at(0) }?.get("0") ?: return null
        if (frame.get("2")?.get("0")?.asI64() != 1L) return null
        val coords = frame.get("1")?.get("0") as? TextValue.Array ?: return null
        val numbers = coords.items.map { it.asF64() ?: return null }
        if (numbers.isEmpty()) return null
        val pairs = numbers.chunked(2).filter { it.size == 2 }
        if (pairs.isEmpty()) return null
        val x = pairs.minOf { it[0] }
        val y = pairs.minOf { it[1] }
        return TextBox(
            x = x, y = y,
            width = pairs.maxOf { it[0] } - x,
            height = pairs.maxOf { it[1] } - y
        )
    }

    /** The manual kerning list of a text run dictionary (`RUN/8/0`). */
    private fun kernRuns(run: TextValue): List<KernRun> {
        val items = (run.get("8")?.get("0") as? TextValue.Array)?.items ?: return emptyList()
        val runs = items.mapNotNull { e ->
            val length = usizeOf(e.get("1")) ?: return@mapNotNull null
            val amount = e.get("0")?.get("0")?.asF64()?.takeIf { it.isFinite() }
            KernRun(length, amount)
        }
        return if (runs.any { it.amount != null && it.amount != 0.0 }) runs else emptyList()
    }
}
