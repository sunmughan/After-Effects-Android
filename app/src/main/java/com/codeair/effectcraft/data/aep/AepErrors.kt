package com.codeair.effectcraft.data.aep

/**
 * Why a .aep/.aepx file could not be read or written.
 *
 * Port of `container/error.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Messages mirror the
 * official wording so a user (or a test) sees the same reason.
 */
sealed class AepError(message: String) : IllegalArgumentException(message) {
    /** The input is shorter than the 12-byte `RIFX` header, or does not start with `RIFX`. */
    class NotRifx : AepError("not an After Effects project: no RIFX header")

    /** The form type after the declared size is not `Egg!`. */
    class BadFormType(val form: String) :
        AepError("RIFX form type \"$form\" is not \"Egg!\"")

    /** The declared container size runs past the end of the input. */
    class Truncated(val declared: Long, val available: Long) :
        AepError("truncated file: the container declares $declared bytes, $available are available")

    /** A chunk declares more body bytes than remain in its parent. */
    class ChunkTooLarge(val offset: Int, val size: Long, val available: Long) :
        AepError("chunk at offset $offset declares $size bytes, only $available remain")

    /** The chunk structure is not well formed at [offset]. */
    class Malformed(val offset: Int, val what: String) :
        AepError("malformed chunk structure at offset $offset: $what")

    /** Nesting is deeper than the allowed maximum. */
    class TooDeep : AepError("chunks are nested too deeply")

    /** More chunks than the allowed maximum. */
    class TooManyChunks : AepError("too many chunks")

    /** A chunk or the whole file would not fit the 32-bit size fields when writing. */
    class TooLarge : AepError("chunk too large for the 32-bit size field")

    /** An `.aepx` is not well-formed XML of the subset After Effects writes. */
    class Xml(val offset: Int, val what: String) :
        AepError("not a readable After Effects XML project (.aepx): $what at byte $offset")

    /** Structural problems a lenient caller may repair by keeping a body opaque. */
    fun isStructural(): Boolean = this is ChunkTooLarge || this is Malformed || this is Truncated
}
