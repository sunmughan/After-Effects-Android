package com.codeair.effectcraft.data.aep

/**
 * Bounds-checked big-endian readers for the .aep container.
 *
 * Port of `container/bytes.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Every read returns null
 * when the bytes are not there; nothing here indexes a slice directly,
 * so hostile input can never crash the reader.
 */
internal fun bytesToText(bytes: ByteArray): String =
    String(bytes, Charsets.UTF_8)

/** Lossy text, as the official reader does for names (never throws). */
internal fun bytesToTextLossy(bytes: ByteArray): String =
    String(bytes, Charsets.UTF_8)

/** The bytes up to the first NUL (all of them when there is none). */
internal fun untilNul(bytes: ByteArray): ByteArray {
    val end = bytes.indexOf(0.toByte()).takeIf { it >= 0 } ?: bytes.size
    return bytes.copyOfRange(0, end)
}

/** A NUL-terminated ASCII string at [at], at most [max] bytes long. */
internal fun cstr(bytes: ByteArray, at: Int, max: Int): String? {
    val end = (at + max).coerceAtMost(bytes.size)
    if (at < 0 || at >= end) return null
    val field = bytes.copyOfRange(at, end)
    return String(untilNul(field), Charsets.US_ASCII)
}

internal fun beU16(bytes: ByteArray, at: Int): Int? {
    if (at < 0 || at + 2 > bytes.size) return null
    return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}

internal fun beU32(bytes: ByteArray, at: Int): Long? {
    if (at < 0 || at + 4 > bytes.size) return null
    var value = 0L
    for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}

internal fun beI32(bytes: ByteArray, at: Int): Int? =
    beU32(bytes, at)?.toInt()

internal fun leU32(bytes: ByteArray, at: Int): Long? {
    if (at < 0 || at + 4 > bytes.size) return null
    var value = 0L
    for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}

internal fun beF32(bytes: ByteArray, at: Int): Float? {
    val bits = beU32(bytes, at)?.toInt() ?: return null
    return Float.fromBits(bits)
}

internal fun beF64(bytes: ByteArray, at: Int): Double? {
    if (at < 0 || at + 8 > bytes.size) return null
    var bits = 0L
    for (i in 0 until 8) bits = (bits shl 8) or (bytes[at + i].toLong() and 0xFF)
    return Double.fromBits(bits)
}

/** [n] bytes starting at [at], or null when they are not there. */
internal fun bytesAt(bytes: ByteArray, at: Int, n: Int): ByteArray? {
    if (at < 0 || n < 0 || at + n > bytes.size) return null
    return bytes.copyOfRange(at, at + n)
}

/** A 4-byte chunk id as a Latin-1 string (ids are printable ASCII; spaces are significant). */
internal fun id4(bytes: ByteArray, at: Int): String? {
    if (at < 0 || at + 4 > bytes.size) return null
    return String(bytes.copyOfRange(at, at + 4), Charsets.ISO_8859_1)
}

internal fun String.toIdBytes(): ByteArray =
    toByteArray(Charsets.ISO_8859_1).copyOf(4)
