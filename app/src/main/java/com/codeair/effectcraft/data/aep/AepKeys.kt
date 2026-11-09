package com.codeair.effectcraft.data.aep

/**
 * Keyframe records, property flags and expressions.
 *
 * Port of `container/keys.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Keyframe times are **layer time**,
 * in units of the property's own time scale (equal to the composition's
 * timescale in every sample).
 *
 * Every record starts with an 8-byte header: `i32` time, `u8` at byte 4 = the
 * interpolation of the **incoming** side, `u8` at byte 5 = the **outgoing**
 * side (1 linear, 2 bezier, 3 hold), and a `u16` of flags (label, roving).
 * The rest depends on the property header (`tdb4`), which selects one of two
 * templates for `n` value components:
 *
 * * **plain** (`tdb4` byte 7 = 0): the value (`n` x `f64`) at byte 8, then four
 *   `f64` per component of ease, type-major. Size `8 + 40 n`.
 * * **spatial** (`tdb4` byte 7 != 0): a `u32` at 8, the arc length (`f64`) at
 *   16, the four ease slots at 24 to 55, the value (`n` x `f64`) at 56, then
 *   the spatial in tangent and out tangent (`n` x `f64` each). Size `56 + 24 n`.
 */
object AepKeys {

    /** A keyframe interpolation type (a byte of the record header). */
    sealed class Interpolation {
        /** `1` */
        object Linear : Interpolation()

        /** `2` */
        object Bezier : Interpolation()

        /** `3` */
        object Hold : Interpolation()

        /** Any other value. */
        class Other(val code: Int) : Interpolation()

        companion object {
            fun fromByte(b: Int): Interpolation = when (b) {
                1 -> Linear
                2 -> Bezier
                3 -> Hold
                else -> Other(b)
            }
        }

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Linear && other is Linear -> true
            this is Bezier && other is Bezier -> true
            this is Hold && other is Hold -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Linear -> 1
            is Bezier -> 2
            is Hold -> 3
            is Other -> code
        }
    }

    /** What a keyframe record is, judged by the property header and the record size. */
    sealed class KeyframeKind {
        /** The plain template: `8 + 40 n` bytes. */
        class Plain(val components: Int) : KeyframeKind()

        /** The spatial template: `56 + 24 n` bytes. */
        class Spatial(val components: Int) : KeyframeKind()

        /** 64 bytes: a mask path key; the path itself is in the matching `shap` chunk. */
        object MaskPath : KeyframeKind()

        /** 16 bytes: a marker (time and two hold bytes). */
        object Marker : KeyframeKind()

        /** Any other size. */
        class Other(val size: Int) : KeyframeKind()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Plain && other is Plain -> components == other.components
            this is Spatial && other is Spatial -> components == other.components
            this is MaskPath && other is MaskPath -> true
            this is Marker && other is Marker -> true
            this is Other && other is Other -> size == other.size
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Plain -> components
            is Spatial -> -components
            is MaskPath -> 64
            is Marker -> 16
            is Other -> size
        }
    }

    /** The ease of a key: speed and influence of the incoming and outgoing side. Speed is in
     * stored value units per second; influence is a fraction (20 % is `0.2`). */
    class KeyEase(
        val inSpeed: Double,
        val inInfluence: Double,
        val outSpeed: Double,
        val outInfluence: Double
    ) {
        override fun equals(other: Any?): Boolean =
            other is KeyEase && inSpeed == other.inSpeed && inInfluence == other.inInfluence &&
                outSpeed == other.outSpeed && outInfluence == other.outInfluence

        override fun hashCode(): Int {
            var result = inSpeed.hashCode()
            result = 31 * result + inInfluence.hashCode()
            result = 31 * result + outSpeed.hashCode()
            result = 31 * result + outInfluence.hashCode()
            return result
        }
    }

    /** The fields of a spatial keyframe record. */
    class SpatialKey(
        /** The `u32` at byte 8: `1` normally, `0` after the spatial interpolation was changed
         * to non-auto-bezier / non-continuous. */
        val autoBezierFlag: Long,
        /** The `f64` at byte 16: the arc length of the segment to the next key. Safe to ignore. */
        val segmentLength: Double,
        /** The four `f64` at bytes 24, 32, 40 and 48 as stored. */
        val ease: DoubleArray,
        /** The value (x, y, z in pixels for Position; ARGB 0..255 for a colour), from byte 56. */
        val value: List<Double>,
        /** The spatial in tangent as an offset from the value. */
        val inTangent: List<Double>,
        /** The spatial out tangent as an offset from the value. */
        val outTangent: List<Double>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SpatialKey) return false
            return autoBezierFlag == other.autoBezierFlag && segmentLength == other.segmentLength &&
                ease.contentEquals(other.ease) && value == other.value &&
                inTangent == other.inTangent && outTangent == other.outTangent
        }

        override fun hashCode(): Int {
            var result = autoBezierFlag.hashCode()
            result = 31 * result + segmentLength.hashCode()
            result = 31 * result + ease.contentHashCode()
            result = 31 * result + value.hashCode()
            result = 31 * result + inTangent.hashCode()
            result = 31 * result + outTangent.hashCode()
            return result
        }
    }

    /** The bytes of a property header that mark an expression: bytes 76, 77 and 120 of `tdb4`
     * are all 1 when the property has an expression. Byte 119 is 1 when the expression is disabled. */
    class ExpressionFlags(
        val byte76: Int,
        val byte77: Int,
        /** 1 when the expression is disabled (`expression.disable_all`); the text stays in the file. */
        val byte119: Int,
        val byte120: Int
    ) {
        /** True when bytes 76, 77 and 120 are all 1. */
        fun allSet(): Boolean = byte76 == 1 && byte77 == 1 && byte120 == 1

        /** True when byte 119 is 1. */
        fun disabled(): Boolean = byte119 == 1
    }

    private fun doubles(b: ByteArray, at: Int, n: Int): List<Double>? {
        val out = ArrayList<Double>(n)
        for (i in 0 until n) {
            out.add(beF64(b, at + i * 8) ?: return null)
        }
        return out
    }

    private fun easeAt(b: ByteArray, at: Int): KeyEase? {
        val inSpeed = beF64(b, at) ?: return null
        val inInfluence = beF64(b, at + 8) ?: return null
        val outSpeed = beF64(b, at + 16) ?: return null
        val outInfluence = beF64(b, at + 24) ?: return null
        return KeyEase(inSpeed, inInfluence, outSpeed, outInfluence)
    }
}

/** The record kind. For a list that belongs to a property, the header's component count and
 * template flag decide; without a property, 48 bytes is read as plain with one component and
 * 128 bytes as spatial with three. */
fun AepTypedView.Keyframe.kind(): AepKeys.KeyframeKind {
    val len = record.size
    val layout = layout
    if (layout != null) {
        val n = layout.components
        if (n > 0) {
            if (layout.spatial && len == 56 + 24 * n) {
                return AepKeys.KeyframeKind.Spatial(n)
            }
            if (!layout.spatial && len == 8 + 40 * n) {
                return AepKeys.KeyframeKind.Plain(n)
            }
        }
    }
    return when (len) {
        64 -> AepKeys.KeyframeKind.MaskPath
        16 -> AepKeys.KeyframeKind.Marker
        48 -> if (layout == null) AepKeys.KeyframeKind.Plain(1) else AepKeys.KeyframeKind.Other(len)
        128 -> if (layout == null) AepKeys.KeyframeKind.Spatial(3) else AepKeys.KeyframeKind.Other(len)
        else -> AepKeys.KeyframeKind.Other(len)
    }
}

/** The time in seconds given the property's time scale (units per second). */
fun AepTypedView.Keyframe.seconds(timeScale: Long): Double? {
    if (timeScale == 0L) return null
    val units = timeUnits() ?: return null
    return units.toDouble() / timeScale.toDouble()
}

/** The interpolation of the incoming side: the byte at offset 4. */
fun AepTypedView.Keyframe.interpolationIn(): AepKeys.Interpolation? =
    record.getOrNull(4)?.toInt()?.and(0xFF)?.let { AepKeys.Interpolation.fromByte(it) }

/** The interpolation of the outgoing side: the byte at offset 5. */
fun AepTypedView.Keyframe.interpolationOut(): AepKeys.Interpolation? =
    record.getOrNull(5)?.toInt()?.and(0xFF)?.let { AepKeys.Interpolation.fromByte(it) }

/** The flags word at byte 6. */
fun AepTypedView.Keyframe.flags(): Int? = beU16(record, 6)

/** The keyframe label: bits 6 to 9 of the flags. */
fun AepTypedView.Keyframe.label(): Int? {
    val flags = flags() ?: return null
    return (flags shr 6) and 0x0f
}

/** True when the key is roving: bit `0x20` of the flags. */
fun AepTypedView.Keyframe.isRoving(): Boolean? {
    val flags = flags() ?: return null
    return flags and 0x0020 != 0
}

/** The number of value components of a plain or spatial record. */
fun AepTypedView.Keyframe.components(): Int? = when (val kind = kind()) {
    is AepKeys.KeyframeKind.Plain -> kind.components
    is AepKeys.KeyframeKind.Spatial -> kind.components
    else -> null
}

/** The stored value of a plain (from byte 8) or spatial (from byte 56) record. */
fun AepTypedView.Keyframe.value(): List<Double>? {
    fun doublesAt(at: Int, n: Int): List<Double>? {
        val out = ArrayList<Double>(n)
        for (i in 0 until n) out.add(beF64(record, at + i * 8) ?: return null)
        return out
    }
    return when (val kind = kind()) {
        is AepKeys.KeyframeKind.Plain -> doublesAt(8, kind.components)
        is AepKeys.KeyframeKind.Spatial -> doublesAt(56, kind.components)
        else -> null
    }
}

/** The value of a record with one component (Opacity, Rotation, a slider). */
fun AepTypedView.Keyframe.scalarValue(): Double? {
    val kind = kind()
    return if (kind is AepKeys.KeyframeKind.Plain && kind.components == 1) beF64(record, 8) else null
}

/** The value of a 3-component spatial keyframe (Position), x, y and z in pixels. */
fun AepTypedView.Keyframe.spatialValue(): DoubleArray? {
    val v = value() ?: return null
    if (kind() != AepKeys.KeyframeKind.Spatial(3)) return null
    return doubleArrayOf(v[0], v[1], v[2])
}

/** The ease of the key: the four slots at byte 16 of a one-component plain record or at byte 24
 * of a spatial record or a mask path record. */
fun AepTypedView.Keyframe.ease(): AepKeys.KeyEase? {
    fun easeAt(at: Int): AepKeys.KeyEase? {
        val inSpeed = beF64(record, at) ?: return null
        val inInfluence = beF64(record, at + 8) ?: return null
        val outSpeed = beF64(record, at + 16) ?: return null
        val outInfluence = beF64(record, at + 24) ?: return null
        return AepKeys.KeyEase(inSpeed, inInfluence, outSpeed, outInfluence)
    }
    return when (val kind = kind()) {
        is AepKeys.KeyframeKind.Plain -> if (kind.components == 1) easeAt(16) else null
        is AepKeys.KeyframeKind.Spatial -> easeAt(24)
        is AepKeys.KeyframeKind.MaskPath -> easeAt(24)
        else -> null
    }
}

/** The `4 n` ease `f64` of a plain record as stored (from byte `8 + 8 n`). */
fun AepTypedView.Keyframe.plainEaseSlots(): List<Double>? {
    val kind = kind() as? AepKeys.KeyframeKind.Plain ?: return null
    val n = kind.components
    val out = ArrayList<Double>(n * 4)
    for (i in 0 until n * 4) out.add(beF64(record, 8 + 8 * n + i * 8) ?: return null)
    return out
}

/** The ease of one value component. A plain record stores the `4 n` ease doubles type-major:
 * `[in speed x n][in influence x n][out speed x n][out influence x n]` starting at byte
 * `8 + 8 n`, and component `i` uses slot `i` of each block. */
fun AepTypedView.Keyframe.easeComponent(component: Int): AepKeys.KeyEase? {
    fun easeAt(at: Int): AepKeys.KeyEase? {
        val inSpeed = beF64(record, at) ?: return null
        val inInfluence = beF64(record, at + 8) ?: return null
        val outSpeed = beF64(record, at + 16) ?: return null
        val outInfluence = beF64(record, at + 24) ?: return null
        return AepKeys.KeyEase(inSpeed, inInfluence, outSpeed, outInfluence)
    }
    return when (val kind = kind()) {
        is AepKeys.KeyframeKind.Plain -> {
            if (component >= kind.components) return null
            val block = kind.components * 8
            val first = block + 8
            fun at(k: Int): Int = first + block * k + component * 8
            AepKeys.KeyEase(
                inSpeed = beF64(record, at(0)) ?: return null,
                inInfluence = beF64(record, at(1)) ?: return null,
                outSpeed = beF64(record, at(2)) ?: return null,
                outInfluence = beF64(record, at(3)) ?: return null
            )
        }
        is AepKeys.KeyframeKind.Spatial -> if (component == 0) easeAt(24) else null
        else -> null
    }
}

/** The ease of every value component of a plain record (one entry for a spatial record). */
fun AepTypedView.Keyframe.easeComponents(): List<AepKeys.KeyEase>? {
    val n = when (val kind = kind()) {
        is AepKeys.KeyframeKind.Plain -> kind.components
        is AepKeys.KeyframeKind.Spatial -> 1
        else -> return null
    }
    val out = ArrayList<AepKeys.KeyEase>(n)
    for (i in 0 until n) out.add(easeComponent(i) ?: return null)
    return out
}

/** The Euclidean ARGB distance to the next key that After Effects writes at byte 16 of a colour
 * (4-component spatial) record whose interpolation is not linear. Null for other records. */
fun AepTypedView.Keyframe.colorDistance(): Double? {
    val kind = kind()
    return if (kind is AepKeys.KeyframeKind.Spatial && kind.components == 4) beF64(record, 16) else null
}

/** The decoded spatial record (Position: 128 bytes, colour: 152 bytes). */
fun AepTypedView.Keyframe.spatial(): AepKeys.SpatialKey? {
    val kind = kind() as? AepKeys.KeyframeKind.Spatial ?: return null
    val n = kind.components
    val span = n * 8
    fun doublesAt(at: Int, count: Int): List<Double>? {
        val out = ArrayList<Double>(count)
        for (i in 0 until count) out.add(beF64(record, at + i * 8) ?: return null)
        return out
    }
    fun easeAt(at: Int): AepKeys.KeyEase? {
        val inSpeed = beF64(record, at) ?: return null
        val inInfluence = beF64(record, at + 8) ?: return null
        val outSpeed = beF64(record, at + 16) ?: return null
        val outInfluence = beF64(record, at + 24) ?: return null
        return AepKeys.KeyEase(inSpeed, inInfluence, outSpeed, outInfluence)
    }
    val ease = easeAt(24) ?: return null
    return AepKeys.SpatialKey(
        autoBezierFlag = beU32(record, 8) ?: return null,
        segmentLength = beF64(record, 16) ?: return null,
        ease = doubleArrayOf(ease.inSpeed, ease.inInfluence, ease.outSpeed, ease.outInfluence),
        value = doublesAt(56, n) ?: return null,
        inTangent = doublesAt(56 + span, n) ?: return null,
        outTangent = doublesAt(56 + 2 * span, n) ?: return null
    )
}

/** The time scale (units per second) of this property's keyframe times: the `u32` at byte 12
 * of `tdb4`. */
fun AepTypedView.Property.timeScale(): Long? {
    val h = header() ?: return null
    return beU32(h, 12)
}

/** The property's `tdsb` flags: `1` for a property that was written (non-default), `3` for the default state. */
fun AepTypedView.Property.flags(): Long? {
    val bytes = chunk.child("tdsb")?.bytes() ?: return null
    return beU32(bytes, 0)
}

/** True when `tdb4` byte 5 has bit 0 set: the property is static, it has no keyframes. */
fun AepTypedView.Property.isStaticFlagSet(): Boolean? {
    val h = header() ?: return null
    val b = h.getOrNull(5) ?: return null
    return b.toInt() and 1 != 0
}

/** The expression source: the raw UTF-8 text of the `Utf8` child that follows the value in the
 * property (no length prefix, no engine tag; the engine is project-wide). */
fun AepTypedView.Property.expression(): String? =
    chunk.child("Utf8")?.bytes()?.let { bytesToText(it) }

/** The expression flag bytes of `tdb4`. */
fun AepTypedView.Property.expressionFlags(): AepKeys.ExpressionFlags? {
    val h = header() ?: return null
    return AepKeys.ExpressionFlags(
        byte76 = h.getOrNull(76)?.toInt()?.and(0xFF) ?: return null,
        byte77 = h.getOrNull(77)?.toInt()?.and(0xFF) ?: return null,
        byte119 = h.getOrNull(119)?.toInt()?.and(0xFF) ?: return null,
        byte120 = h.getOrNull(120)?.toInt()?.and(0xFF) ?: return null
    )
}

/** Whether the expression is enabled: null when the property has no expression, else false when
 * byte 119 marks it disabled. */
fun AepTypedView.Property.expressionEnabled(): Boolean? {
    val flags = expressionFlags() ?: return null
    if (flags.byte120 != 1 || expression() == null) return null
    return !flags.disabled()
}

/** The value of the first keyframe (plain or spatial record), for reading an animated property
 * at its first key. */
fun AepTypedView.Property.firstKeyValue(): List<Double>? =
    keys()?.keyframes()?.firstOrNull()?.value()
