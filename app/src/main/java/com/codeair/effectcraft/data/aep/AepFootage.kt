package com.codeair.effectcraft.data.aep

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Item ids and labels, footage items (solids and files), precomp linkage, and the name and
 * size of a layer's source.
 *
 * Port of `container/footage.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0).
 */
object AepFootage {

    /** The footage data of an item (`LIST Pin ` with `sspc`, `opti` and, for files, `Als2/alas`). */
    class Footage(
        /** The 4-character file type tag at byte 22 of `sspc` (`Soli` for a solid, `png!` for a png). */
        val fileType: String,
        /** The width in pixels (`u32` at byte 30 of `sspc`). */
        val width: Long,
        /** The height in pixels (`u32` at byte 34 of `sspc`). */
        val height: Long,
        /** The display name stored in `opti` (the item's own `Utf8` name is empty for footage):
         * at byte 26 for a solid and at byte 58 for a file. */
        val name: String?,
        /** True for a solid (tag `Soli` at the start of `opti`). */
        val isSolid: Boolean,
        /** The colour of a solid as ARGB floats 0..1 (`opti` bytes 10, 14, 18, 22). */
        val solidArgb: FloatArray?,
        /** The file path from the alias JSON (`Als2/alas`, key `fullpath`); none for solids. For an
         * image sequence it is the sequence **folder**. */
        val path: String?,
        /** The duration in seconds as stored (`sspc` `u32` at byte 38 over `u32` at byte 42). */
        val duration: AepHeader.Rational?,
        /** The native frame rate (`sspc` `u32` at byte 56): 30 for the image sequence, 0 for stills,
         * audio and solids (also 0 when the byte is missing). */
        val nativeFrameRate: Long,
        /** The bits per pixel (`sspc` `u32` at byte 60). */
        val bitsPerPixel: Long,
        /** The size in bytes of the source file(s) (`sspc` `u32` at byte 208). */
        val fileSize: Long,
        /** The audio sample rate (`sspc` `f64` at byte 160); null for footage without audio. */
        val audioSampleRate: Double?,
        /** True when the alias points at a folder (`"target_is_folder":true`): an image sequence
         * is ONE item whose alias is its folder. */
        val isSequence: Boolean
    ) {
        /** The number of frames of an image sequence (`duration * native_frame_rate`); null for
         * anything else. */
        fun sequenceFrameCount(): Long? {
            if (!isSequence) return null
            val d = duration ?: return null
            val frames = BigInteger.valueOf(d.num).multiply(BigInteger.valueOf(nativeFrameRate))
            val den = BigInteger.valueOf(d.den)
            if (den <= BigInteger.ZERO || frames.mod(den) != BigInteger.ZERO) return null
            return try {
                frames.divide(den).longValueExact()
            } catch (e: ArithmeticException) {
                null
            }?.takeIf { it in 0L..0xFFFFFFFFL }
        }

        /** The solid's colour as fractions R, G, B. */
        fun solidRgb(): FloatArray? {
            val argb = solidArgb ?: return null
            return floatArrayOf(argb[1], argb[2], argb[3])
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Footage) return false
            return fileType == other.fileType && width == other.width && height == other.height &&
                name == other.name && isSolid == other.isSolid &&
                (solidArgb == null && other.solidArgb == null ||
                    solidArgb != null && other.solidArgb != null && solidArgb.contentEquals(other.solidArgb)) &&
                path == other.path && duration == other.duration &&
                nativeFrameRate == other.nativeFrameRate && bitsPerPixel == other.bitsPerPixel &&
                fileSize == other.fileSize && audioSampleRate == other.audioSampleRate &&
                isSequence == other.isSequence
        }

        override fun hashCode(): Int {
            var result = fileType.hashCode()
            result = 31 * result + width.hashCode()
            result = 31 * result + height.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + isSolid.hashCode()
            result = 31 * result + (solidArgb?.contentHashCode() ?: 0)
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + (duration?.hashCode() ?: 0)
            result = 31 * result + nativeFrameRate.hashCode()
            result = 31 * result + bitsPerPixel.hashCode()
            result = 31 * result + fileSize.hashCode()
            result = 31 * result + (audioSampleRate?.hashCode() ?: 0)
            result = 31 * result + isSequence.hashCode()
            return result
        }
    }

    /** The boolean value of `"key"` in a flat JSON object (`true` / `false`); null when missing. */
    fun jsonBool(json: ByteArray, key: String): Boolean? {
        val needle = "\"$key\":".toByteArray(Charsets.UTF_8)
        val at = json.indexOf(needle) ?: return null
        val rest = json.copyOfRange(at + needle.size, json.size)
        val trueBytes = "true".toByteArray(Charsets.UTF_8)
        val falseBytes = "false".toByteArray(Charsets.UTF_8)
        return when {
            rest.size >= trueBytes.size && rest.copyOfRange(0, trueBytes.size).contentEquals(trueBytes) -> true
            rest.size >= falseBytes.size && rest.copyOfRange(0, falseBytes.size).contentEquals(falseBytes) -> false
            else -> null
        }
    }

    /** The string value of `"key"` in a flat JSON object, with the standard escapes decoded.
     * Returns null when the key is missing or the value is not a string. The key is the first
     * occurrence of `"key"` that is followed by `:` (the same text as a value is skipped). */
    fun jsonString(json: ByteArray, key: String): String? {
        val needle = "\"$key\"".toByteArray(Charsets.UTF_8)
        var from = 0
        while (true) {
            val at = json.indexOfFrom(from, needle) ?: return null
            val after = at + needle.size
            val value = jsonStringAt(json, after)
            if (value != null) return value
            from = at + 1
        }
    }

    /** The string value right after a key ending at [i] (`: "…"`), or null when none follows. */
    private fun jsonStringAt(json: ByteArray, start: Int): String? {
        var i = start
        fun skipWs() {
            while (i < json.size && json[i].isAsciiWs()) i++
        }
        skipWs()
        if (json.getOrNull(i) != ':'.code.toByte()) return null
        i++
        skipWs()
        if (json.getOrNull(i) != '"'.code.toByte()) return null
        i++
        val out = ByteArrayOutputStream()
        while (true) {
            val c = json.getOrNull(i) ?: return null
            i++
            when (c) {
                '"'.code.toByte() -> return bytesToText(out.toByteArray())
                '\\'.code.toByte() -> {
                    val e = json.getOrNull(i) ?: return null
                    i++
                    when (e) {
                        'n'.code.toByte() -> out.write('\n'.code)
                        't'.code.toByte() -> out.write('\t'.code)
                        'r'.code.toByte() -> out.write('\r'.code)
                        'b'.code.toByte() -> out.write(8)
                        'f'.code.toByte() -> out.write(12)
                        'u'.code.toByte() -> {
                            fun hex4(at: Int): Int? {
                                if (at < 0 || at + 4 > json.size) return null
                                val text = String(json.copyOfRange(at, at + 4), Charsets.US_ASCII)
                                return text.toIntOrNull(16)
                            }
                            var code = hex4(i) ?: return null
                            i += 4
                            // A character outside the BMP is a high surrogate and a `\u` low surrogate.
                            if (code in 0xD800 until 0xDC00 &&
                                json.getOrNull(i) == '\\'.code.toByte() &&
                                json.getOrNull(i + 1) == 'u'.code.toByte()
                            ) {
                                val lo = hex4(i + 2)
                                if (lo != null && lo in 0xDC00 until 0xE000) {
                                    code = 0x10000 + ((code - 0xD800) shl 10) + (lo - 0xDC00)
                                    i += 6
                                }
                            }
                            val text = if (code in 0xD800 until 0xE000) {
                                // A lone surrogate half stays U+FFFD.
                                "\uFFFD"
                            } else {
                                String(Character.toChars(code))
                            }
                            out.write(text.toByteArray(Charsets.UTF_8))
                        }
                        else -> out.write(e.toInt())
                    }
                }
                else -> out.write(c.toInt())
            }
        }
    }

    private fun Byte.isAsciiWs(): Boolean =
        this == ' '.code.toByte() || this == '\t'.code.toByte() ||
            this == '\n'.code.toByte() || this == '\r'.code.toByte() ||
            this == 0x0C.toByte()

    /** The numbering of an image sequence item, or its file list when it was imported with
     * Force alphabetical order. */
    class SequenceInfo(
        /** The file name before the frame number (`seq_`, `shot_`). */
        val prefix: String,
        /** The file name after the frame number (`.png`, `.exr`). */
        val suffix: String,
        /** The first and last frame number. */
        val start: Long,
        val end: Long,
        /** The digits of the frame number. */
        val digits: Long,
        /** The file names in order for a sequence imported with Force alphabetical order;
         * empty for a numbered sequence. When set, it replaces the numbering. */
        val files: List<String>
    ) {
        /** The file name of frame [n] (`shot_0101.exr`). */
        fun fileName(n: Long): String {
            val width = digits.coerceAtMost(16).toInt()
            return prefix + n.toString().padStart(width, '0') + suffix
        }

        /** The number of files: the listed files, or the frame numbers from start to end. */
        fun frameCount(): Long {
            return if (files.isEmpty()) {
                if (end < start) 0 else end - start + 1
            } else {
                files.size.toLong()
            }
        }

        /** The name of the `i`-th file (from 0): from the file list, or numbered from `start`;
         * null past the last file. */
        fun nthFileName(i: Long): String? {
            return if (files.isEmpty()) {
                val n = start + i
                if (n <= end) fileName(n) else null
            } else {
                files.getOrNull(i.toInt())
            }
        }

        override fun equals(other: Any?): Boolean =
            other is SequenceInfo && prefix == other.prefix && suffix == other.suffix &&
                start == other.start && end == other.end && digits == other.digits &&
                files == other.files

        override fun hashCode(): Int {
            var result = prefix.hashCode()
            result = 31 * result + suffix.hashCode()
            result = 31 * result + start.hashCode()
            result = 31 * result + end.hashCode()
            result = 31 * result + digits.hashCode()
            result = 31 * result + files.hashCode()
            return result
        }
    }

    /** The file names of a `LIST StVc` (Force alphabetical order). Empty when unreadable. */
    internal fun sequenceFileList(list: AepContainer.Chunk): List<String> {
        val count = list.child("StVS")?.bytes()?.let { leU32(it, 0) }?.toInt() ?: return emptyList()
        val names = list.children().filter { it.hasId("Utf8") }
            .mapNotNull { it.bytes() }.take(count).map { bytesToText(it) }
            .filter { it.isNotEmpty() }
        if (names.any { n ->
                n.any { c -> c == '/' || c == '\\' || c == ':' || c.isISOControl() } ||
                    n == "." || n == ".."
            }
        ) {
            return emptyList()
        }
        return names
    }

    /** The prefix and suffix of a sequence whose naming holds one `Utf8` (`part`). */
    internal fun singleNamePart(part: String, firstFile: String?): Pair<String, String>? {
        if (firstFile != null && part.isNotEmpty()) {
            when {
                firstFile.startsWith(part) && !firstFile.endsWith(part) -> return part to ""
                !firstFile.startsWith(part) && firstFile.endsWith(part) -> return "" to part
            }
        }
        return if (part.startsWith('.')) "" to part else null
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int? = indexOfFrom(0, needle)

    private fun ByteArray.indexOfFrom(from: Int, needle: ByteArray): Int? {
        if (needle.isEmpty() || from > size - needle.size) return null
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}

/** The numbering of an image sequence (an item whose alias is a folder); null for anything else. */
fun AepTypedView.Item.sequenceInfo(): AepFootage.SequenceInfo? {
    val f = footage() ?: return null
    if (!f.isSequence) return null
    val pin = chunk.childList("Pin ") ?: return null
    val sspc = pin.child("sspc")?.bytes() ?: return null
    val kids = pin.children()
    val after = kids.indexOfFirst { it.isList("Als2") }.takeIf { it >= 0 } ?: return null
    val naming = kids.drop(after + 1).takeWhile { !it.hasId("opti") }
    val start = beU32(sspc, 172) ?: return null
    val end = beU32(sspc, 176) ?: return null
    val digits = beU32(sspc, 180) ?: return null
    // Force alphabetical order: the file list replaces the numbering.
    val stVc = naming.firstOrNull { it.isList("StVc") }
    if (stVc != null) {
        val files = AepFootage.sequenceFileList(stVc)
        if (files.isEmpty()) return null
        return AepFootage.SequenceInfo("", "", start, end, digits, files)
    }
    val names = naming.filter { it.hasId("Utf8") }.mapNotNull { it.bytes() }
        .map { bytesToText(it) }.take(2)
    val (prefix, suffix) = when (names.size) {
        2 -> names[0] to names[1]
        // An empty prefix is not stored (`0001.png` has only `.png`).
        1 -> AepFootage.singleNamePart(names[0], f.name) ?: return null
        else -> return null
    }
    return AepFootage.SequenceInfo(prefix, suffix, start, end, digits, emptyList())
}

/** The rate a clip is conformed to (Interpret Footage ▸ Conform to frame rate); null when not
 * conformed or for an item without footage data. */
fun AepTypedView.Item.conformFrameRate(): Long? {
    val sspc = chunk.childList("Pin ")?.child("sspc")?.bytes() ?: return null
    return beU32(sspc, 146)?.takeIf { it > 0 }
}

/** The item id: the big-endian `u32` at byte 16 of `idta` (what a layer's source id refers to). */
fun AepTypedView.Item.id(): Long? {
    val bytes = chunk.child("idta")?.bytes() ?: return null
    return beU32(bytes, 16)
}

/** The label colour index, byte 58 of `idta`. */
fun AepTypedView.Item.label(): Int? =
    chunk.child("idta")?.bytes()?.getOrNull(58)?.toInt()?.and(0xFF)

/** The ids of the compositions this item uses as precomp layers. */
fun AepTypedView.Item.usedItemIds(): List<Long> =
    chunk.children().filter { it.hasId("idpi") }
        .mapNotNull { it.bytes()?.let { bytes -> leU32(bytes, 0) } }

/** The footage data of a footage item. */
fun AepTypedView.Item.footage(): AepFootage.Footage? {
    if (kind() != AepTypedView.ItemKind.Footage) return null
    val pin = chunk.childList("Pin ") ?: return null
    val sspc = pin.child("sspc")?.bytes() ?: return null
    val opti = pin.child("opti")?.bytes() ?: return null
    val isSolid = opti.size >= 4 && opti.copyOfRange(0, 4).contentEquals("Soli".toByteArray(Charsets.US_ASCII))
    val alias = pin.childList("Als2")?.child("alas")?.bytes()
    val path = alias?.let { AepFootage.jsonString(it, "fullpath") }
    val isSequence = alias?.let { AepFootage.jsonBool(it, "target_is_folder") } ?: false
    val solidArgb = if (isSolid) {
        floatArrayOf(
            beF32(opti, 10) ?: return null,
            beF32(opti, 14) ?: return null,
            beF32(opti, 18) ?: return null,
            beF32(opti, 22) ?: return null
        )
    } else {
        null
    }
    val durationNum = beU32(sspc, 38)
    val durationDen = beU32(sspc, 42)
    return AepFootage.Footage(
        fileType = cstr(sspc, 22, 4) ?: return null,
        width = beU32(sspc, 30) ?: return null,
        height = beU32(sspc, 34) ?: return null,
        name = cstr(opti, if (isSolid) 26 else 58, 256)?.takeIf { it.isNotEmpty() },
        isSolid = isSolid,
        solidArgb = solidArgb,
        path = path,
        duration = if (durationNum != null && durationDen != null) {
            AepHeader.Rational(durationNum, durationDen)
        } else {
            null
        },
        nativeFrameRate = beU32(sspc, 56) ?: 0,
        bitsPerPixel = beU32(sspc, 60) ?: 0,
        fileSize = beU32(sspc, 208) ?: 0,
        audioSampleRate = beF64(sspc, 160)?.takeIf { it.isFinite() && it > 0.0 },
        isSequence = isSequence
    )
}

/** The name this item shows in the project: the item name, or for footage the name stored in `opti`. */
fun AepTypedView.Item.displayName(): String? =
    name() ?: footage()?.name

/** The item with this id (`idta` byte 16), searching every item including those in folders. */
fun AepTypedView.Project.itemById(id: Long): AepTypedView.Item? =
    allItems().firstOrNull { it.id() == id }

/** The item a layer is made from (its source id), e.g. the solid, the footage or the precomp. */
fun AepTypedView.Project.layerSource(layer: AepTypedView.Layer): AepTypedView.Item? {
    val sourceId = layer.header()?.sourceId ?: return null
    return itemById(sourceId)
}

/** The name a layer shows: its own name, or its source's name when it has none. */
fun AepTypedView.Project.layerDisplayName(layer: AepTypedView.Layer): String? =
    layer.name() ?: layerSource(layer)?.displayName()

/** The pixel size of a layer's source: a footage item's width and height, or a precomp's
 * composition size. */
fun AepTypedView.Project.layerSourceSize(layer: AepTypedView.Layer): Pair<Long, Long>? {
    val source = layerSource(layer) ?: return null
    return when (source.kind()) {
        AepTypedView.ItemKind.Footage -> source.footage()?.let { it.width to it.height }
        AepTypedView.ItemKind.Composition -> source.comp()?.header()?.let {
            it.width.toLong() to it.height.toLong()
        }
        else -> null
    }
}
