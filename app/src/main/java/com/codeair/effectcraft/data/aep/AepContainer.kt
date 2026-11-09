package com.codeair.effectcraft.data.aep

/**
 * The lossless .aep chunk tree, its RIFX reader and its byte-identical writer.
 *
 * Port of `container/chunk.rs`, `container/read.rs` and `container/write.rs`
 * from the official EffectCraft project (storytold/effectcraft, MIT OR
 * Apache-2.0). The format: the file starts with `RIFX`, a big-endian u32
 * size and the form type `Egg!`; every chunk is a 4-byte id, a big-endian
 * u32 body size and the body, padded to even length. `LIST` chunks hold a
 * 4-byte list type followed by chunks. All integers are big-endian (the one
 * exception, `iide`/`idpi`, is little-endian and is read where it is used).
 *
 * Hostile-input rules (mirrored from the official reader): sizes are
 * checked against the remaining input, nothing is allocated from a
 * declared size, nesting and chunk count are bounded, and a `LIST` whose
 * content is not a well-formed chunk sequence is kept opaque instead of
 * failing the parse. For every input that parses, `write(parse(x)) == x`
 * byte for byte.
 */
object AepContainer {

    const val LIST = "LIST"

    /** Non-`LIST` chunk ids whose body is itself a sequence of chunks. */
    val NESTED_IDS: Set<String> = setOf("tdsn", "pdnm", "fnam")

    /** Bounds for hostile input (official defaults). */
    data class Limits(val maxDepth: Int = 64, val maxChunks: Int = 10_000_000) {
        companion object {
            val DEFAULT = Limits()
        }
    }

    /** One chunk: a 4-byte id, a body and an optional pad byte. */
    class Chunk(val id: String, val body: ChunkBody, val pad: Byte?) {
        /** The raw bytes of a [ChunkBody.Data] chunk, or after the list type of an opaque list. */
        fun bytes(): ByteArray? = when (val b = body) {
            is ChunkBody.Data -> b.bytes
            is ChunkBody.OpaqueList -> b.data
            else -> null
        }

        /** The child chunks of a list or nested chunk; empty for data. */
        fun children(): List<Chunk> = when (val b = body) {
            is ChunkBody.Chunks -> b.children
            is ChunkBody.List -> b.children
            else -> emptyList()
        }

        /** The list type when this is a `LIST` (parsed or opaque). */
        fun listType(): String? = when (val b = body) {
            is ChunkBody.List -> b.listType
            is ChunkBody.OpaqueList -> b.listType
            else -> null
        }

        /** True when this is a `LIST` of the given type. */
        fun isList(listType: String): Boolean = id == LIST && listType() == listType

        /** True when this is a `LIST` whose content could not be read as chunks. */
        fun isOpaqueList(): Boolean = body is ChunkBody.OpaqueList

        /** True when this is a chunk (not a `LIST`) with the given id. */
        fun `is`(chunkId: String): Boolean = id == chunkId

        /** Plain-named alias of [`is`], for call sites that dislike backticks. */
        fun hasId(chunkId: String): Boolean = id == chunkId

        /** The first child (non-list) chunk with this id. */
        fun child(chunkId: String): Chunk? = children().firstOrNull { it.`is`(chunkId) }

        /** The first child `LIST` of this type. */
        fun childList(listType: String): Chunk? = children().firstOrNull { it.isList(listType) }

        /** The first descendant `LIST` of this type, searching depth-first. */
        fun findList(listType: String): Chunk? =
            walk().firstOrNull { (_, chunk) -> chunk.isList(listType) }?.second

        /** Visits every chunk below this one depth-first (children before later siblings). */
        fun walk(): Sequence<Pair<Int, Chunk>> = sequence {
            val stack = ArrayDeque<Pair<Iterator<Chunk>, Int>>()
            stack.addLast(children().iterator() to 1)
            while (stack.isNotEmpty()) {
                val (iterator, depth) = stack.last()
                val next = if (iterator.hasNext()) iterator.next() else null
                if (next == null) {
                    stack.removeLast()
                    continue
                }
                val kids = next.children()
                if (kids.isNotEmpty()) stack.addLast(kids.iterator() to depth + 1)
                yield(depth to next)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return id == other.id && body == other.body && pad == other.pad
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + body.hashCode()
            result = 31 * result + (pad?.toInt() ?: 0)
            return result
        }

        override fun toString(): String = "Chunk($id, $body, pad=$pad)"

        companion object {
            /** A data chunk with the pad byte its length needs. */
            fun data(id: String, data: ByteArray): Chunk {
                val pad: Byte? = if (data.size % 2 == 1) 0 else null
                return Chunk(id, ChunkBody.Data(data), pad)
            }

            /** A `LIST` chunk. */
            fun list(listType: String, children: List<Chunk>): Chunk =
                Chunk(LIST, ChunkBody.List(listType, children), null)
        }
    }

    /** The body of a [Chunk]. */
    sealed interface ChunkBody {
        /** Opaque bytes (the content of nearly all chunks). */
        class Data(val bytes: ByteArray) : ChunkBody {
            override fun equals(other: Any?): Boolean =
                other is Data && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
            override fun toString(): String = "Data(${bytes.size} bytes)"
        }

        /** A body that is a sequence of chunks although the id is not `LIST`. */
        class Chunks(val children: kotlin.collections.List<Chunk>) : ChunkBody {
            override fun equals(other: Any?): Boolean =
                other is Chunks && children == other.children

            override fun hashCode(): Int = children.hashCode()
            override fun toString(): String = "Chunks(${children.size})"
        }

        /** A `LIST`: a 4-byte list type followed by chunks. */
        class List(val listType: String, val children: kotlin.collections.List<Chunk>) : ChunkBody {
            override fun equals(other: Any?): Boolean =
                other is List && listType == other.listType && children == other.children

            override fun hashCode(): Int = 31 * listType.hashCode() + children.hashCode()
            override fun toString(): String = "List($listType, ${children.size})"
        }

        /** A `LIST` whose content is not a well-formed chunk sequence, kept as bytes. */
        class OpaqueList(val listType: String, val data: ByteArray) : ChunkBody {
            override fun equals(other: Any?): Boolean =
                other is OpaqueList && listType == other.listType && data.contentEquals(other.data)

            override fun hashCode(): Int = 31 * listType.hashCode() + data.contentHashCode()
            override fun toString(): String = "OpaqueList($listType, ${data.size} bytes)"
        }
    }

    /** A parsed `.aep`: the `RIFX` container's chunks and the bytes after its declared end. */
    class AepFile(val chunks: List<Chunk>, val trailer: ByteArray) {
        /** The first top-level chunk with this id. */
        fun chunk(id: String): Chunk? = chunks.firstOrNull { it.`is`(id) }

        /** The first top-level `LIST` of this type. */
        fun list(listType: String): Chunk? = chunks.firstOrNull { it.isList(listType) }

        /** The XMP packet in the trailer, or null. */
        fun xmp(): ByteArray? {
            val start = trailer.indexOf("<?xpacket begin".toByteArray(Charsets.UTF_8)) ?: return null
            val rest = trailer.copyOfRange(start, trailer.size)
            val endMarker = rest.indexOf("<?xpacket end".toByteArray(Charsets.UTF_8)) ?: return null
            val tail = rest.copyOfRange(endMarker, rest.size)
            val close = tail.indexOf("?>".toByteArray(Charsets.UTF_8)) ?: return null
            return rest.copyOfRange(0, endMarker + close + 2)
        }

        /** Visits every chunk depth-first with its nesting depth (the top level is 1). */
        fun walk(): Sequence<Pair<Int, Chunk>> = sequence {
            val stack = ArrayDeque<Pair<Iterator<Chunk>, Int>>()
            stack.addLast(chunks.iterator() to 1)
            while (stack.isNotEmpty()) {
                val (iterator, depth) = stack.last()
                val next = if (iterator.hasNext()) iterator.next() else null
                if (next == null) {
                    stack.removeLast()
                    continue
                }
                val kids = next.children()
                if (kids.isNotEmpty()) stack.addLast(kids.iterator() to depth + 1)
                yield(depth to next)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AepFile) return false
            return chunks == other.chunks && trailer.contentEquals(other.trailer)
        }

        override fun hashCode(): Int = 31 * chunks.hashCode() + trailer.contentHashCode()
    }

    /** Parses an `.aep` with the default [Limits]. */
    fun parse(data: ByteArray): AepFile = parseWithLimits(data, Limits.DEFAULT)

    /** Parses an `.aep` with explicit [Limits]. */
    fun parseWithLimits(data: ByteArray, limits: Limits): AepFile {
        if (id4(data, 0) != "RIFX") throw AepError.NotRifx()
        val declared = beU32(data, 4) ?: throw AepError.NotRifx()
        val form = id4(data, 8) ?: throw AepError.NotRifx()
        if (form != "Egg!") throw AepError.BadFormType(form)
        val available = (data.size.toLong() - 8).coerceAtLeast(0)
        if (declared < 4 || declared > available) {
            throw AepError.Truncated(declared, available)
        }
        // declared <= available < data.size, so the end is inside the input.
        val end = (declared + 8).let {
            if (it > Int.MAX_VALUE) throw AepError.TooLarge()
            it.toInt()
        }
        val parser = Parser(data, limits)
        val chunks = parser.sequence(12, end, 1)
        val trailer = data.copyOfRange(end, data.size)
        return AepFile(chunks, trailer)
    }

    private class Parser(val data: ByteArray, val limits: Limits) {
        var count: Int = 0

        /** Parses the sibling chunks that exactly fill `start..end`. */
        fun sequence(start: Int, end: Int, depth: Int): List<Chunk> {
            val out = mutableListOf<Chunk>()
            var pos = start
            while (pos < end) {
                val short = AepError.Malformed(pos, "bytes left over that are too short for a chunk header")
                val bodyStart = pos + 8
                if (bodyStart > end) throw short
                val id = id4(data, pos) ?: throw AepError.Malformed(pos, "short read")
                val size = (beU32(data, pos + 4) ?: throw AepError.Malformed(pos, "short read")).let {
                    if (it > Int.MAX_VALUE) throw AepError.TooLarge()
                    it.toInt()
                }
                val avail = end - bodyStart
                if (size > avail) {
                    throw AepError.ChunkTooLarge(pos, size.toLong(), avail.toLong())
                }
                count = count.saturatingAdd(1)
                if (count > limits.maxChunks) throw AepError.TooManyChunks()
                val bodyEnd = bodyStart + size
                val body = body(id, bodyStart, bodyEnd, depth)
                var next = bodyEnd
                var pad: Byte? = null
                if (size % 2 == 1 && next < end) {
                    pad = data.getOrNull(next)
                        ?: throw AepError.Malformed(next, "missing pad byte")
                    next += 1
                }
                out.add(Chunk(id, body, pad))
                pos = next
            }
            return out
        }

        /** Like [sequence] but a structural problem yields null (the caller keeps bytes). */
        private fun tentative(start: Int, end: Int, depth: Int): List<Chunk>? {
            return try {
                sequence(start, end, depth)
            } catch (e: AepError) {
                if (e.isStructural()) null else throw e
            }
        }

        private fun slice(start: Int, end: Int): ByteArray =
            data.copyOfRange(start.coerceIn(0, data.size), end.coerceIn(0, data.size))

        private fun body(id: String, start: Int, end: Int, depth: Int): ChunkBody {
            val len = end - start
            if (id == LIST && len >= 4) {
                val listType = id4(data, start)
                    ?: throw AepError.Malformed(start, "short read")
                val opaque = ChunkBody.OpaqueList(listType, slice(start + 4, end))
                if (depth >= limits.maxDepth) return opaque
                return when (val children = tentative(start + 4, end, depth + 1)) {
                    null -> opaque
                    else -> ChunkBody.List(listType, children)
                }
            }
            if (id in NESTED_IDS && len >= 8 && depth < limits.maxDepth) {
                val children = tentative(start, end, depth + 1)
                if (children != null && children.isNotEmpty()) {
                    return ChunkBody.Chunks(children)
                }
            }
            return ChunkBody.Data(slice(start, end))
        }
    }

    private fun Int.saturatingAdd(other: Int): Int {
        val sum = this.toLong() + other.toLong()
        return sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int? {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    /** Deepest tree [write] accepts (a hand-built tree may be deeper than any parsed one). */
    private const val MAX_WRITE_DEPTH = 256

    /**
     * Serialises [file]: `RIFX`, the recomputed container size, `Egg!`, the
     * chunks and the trailer. Sizes are recomputed from the tree; an
     * unmodified parsed file comes back byte for byte.
     */
    fun write(file: AepFile): ByteArray {
        val out = GrowableBuffer()
        out.write("RIFX".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write("Egg!".toByteArray(Charsets.US_ASCII))
        writeSequence(out, file.chunks, 1)
        val size = (out.size - 8).let {
            if (it > 0xFFFFFFFFL) throw AepError.TooLarge()
            it.toInt()
        }
        out.setU32(4, size)
        out.write(file.trailer)
        return out.toByteArray()
    }

    /** A growable byte buffer with random-access patching for size fields. */
    private class GrowableBuffer {
        private var buf = ByteArray(8192)
        var size: Int = 0
            private set

        fun write(bytes: ByteArray) {
            ensure(size + bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun writeByte(value: Int) {
            ensure(size + 1)
            buf[size++] = value.toByte()
        }

        fun setU32(at: Int, value: Int) {
            if (at < 0 || at + 4 > size) throw AepError.TooLarge()
            buf[at] = (value ushr 24).toByte()
            buf[at + 1] = (value ushr 16).toByte()
            buf[at + 2] = (value ushr 8).toByte()
            buf[at + 3] = value.toByte()
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(capacity: Int) {
            if (capacity <= buf.size) return
            var next = buf.size
            while (next < capacity) next = (next * 2).coerceAtLeast(1)
            buf = buf.copyOf(next)
        }
    }

    private fun writeSequence(out: GrowableBuffer, chunks: List<Chunk>, depth: Int) {
        if (depth > MAX_WRITE_DEPTH) throw AepError.TooDeep()
        for ((index, chunk) in chunks.withIndex()) {
            writeChunk(out, chunk, index + 1 == chunks.size, depth)
        }
    }

    private fun writeChunk(out: GrowableBuffer, chunk: Chunk, isLast: Boolean, depth: Int) {
        out.write(chunk.id.toIdBytes())
        val sizeAt = out.size
        out.write(byteArrayOf(0, 0, 0, 0))
        val bodyStart = out.size
        when (val body = chunk.body) {
            is ChunkBody.Data -> out.write(body.bytes)
            is ChunkBody.Chunks -> writeSequence(out, body.children, depth + 1)
            is ChunkBody.List -> {
                out.write(body.listType.toIdBytes())
                writeSequence(out, body.children, depth + 1)
            }
            is ChunkBody.OpaqueList -> {
                out.write(body.listType.toIdBytes())
                out.write(body.data)
            }
        }
        val len = out.size - bodyStart
        if (len > 0xFFFFFFFFL) throw AepError.TooLarge()
        out.setU32(sizeAt, len)
        if (len % 2 == 1) {
            when {
                chunk.pad != null -> out.writeByte(chunk.pad.toInt())
                !isLast -> out.writeByte(0)
                else -> Unit
            }
        }
    }
}
