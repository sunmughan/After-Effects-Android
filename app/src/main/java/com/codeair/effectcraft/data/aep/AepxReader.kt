package com.codeair.effectcraft.data.aep

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reading the XML form of a project (`.aepx`) into the same chunk tree as an `.aep`.
 *
 * Port of `container/aepx.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). An `.aepx` is the `RIFX`
 * chunk tree written as XML: one element per chunk, named by the chunk id
 * (or the list type of a `LIST`), with the body as hex in a `bdata`
 * attribute. A few chunks are written as text instead (`Utf8` as
 * `<string>`, the numbers `numS` and `ppSn`, the footage alias `alas` as
 * `<fileReference>`), and the XMP packet is `<ProjectXMPMetadata>`.
 *
 * The reader is a small XML tokenizer for exactly that subset. A document
 * type declaration is refused (no entity expansion). It runs without
 * recursion; nesting depth and element count are bounded by [Limits].
 */
object AepxReader {

    private val ROOT = "AfterEffectsProject".toByteArray(Charsets.US_ASCII)
    private val XMP = "ProjectXMPMetadata".toByteArray(Charsets.US_ASCII)
    private val STRING = "string".toByteArray(Charsets.US_ASCII)
    private val FILE_REFERENCE = "fileReference".toByteArray(Charsets.US_ASCII)

    /** List types whose body is not a chunk sequence: written like data chunks in `.aepx`. */
    private val OPAQUE_LIST_TYPES: Set<String> = setOf("btdk")

    /** True when [data] looks like an `.aepx`: optional BOM and white space, then XML. */
    fun isAepx(data: ByteArray): Boolean {
        var rest = data
        if (rest.size >= 3 && rest[0] == 0xEF.toByte() && rest[1] == 0xBB.toByte() && rest[2] == 0xBF.toByte()) {
            rest = rest.copyOfRange(3, rest.size)
        }
        val start = rest.indexOfFirst { !it.isAsciiWhitespace() }.takeIf { it >= 0 } ?: rest.size
        val tail = rest.copyOfRange(start, rest.size)
        return tail.startsWith("<?xml".toByteArray(Charsets.US_ASCII)) ||
            tail.startsWith("<AfterEffectsProject".toByteArray(Charsets.US_ASCII))
    }

    /** Reads an `.aep` or an `.aepx`, chosen by content. */
    fun parseAny(data: ByteArray): AepContainer.AepFile =
        if (isAepx(data)) readAepx(data) else AepContainer.parse(data)

    /** Reads an `.aepx` with the default [AepContainer.Limits]. */
    fun readAepx(data: ByteArray): AepContainer.AepFile =
        readAepxWithLimits(data, AepContainer.Limits.DEFAULT)

    fun readAepxWithLimits(data: ByteArray, limits: AepContainer.Limits): AepContainer.AepFile {
        // Strict UTF-8: malformed input is an Xml error, never silent U+FFFD.
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
        } catch (failure: CharacterCodingException) {
            throw AepError.Xml(0, "the file is not valid UTF-8")
        }
        return Reader(data, limits).run()
    }

    private fun Byte.isAsciiWhitespace(): Boolean =
        this == ' '.code.toByte() || this == '\t'.code.toByte() ||
            this == '\n'.code.toByte() || this == '\r'.code.toByte()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun xml(offset: Int, what: String): AepError = AepError.Xml(offset, what)

    /** What an open element becomes when it closes. */
    private enum class Kind {
        Root,
        Chunk,
        Utf8,
        Alias,
        Xmp,
        Skip
    }

    private class Frame(
        val kind: Kind,
        val chunkId: String?,
        val at: Int,
        val name: Pair<Int, Int>,
        var bdata: ByteArray? = null,
        val attrs: MutableList<Pair<String, String>> = mutableListOf(),
        val text: ByteArrayOutputStream = ByteArrayOutputStream(),
        val children: MutableList<AepContainer.Chunk> = mutableListOf()
    )

    private class Reader(val data: ByteArray, val limits: AepContainer.Limits) {
        var pos: Int = 0
        var count: Int = 0
        val stack = ArrayList<Frame>()
        var top: List<AepContainer.Chunk>? = null
        var trailer: ByteArray? = null

        fun run(): AepContainer.AepFile {
            if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
                pos = 3
            }
            while (pos < data.size) {
                if (rest().startsWith("<".toByteArray(Charsets.US_ASCII))) {
                    markup()
                } else {
                    text()
                }
            }
            val open = stack.lastOrNull()
            if (open != null) {
                throw xml(open.at, "an element is not closed before the end of the file")
            }
            val chunks = top ?: throw xml(data.size, "no AfterEffectsProject root element")
            return AepContainer.AepFile(chunks, trailer ?: byteArrayOf())
        }

        private fun rest(): ByteArray = data.copyOfRange(pos, data.size)

        private fun find(from: Int, needle: ByteArray): Int? {
            if (needle.isEmpty() || from > data.size - needle.size) return null
            outer@ for (i in from..data.size - needle.size) {
                for (j in needle.indices) {
                    if (data[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return null
        }

        private fun skipPast(close: ByteArray, what: String): Int {
            val at = find(pos, close) ?: throw xml(pos, what)
            pos = at + close.size
            return at
        }

        private fun markup() {
            val rest = rest()
            when {
                rest.startsWith("<?".toByteArray(Charsets.US_ASCII)) ->
                    skipPast("?>".toByteArray(Charsets.US_ASCII), "a processing instruction is not closed")
                rest.startsWith("<!--".toByteArray(Charsets.US_ASCII)) ->
                    skipPast("-->".toByteArray(Charsets.US_ASCII), "a comment is not closed")
                rest.startsWith("<![CDATA[".toByteArray(Charsets.US_ASCII)) -> {
                    val start = pos + 9
                    pos = start
                    val end = skipPast("]]>".toByteArray(Charsets.US_ASCII), "a CDATA section is not closed")
                    val content = data.copyOfRange(start, end)
                    val frame = stack.lastOrNull()
                    if (frame == null) throw xml(start, "CDATA outside the root element")
                    if (frame.kind == Kind.Utf8 || frame.kind == Kind.Xmp) {
                        frame.text.write(content)
                    }
                }
                rest.startsWith("<!".toByteArray(Charsets.US_ASCII)) ->
                    throw xml(pos, "document type declarations are not supported")
                rest.startsWith("</".toByteArray(Charsets.US_ASCII)) -> endTag()
                else -> startTag()
            }
        }

        private fun isNameByte(b: Byte): Boolean =
            !b.isAsciiWhitespace() && b != '<'.code.toByte() && b != '>'.code.toByte() &&
                b != '/'.code.toByte() && b != '='.code.toByte() &&
                b != '"'.code.toByte() && b != '\''.code.toByte() && b != '&'.code.toByte()

        private fun name(): Pair<Int, Int> {
            val start = pos
            val len = rest().indexOfFirst { !isNameByte(it) }.takeIf { it >= 0 } ?: rest().size
            if (len == 0) throw xml(start, "a tag or attribute has no name")
            pos = start + len
            return start to pos
        }

        private fun skipWs() {
            val n = rest().indexOfFirst { !it.isAsciiWhitespace() }.takeIf { it >= 0 } ?: rest().size
            pos += n
        }

        private fun slice(range: Pair<Int, Int>): ByteArray =
            data.copyOfRange(range.first, range.second)

        private fun startTag() {
            val at = pos
            pos += 1
            val name = name()
            val attrs = mutableListOf<Pair<Pair<Int, Int>, ByteArray>>()
            var selfClosing = false
            while (true) {
                skipWs()
                val rest = rest()
                when {
                    rest.startsWith("/>".toByteArray(Charsets.US_ASCII)) -> {
                        pos += 2
                        selfClosing = true
                        break
                    }
                    rest.startsWith(">".toByteArray(Charsets.US_ASCII)) -> {
                        pos += 1
                        break
                    }
                    rest.isEmpty() -> throw xml(at, "a start tag is not closed")
                    else -> {
                        val key = name()
                        skipWs()
                        if (!rest().startsWith("=".toByteArray(Charsets.US_ASCII))) {
                            throw xml(pos, "an attribute has no value")
                        }
                        pos += 1
                        skipWs()
                        val quote = rest().firstOrNull() ?: throw xml(pos, "a start tag is not closed")
                        if (quote != '"'.code.toByte() && quote != '\''.code.toByte()) {
                            throw xml(pos, "an attribute value is not quoted")
                        }
                        val vstart = pos + 1
                        val vend = find(vstart, byteArrayOf(quote))
                            ?: throw xml(vstart, "an attribute value is not closed")
                        val raw = data.copyOfRange(vstart, vend)
                        if (raw.contains('<'.code.toByte())) {
                            throw xml(vstart, "`<` inside an attribute value")
                        }
                        val value = ByteArrayOutputStream()
                        decodeEntities(raw, vstart, value)
                        pos = vend + 1
                        attrs.add(key to value.toByteArray())
                    }
                }
            }
            val (kind, chunkId) = kindOf(name, at)
            if (kind != Kind.Root) {
                count = count.saturatingAdd(1)
                if (count > limits.maxChunks) throw AepError.TooManyChunks()
                // The root's children are depth 1, like the top-level chunks of an `.aep`.
                if (stack.size > limits.maxDepth) throw AepError.TooDeep()
            }
            val frame = Frame(kind, chunkId, at, name)
            if (kind == Kind.Chunk) {
                val bdataAttr = attrs.firstOrNull { slice(it.first).contentEquals("bdata".toByteArray(Charsets.US_ASCII)) }
                if (bdataAttr != null) {
                    frame.bdata = decodeHex(bdataAttr.second, bdataAttr.first.first)
                }
            } else if (kind == Kind.Alias) {
                frame.attrs.addAll(attrs.map { (k, v) ->
                    String(slice(k), Charsets.UTF_8) to String(v, Charsets.UTF_8)
                })
            }
            stack.add(frame)
            if (selfClosing) close()
        }

        /** Classifies an element by its name and where it is. */
        private fun kindOf(name: Pair<Int, Int>, at: Int): Pair<Kind, String?> {
            val n = slice(name)
            val parent = stack.lastOrNull() ?: run {
                if (top != null) throw xml(at, "a second root element")
                if (!n.contentEquals(ROOT)) {
                    throw xml(at, "the root element is not AfterEffectsProject")
                }
                return Kind.Root to null
            }
            return when (parent.kind) {
                Kind.Skip, Kind.Utf8, Kind.Xmp, Kind.Alias -> Kind.Skip to null
                Kind.Root, Kind.Chunk -> {
                    when {
                        n.contentEquals(STRING) -> Kind.Utf8 to null
                        n.contentEquals(FILE_REFERENCE) -> Kind.Alias to null
                        n.contentEquals(XMP) && parent.kind == Kind.Root -> Kind.Xmp to null
                        else -> {
                            val id = chunkId(n)
                            if (id != null) Kind.Chunk to id else Kind.Skip to null
                        }
                    }
                }
            }
        }

        private fun endTag() {
            val at = pos
            pos += 2
            val name = name()
            skipWs()
            if (!rest().startsWith(">".toByteArray(Charsets.US_ASCII))) {
                throw xml(at, "an end tag is not closed")
            }
            pos += 1
            val open = stack.lastOrNull() ?: throw xml(at, "an end tag without a start tag")
            if (!slice(open.name).contentEquals(slice(name))) {
                throw xml(at, "an end tag does not match its start tag")
            }
            close()
        }

        /** Closes the innermost element and hands its chunk to the parent. */
        private fun close() {
            val frame = stack.removeLastOrNull() ?: throw xml(pos, "an end tag without a start tag")
            val chunk: AepContainer.Chunk? = when (frame.kind) {
                Kind.Root -> {
                    top = frame.children.toList()
                    return
                }
                Kind.Skip -> return
                Kind.Xmp -> {
                    if (trailer == null) trailer = frame.text.toByteArray()
                    return
                }
                Kind.Utf8 -> AepContainer.Chunk.data("Utf8", frame.text.toByteArray())
                Kind.Alias -> AepContainer.Chunk.data("alas", aliasJson(frame.attrs).toByteArray(Charsets.UTF_8))
                Kind.Chunk -> chunkOf(frame.chunkId!!, frame)
            }
            stack.lastOrNull()?.children?.add(chunk!!)
        }

        private fun text() {
            val start = pos
            val len = rest().indexOfFirst { it == '<'.code.toByte() }.takeIf { it >= 0 } ?: rest().size
            val end = start + len
            pos = end
            val raw = data.copyOfRange(start, end)
            val frame = stack.lastOrNull()
            when {
                frame != null && (frame.kind == Kind.Utf8 || frame.kind == Kind.Xmp) ->
                    decodeEntities(raw, start, frame.text)
                frame != null -> Unit
                raw.all { it.isAsciiWhitespace() } -> Unit
                else -> throw xml(start, "text outside the root element")
            }
        }
    }

    /** The chunk id of an element name: 1 to 4 printable ASCII bytes, padded with spaces. */
    private fun chunkId(name: ByteArray): String? {
        if (name.isEmpty() || name.size > 4) return null
        if (!name.all { it.toInt() in 0x21..0x7E }) return null
        val id = CharArray(4) { ' ' }
        for (i in name.indices) id[i] = name[i].toInt().toChar()
        return String(id)
    }

    /** The chunk of a closed element named by a chunk id. */
    private fun chunkOf(id: String, frame: Frame): AepContainer.Chunk {
        val bytes = frame.bdata
        if (bytes != null) {
            if (id in OPAQUE_LIST_TYPES) {
                val pad: Byte? = if (bytes.size % 2 == 1) 0 else null
                return AepContainer.Chunk(
                    AepContainer.LIST,
                    AepContainer.ChunkBody.OpaqueList(id, bytes),
                    pad
                )
            }
            return AepContainer.Chunk.data(id, bytes)
        }
        // Numbers written as text: `<numS><string>0</string></numS>` and `<ppSn><string>150.000000000</string></ppSn>`.
        if (id == "numS" || id == "ppSn") {
            val text = if (frame.children.size == 1 && frame.children[0].hasId("Utf8")) {
                frame.children[0].bytes() ?: byteArrayOf()
            } else {
                throw xml(frame.at, "a numS or ppSn element without its number")
            }
            val trimmed = String(text, Charsets.UTF_8).trim()
            val body: ByteArray = if (id == "numS") {
                val n = trimmed.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL }
                    ?: throw xml(frame.at, "numS is not a 32-bit integer")
                byteArrayOf(
                    (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
                )
            } else {
                val d = trimmed.toDoubleOrNull() ?: throw xml(frame.at, "ppSn is not a number")
                val bits = d.toBits()
                byteArrayOf(
                    (bits ushr 56).toByte(), (bits ushr 48).toByte(), (bits ushr 40).toByte(),
                    (bits ushr 32).toByte(), (bits ushr 24).toByte(), (bits ushr 16).toByte(),
                    (bits ushr 8).toByte(), bits.toByte()
                )
            }
            return AepContainer.Chunk.data(id, body)
        }
        if (id in AepContainer.NESTED_IDS) {
            if (frame.children.isEmpty()) {
                return AepContainer.Chunk.data(id, byteArrayOf())
            }
            return AepContainer.Chunk(id, AepContainer.ChunkBody.Chunks(frame.children.toList()), null)
        }
        return AepContainer.Chunk.list(id, frame.children.toList())
    }

    /** Appends [raw] with the predefined entities and character references decoded. */
    private fun decodeEntities(raw: ByteArray, offset: Int, out: ByteArrayOutputStream) {
        var i = 0
        while (i < raw.size) {
            val amp = (i until raw.size).firstOrNull { raw[it] == '&'.code.toByte() }
            if (amp == null) {
                out.write(raw, i, raw.size - i)
                break
            }
            out.write(raw, i, amp - i)
            val at = offset + amp
            val after = raw.copyOfRange(amp + 1, raw.size)
            val semi = after.indexOf(';'.code.toByte()).takeIf { it in 0..11 }
                ?: throw xml(at, "an entity is not terminated")
            val name = after.copyOfRange(0, semi)
            val decoded: String? = when {
                name.contentEquals("amp".toByteArray(Charsets.US_ASCII)) -> "&"
                name.contentEquals("lt".toByteArray(Charsets.US_ASCII)) -> "<"
                name.contentEquals("gt".toByteArray(Charsets.US_ASCII)) -> ">"
                name.contentEquals("quot".toByteArray(Charsets.US_ASCII)) -> "\""
                name.contentEquals("apos".toByteArray(Charsets.US_ASCII)) -> "'"
                name.startsWith("#x".toByteArray(Charsets.US_ASCII)) ||
                    name.startsWith("#X".toByteArray(Charsets.US_ASCII)) -> {
                    val code = String(name.copyOfRange(2, name.size), Charsets.UTF_8).toIntOrNull(16)
                    charRef(code, at)
                }
                name.startsWith("#".toByteArray(Charsets.US_ASCII)) -> {
                    val code = String(name.copyOfRange(1, name.size), Charsets.UTF_8).toIntOrNull()
                    charRef(code, at)
                }
                else -> throw xml(at, "an unknown entity")
            }
            out.write(decoded!!.toByteArray(Charsets.UTF_8))
            i = amp + semi + 2
        }
    }

    /** The string of a numeric character reference, or an error for an invalid one. */
    private fun charRef(code: Int?, at: Int): String {
        val valid = code?.takeIf { it != 0 && it <= 0x10FFFF }
            ?.let { runCatching { Character.toChars(it) }.getOrNull() }
            ?: throw xml(at, "an invalid character reference")
        return String(valid)
    }

    /** Decodes a `bdata` value (hex digits, two per byte). */
    private fun decodeHex(text: ByteArray, offset: Int): ByteArray {
        if (text.size % 2 == 1) throw xml(offset, "bdata has an odd number of hex digits")
        val digit = { b: Byte ->
            when (b) {
                in '0'.code.toByte()..'9'.code.toByte() -> b - '0'.code.toByte()
                in 'a'.code.toByte()..'f'.code.toByte() -> b - 'a'.code.toByte() + 10
                in 'A'.code.toByte()..'F'.code.toByte() -> b - 'A'.code.toByte() + 10
                else -> null
            }
        }
        val out = ByteArray(text.size / 2)
        for (i in out.indices) {
            val hi = digit(text[i * 2]) ?: throw xml(offset, "bdata holds a character that is not a hex digit")
            val lo = digit(text[i * 2 + 1]) ?: throw xml(offset, "bdata holds a character that is not a hex digit")
            out[i] = ((hi.toInt() shl 4) or lo.toInt()).toByte()
        }
        return out
    }

    /** The JSON body of an `alas` chunk from the attributes of a `fileReference`. */
    private fun aliasJson(attrs: List<Pair<String, String>>): String {
        val out = StringBuilder("{")
        for ((index, pair) in attrs.withIndex()) {
            val (key, value) = pair
            if (index > 0) out.append(',')
            jsonStr(out, key)
            out.append(':')
            val number = value.toLongOrNull()
            when {
                (key == "ascendcount_base" || key == "ascendcount_target") && number != null ->
                    out.append(value)
                key == "platform" && value == "Win" -> out.append('1')
                key == "platform" && number != null -> out.append(value)
                key == "target_is_folder" && (value == "0" || value == "false") -> out.append("false")
                key == "target_is_folder" && (value == "1" || value == "true") -> out.append("true")
                else -> jsonStr(out, value)
            }
        }
        out.append('}')
        return out.toString()
    }

    private fun jsonStr(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
        }
        out.append('"')
    }

    private fun Int.saturatingAdd(other: Int): Int {
        val sum = this.toLong() + other.toLong()
        return sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
