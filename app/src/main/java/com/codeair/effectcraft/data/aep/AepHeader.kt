package com.codeair.effectcraft.data.aep

import java.math.BigInteger

/**
 * The layer header (`ldta`) and the composition header (`cdta`, `PRin`).
 *
 * Port of `container/header.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Every field here has a verified
 * layout: the decoded value equals the value the generated corpus was made
 * with. Fields the corpus does not decide are not decoded; the raw bytes stay
 * reachable through the chunk.
 */
object AepHeader {

    /** An exact fraction. Used for times (`seconds = num / den`) and for the pixel aspect ratio.
     * The fields hold the stored values unreduced; [reduced] normalises. */
    class Rational(val num: Long, val den: Long) {
        companion object {
            /** A fraction with a positive denominator, reduced; null for a zero denominator. */
            fun new(num: Long, den: Long): Rational? = Rational(num, den).reduced()
        }

        /** The value as `Double`, null for a zero denominator. */
        fun toDoubleOrNull(): Double? =
            if (den != 0L) num.toDouble() / den.toDouble() else null

        /** The same value in lowest terms with a positive denominator, null for a zero denominator. */
        fun reduced(): Rational? {
            if (den == 0L) return null
            val a = BigInteger.valueOf(num).abs()
            val b = BigInteger.valueOf(den).abs()
            var g = a.gcd(b)
            if (g == BigInteger.ZERO) g = BigInteger.ONE
            val reducedNum = a.divide(g)
            val reducedDen = b.divide(g)
            val negative = (num < 0) != (den < 0)
            val signedNum = if (negative) reducedNum.negate() else reducedNum
            return try {
                Rational(signedNum.longValueExact(), reducedDen.longValueExact())
            } catch (e: ArithmeticException) {
                null
            }
        }

        /** `self + other * factor`, exact, null on a zero denominator or overflow. */
        fun plusScaled(other: Rational, factor: Rational): Rational? {
            if (den == 0L || other.den == 0L || factor.den == 0L) return null
            val scaledNum = BigInteger.valueOf(other.num).multiply(BigInteger.valueOf(factor.num))
            val scaledDen = BigInteger.valueOf(other.den).multiply(BigInteger.valueOf(factor.den))
            val denBig = BigInteger.valueOf(den).multiply(scaledDen)
            val numBig = BigInteger.valueOf(num).multiply(scaledDen)
                .add(scaledNum.multiply(BigInteger.valueOf(den)))
            return try {
                Rational(numBig.longValueExact(), denBig.longValueExact()).reduced()
            } catch (e: ArithmeticException) {
                null
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Rational && num == other.num && den == other.den

        override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

        override fun toString(): String = "$num/$den"
    }

    /** The layer quality switch, `ldta` byte 5. */
    sealed class Quality {
        /** `1` */
        object Draft : Quality()

        /** `2` */
        object Best : Quality()

        /** Any other value (`0` on cameras and lights). */
        class Other(val code: Int) : Quality()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Draft && other is Draft -> true
            this is Best && other is Best -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Draft -> 1
            is Best -> 2
            is Other -> code
        }
    }

    /** The blend mode, `ldta` byte 99. Six modes were sampled; any other mode is kept as its raw code. */
    sealed class BlendMode {
        /** `2` */
        object Normal : BlendMode()

        /** `4` */
        object Add : BlendMode()

        /** `5` */
        object Multiply : BlendMode()

        /** `6` */
        object Screen : BlendMode()

        /** `7` */
        object Overlay : BlendMode()

        /** `26` */
        object Difference : BlendMode()

        /** Any other value (not sampled; `0` on cameras and lights). */
        class Other(val code: Int) : BlendMode()

        companion object {
            /** The mode for a code. */
            fun fromCode(code: Int): BlendMode = when (code) {
                2 -> Normal
                4 -> Add
                5 -> Multiply
                6 -> Screen
                7 -> Overlay
                26 -> Difference
                else -> Other(code)
            }
        }

        /** The code as stored. */
        fun code(): Int = when (this) {
            is Normal -> 2
            is Add -> 4
            is Multiply -> 5
            is Screen -> 6
            is Overlay -> 7
            is Difference -> 26
            is Other -> code
        }

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Normal && other is Normal -> true
            this is Add && other is Add -> true
            this is Multiply && other is Multiply -> true
            this is Screen && other is Screen -> true
            this is Overlay && other is Overlay -> true
            this is Difference && other is Difference -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = code()
    }

    /** The light type, `ldta` `u32` at byte 136 of a light layer (0 on every other layer). */
    sealed class LightType {
        /** `0` */
        object Parallel : LightType()

        /** `1` */
        object Spot : LightType()

        /** `2` */
        object Point : LightType()

        /** `3` */
        object Ambient : LightType()

        /** Any other value. */
        class Other(val code: Long) : LightType()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Parallel && other is Parallel -> true
            this is Spot && other is Spot -> true
            this is Point && other is Point -> true
            this is Ambient && other is Ambient -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Parallel -> 0
            is Spot -> 1
            is Point -> 2
            is Ambient -> 3
            is Other -> code.hashCode()
        }
    }

    /** The layer type, `ldta` `u32` at byte 128. */
    sealed class LayerType {
        /** `0`: solid, footage, precomp or null object (an AV layer). */
        object Av : LayerType()

        /** `1` */
        object Light : LayerType()

        /** `2` */
        object Camera : LayerType()

        /** `3` */
        object Text : LayerType()

        /** `4` */
        object Shape : LayerType()

        /** Any other value. */
        class Other(val code: Long) : LayerType()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Av && other is Av -> true
            this is Light && other is Light -> true
            this is Camera && other is Camera -> true
            this is Text && other is Text -> true
            this is Shape && other is Shape -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Av -> 0
            is Light -> 1
            is Camera -> 2
            is Text -> 3
            is Shape -> 4
            is Other -> code.hashCode()
        }
    }

    /** The decoded fields of a layer's `ldta` chunk (164 bytes; the fields below need the first 140).
     *
     * Times are stored as a numerator and a denominator (the denominator is the composition's
     * timescale, 1 in a precomp layer). The in and out points are stored **relative to the start**:
     * the absolute in point is `start + in * stretch`. */
    class LayerHeader(
        /** The layer id (unique in the project); parent links use it. */
        val id: Long,
        val quality: Quality,
        /** The numerator of the time stretch (signed `i32` at byte 8). */
        val stretch: Int,
        /** The denominator of the time stretch (`u32` at byte 108). */
        val stretchDen: Long,
        /** The layer start time, as stored. */
        val start: Rational,
        /** The in point relative to the start, as stored. */
        val inRelative: Rational,
        /** The out point relative to the start, as stored. */
        val outRelative: Rational,
        /** Bytes 37, 38 and 39 of `ldta`: the switch bits (see the `is_*` methods). */
        val flagBytes: ByteArray,
        /** The id of the source item (`ldta` byte 40), null for `0` (shape, text, camera)
         * and `0xffff_ffff` (light). */
        val sourceId: Long?,
        /** The label colour index (`ldta` byte 61). */
        val label: Int,
        /** The copy of the layer name at byte 64 (the layer's `Utf8` child holds the full name). */
        val nameCopy: String,
        val blendMode: BlendMode,
        val layerType: LayerType,
        /** The id of the parent layer, null when there is none. */
        val parentId: Long?,
        /** The `u32` at byte 136 (the light type of a light layer, 0 elsewhere). */
        val lightTypeCode: Long
    ) {
        companion object {
            /** Decodes an `ldta` body; null when it is shorter than 140 bytes. */
            fun parse(ldta: ByteArray): LayerHeader? {
                fun time(at: Int): Rational? {
                    val num = beI32(ldta, at) ?: return null
                    val den = beU32(ldta, at + 4) ?: return null
                    return Rational(num.toLong(), den)
                }
                val source = beU32(ldta, 40) ?: return null
                val parent = beU32(ldta, 132) ?: return null
                val flags = bytesAt(ldta, 37, 3) ?: return null
                val nameField = bytesAt(ldta, 64, 32) ?: return null
                return LayerHeader(
                    id = beU32(ldta, 0) ?: return null,
                    quality = when (ldta.getOrNull(5)?.toInt()?.and(0xFF)) {
                        1 -> Quality.Draft
                        2 -> Quality.Best
                        null -> return null
                        else -> Quality.Other(ldta[5].toInt() and 0xFF)
                    },
                    stretch = beI32(ldta, 8) ?: return null,
                    stretchDen = beU32(ldta, 108) ?: return null,
                    start = time(12) ?: return null,
                    inRelative = time(20) ?: return null,
                    outRelative = time(28) ?: return null,
                    flagBytes = flags,
                    sourceId = if (source != 0L && source != 0xFFFFFFFFL) source else null,
                    label = ldta.getOrNull(61)?.toInt()?.and(0xFF) ?: return null,
                    nameCopy = bytesToText(untilNul(nameField)),
                    blendMode = BlendMode.fromCode(ldta.getOrNull(99)?.toInt()?.and(0xFF) ?: return null),
                    layerType = when (beU32(ldta, 128) ?: return null) {
                        0L -> LayerType.Av
                        1L -> LayerType.Light
                        2L -> LayerType.Camera
                        3L -> LayerType.Text
                        4L -> LayerType.Shape
                        else -> LayerType.Other(beU32(ldta, 128)!!)
                    },
                    parentId = if (parent != 0L) parent else null,
                    lightTypeCode = beU32(ldta, 136) ?: return null
                )
            }
        }

        private fun flag(byte: Int, mask: Int): Boolean =
            flagBytes.getOrNull(byte)?.toInt()?.and(mask) != 0

        /** Byte 37 bit `0x01`: the layer has a name set by the user. */
        fun nameIsUserSet(): Boolean = flag(0, 0x01)

        /** Byte 37 bit `0x02`: guide layer. */
        fun isGuide(): Boolean = flag(0, 0x02)

        /** Byte 38 bit `0x02`: adjustment layer. */
        fun isAdjustment(): Boolean = flag(1, 0x02)

        /** Byte 38 bit `0x04`: the 3D layer switch. */
        fun isThreeD(): Boolean = flag(1, 0x04)

        /** Byte 38 bit `0x40`: the layer orients itself towards its point of interest. */
        fun isAutoOriented(): Boolean = flag(1, 0x40)

        /** Byte 38 bit `0x08`: solo. */
        fun isSolo(): Boolean = flag(1, 0x08)

        /** Byte 38 bit `0x80`: the layer was created as a null object. */
        fun isNullObject(): Boolean = flag(1, 0x80)

        /** Byte 39 bit `0x01`: the video switch (the layer's eye icon). */
        fun isVideoEnabled(): Boolean = flag(2, 0x01)

        /** The same as [isVideoEnabled]. */
        fun isEnabled(): Boolean = isVideoEnabled()

        /** Byte 39 bit `0x02`: the audio switch. */
        fun isAudioEnabled(): Boolean = flag(2, 0x02)

        /** Byte 39 bit `0x04`: the effects switch. */
        fun isEffectsEnabled(): Boolean = flag(2, 0x04)

        /** Byte 39 bit `0x08`: the motion blur switch. */
        fun isMotionBlur(): Boolean = flag(2, 0x08)

        /** Byte 39 bit `0x20`: locked. */
        fun isLocked(): Boolean = flag(2, 0x20)

        /** Byte 39 bit `0x40`: shy. */
        fun isShy(): Boolean = flag(2, 0x40)

        /** Byte 39 bit `0x80`: collapse transformations / continuously rasterize. */
        fun collapsesTransformations(): Boolean = flag(2, 0x80)

        /** The light type of a light layer, null for any other layer type. */
        fun lightType(): LightType? = if (layerType != LayerType.Light) null else when (lightTypeCode) {
            0L -> LightType.Parallel
            1L -> LightType.Spot
            2L -> LightType.Point
            3L -> LightType.Ambient
            else -> LightType.Other(lightTypeCode)
        }

        /** The time stretch as an exact reduced ratio; null for a zero denominator. */
        fun stretchRatio(): Rational? = Rational.new(stretch.toLong(), stretchDen)

        /** The time stretch in percent; a zero denominator reads as 1. */
        fun stretchPercent(): Double {
            val den = if (stretchDen == 0L) 1.0 else stretchDen.toDouble()
            return stretch.toDouble() / den * 100.0
        }

        /** The start time in seconds, null for a zero denominator. */
        fun startSeconds(): Double? = start.toDoubleOrNull()

        /** The absolute in point, `start + in * stretch`, exact and reduced. */
        fun inPoint(): Rational? {
            val ratio = stretchRatio() ?: return null
            return start.plusScaled(inRelative, ratio)
        }

        /** The absolute out point, `start + out * stretch`, exact and reduced. */
        fun outPoint(): Rational? {
            val ratio = stretchRatio() ?: return null
            return start.plusScaled(outRelative, ratio)
        }

        /** The absolute in point in seconds. */
        fun inPointSeconds(): Double? = inPoint()?.toDoubleOrNull()

        /** The absolute out point in seconds. */
        fun outPointSeconds(): Double? = outPoint()?.toDoubleOrNull()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LayerHeader) return false
            return id == other.id && quality == other.quality && stretch == other.stretch &&
                stretchDen == other.stretchDen && start == other.start &&
                inRelative == other.inRelative && outRelative == other.outRelative &&
                flagBytes.contentEquals(other.flagBytes) && sourceId == other.sourceId &&
                label == other.label && nameCopy == other.nameCopy &&
                blendMode == other.blendMode && layerType == other.layerType &&
                parentId == other.parentId && lightTypeCode == other.lightTypeCode
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + quality.hashCode()
            result = 31 * result + stretch
            result = 31 * result + stretchDen.hashCode()
            result = 31 * result + start.hashCode()
            result = 31 * result + inRelative.hashCode()
            result = 31 * result + outRelative.hashCode()
            result = 31 * result + flagBytes.contentHashCode()
            result = 31 * result + (sourceId?.hashCode() ?: 0)
            result = 31 * result + label
            result = 31 * result + nameCopy.hashCode()
            result = 31 * result + blendMode.hashCode()
            result = 31 * result + layerType.hashCode()
            result = 31 * result + (parentId?.hashCode() ?: 0)
            result = 31 * result + lightTypeCode.hashCode()
            return result
        }
    }

    /** The renderer of a composition (`LIST PRin` / `prin`). */
    class Renderer(
        /** The match name (`ADBE Escher` for Classic 3D, `ADBE Calder` for Advanced 3D). */
        val matchName: String,
        /** The display name (`Classic 3D`, `Advanced 3D`). */
        val displayName: String
    ) {
        override fun equals(other: Any?): Boolean =
            other is Renderer && matchName == other.matchName && displayName == other.displayName

        override fun hashCode(): Int = 31 * matchName.hashCode() + displayName.hashCode()
    }

    /** The decoded fields of a composition's `cdta` chunk (204 bytes; the fields below need 176). */
    class CompHeader(
        /** Frame duration (`u32` at byte 4) and timescale (`u32` at byte 8). */
        val timeBase: AepTypedView.TimeBase,
        /** The frame rate as 16.16 fixed point (`u32` at byte 156). */
        val frameRateFixed: Long,
        /** The composition duration in time units. */
        val durationUnits: Long,
        /** The work area start in time units. */
        val workAreaStartUnits: Long,
        /** The work area end (start + duration) in time units; null when it is the whole
         * composition (stored as `0xffff_ffff`). */
        val workAreaEndUnits: Long?,
        /** The background colour as R, G, B (bytes 52 to 54), 8 bits per channel. */
        val background: ByteArray,
        /** The shutter angle in degrees. */
        val shutterAngle: Long,
        val width: Int,
        val height: Int,
        /** The pixel aspect ratio as stored: numerator and denominator. */
        val pixelAspect: Rational,
        /** The composition's motion blur switch. */
        val motionBlur: Boolean,
        /** The shutter phase in degrees (`i32` at byte 180); null when the chunk ends before byte 184. */
        val shutterPhase: Int?,
        /** The display start time (the comp start timecode) as `num / den` seconds; null when the
         * chunk ends before byte 172. */
        val displayStart: Rational?
    ) {
        companion object {
            /** Decodes a `cdta` body; null when it is shorter than 176 bytes. */
            fun parse(cdta: ByteArray): CompHeader? {
                val workEnd = beU32(cdta, 36) ?: return null
                val frameDuration = beU32(cdta, 4) ?: return null
                val timescale = beU32(cdta, 8) ?: return null
                return CompHeader(
                    timeBase = AepTypedView.TimeBase(frameDuration, timescale),
                    frameRateFixed = beU32(cdta, 156) ?: return null,
                    durationUnits = beU32(cdta, 44) ?: return null,
                    workAreaStartUnits = beU32(cdta, 28) ?: return null,
                    workAreaEndUnits = if (workEnd != 0xFFFFFFFFL) workEnd else null,
                    background = bytesAt(cdta, 52, 3) ?: return null,
                    shutterAngle = beU32(cdta, 172) ?: return null,
                    width = beU16(cdta, 140) ?: return null,
                    height = beU16(cdta, 142) ?: return null,
                    pixelAspect = Rational(
                        (beU32(cdta, 144) ?: return null),
                        (beU32(cdta, 148) ?: return null)
                    ),
                    motionBlur = (cdta.getOrNull(139)?.toInt()?.and(0x08) ?: return null) != 0,
                    shutterPhase = beI32(cdta, 180),
                    displayStart = run {
                        val num = beU32(cdta, 164) ?: return@run null
                        val den = beU32(cdta, 168) ?: return@run null
                        Rational(num, den)
                    }
                )
            }
        }

        /** Frames per second from the time base (`timescale / frame_duration`). */
        fun frameRate(): Double? = timeBase.frameRate()

        /** The frame rate from the 16.16 fixed-point field (25 is `0x0019_0000`). */
        fun frameRateFromFixed(): Double = frameRateFixed.toDouble() / 65536.0

        /** The duration in seconds. */
        fun durationSeconds(): Double? {
            val units = durationUnits.toIntOrNull() ?: return null
            return timeBase.seconds(units)
        }

        /** The work area start in seconds. */
        fun workAreaStartSeconds(): Double? {
            val units = workAreaStartUnits.toIntOrNull() ?: return null
            return timeBase.seconds(units)
        }

        /** The work area end in seconds; the composition duration when the work area is the whole composition. */
        fun workAreaEndSeconds(): Double? = when (val end = workAreaEndUnits) {
            null -> durationSeconds()
            else -> {
                val units = end.toIntOrNull() ?: return null
                timeBase.seconds(units)
            }
        }

        /** The background colour as fractions 0..1 (`byte / 255`). */
        fun backgroundRgb(): DoubleArray =
            doubleArrayOf(
                (background.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) / 255.0,
                (background.getOrNull(1)?.toInt()?.and(0xFF) ?: 0) / 255.0,
                (background.getOrNull(2)?.toInt()?.and(0xFF) ?: 0) / 255.0
            )

        /** The pixel aspect ratio as a number. */
        fun pixelAspectRatio(): Double? = pixelAspect.toDoubleOrNull()

        /** The display start time in seconds (the start timecode), null when absent or the denominator is zero. */
        fun displayStartSeconds(): Double? = displayStart?.toDoubleOrNull()

        /** The display start as a frame number (`display start * frame rate`, rounded). */
        fun displayStartFrame(): Long? {
            val rate = frameRate() ?: return null
            val seconds = displayStartSeconds() ?: return null
            return Math.round(seconds * rate)
        }

        /** The frame rate as an exact fraction `timescale / frame_duration`. */
        fun frameRateExact(): Rational? =
            Rational.new(timeBase.timescale, timeBase.frameDuration)

        /** The duration in whole frames (`duration / frame_duration`). */
        fun durationFrames(): Long? =
            if (timeBase.frameDuration != 0L) durationUnits / timeBase.frameDuration else null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompHeader) return false
            return timeBase == other.timeBase && frameRateFixed == other.frameRateFixed &&
                durationUnits == other.durationUnits &&
                workAreaStartUnits == other.workAreaStartUnits &&
                workAreaEndUnits == other.workAreaEndUnits &&
                background.contentEquals(other.background) &&
                shutterAngle == other.shutterAngle && width == other.width &&
                height == other.height && pixelAspect == other.pixelAspect &&
                motionBlur == other.motionBlur && shutterPhase == other.shutterPhase &&
                displayStart == other.displayStart
        }

        override fun hashCode(): Int {
            var result = timeBase.hashCode()
            result = 31 * result + frameRateFixed.hashCode()
            result = 31 * result + durationUnits.hashCode()
            result = 31 * result + workAreaStartUnits.hashCode()
            result = 31 * result + (workAreaEndUnits?.hashCode() ?: 0)
            result = 31 * result + background.contentHashCode()
            result = 31 * result + shutterAngle.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + pixelAspect.hashCode()
            result = 31 * result + motionBlur.hashCode()
            result = 31 * result + (shutterPhase?.hashCode() ?: 0)
            result = 31 * result + (displayStart?.hashCode() ?: 0)
            return result
        }
    }
}

/** The layer id (`ldta` byte 0). */
fun AepTypedView.Layer.id(): Long? {
    val bytes = chunk.child("ldta")?.bytes() ?: return null
    return beU32(bytes, 0)
}

/** The decoded `ldta`. */
fun AepTypedView.Layer.header(): AepHeader.LayerHeader? {
    val bytes = chunk.child("ldta")?.bytes() ?: return null
    return AepHeader.LayerHeader.parse(bytes)
}

/** The video switch (byte 39 bit `0x01`), `enabled` in the scripting API. */
fun AepTypedView.Layer.isEnabled(): Boolean? = header()?.isVideoEnabled()

/** The audio switch (byte 39 bit `0x02`), `audioEnabled`. */
fun AepTypedView.Layer.isAudioEnabled(): Boolean? = header()?.isAudioEnabled()

/** The effects switch (byte 39 bit `0x04`), `effectsActive`. */
fun AepTypedView.Layer.isEffectsEnabled(): Boolean? = header()?.isEffectsEnabled()

/** The decoded `cdta`. */
fun AepTypedView.Comp.header(): AepHeader.CompHeader? {
    val bytes = item.child("cdta")?.bytes() ?: return null
    return AepHeader.CompHeader.parse(bytes)
}

/** The composition name (the item's `Utf8` child). */
fun AepTypedView.Comp.name(): String? =
    AepTypedView.utf8Child(item).takeIf { !it.isNullOrEmpty() }

/** The item this composition data belongs to. */
fun AepTypedView.Comp.item(): AepTypedView.Item = AepTypedView.Item(item)

/** The renderer (`LIST PRin` / `prin`: match name at byte 4, display name at byte 52, 32 bytes each). */
fun AepTypedView.Comp.renderer(): AepHeader.Renderer? {
    val prin = item.childList("PRin")?.child("prin")?.bytes() ?: return null
    val matchName = cstr(prin, 4, 32) ?: return null
    val displayName = cstr(prin, 52, 32) ?: return null
    return AepHeader.Renderer(matchName, displayName)
}

private fun Long.toIntOrNull(): Int? =
    if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
