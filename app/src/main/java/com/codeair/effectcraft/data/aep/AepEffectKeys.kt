package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.AeInterchangeConverter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Keyframes and static values of effect parameters, as canonical JSON values.
 *
 * Port of the effect side of `import/props.rs` (`Kind` + `import_keys`) from the
 * official EffectCraft project (storytold/effectcraft, MIT OR Apache-2.0).
 * Stored values arrive in the file's units (ARGB 0..255 for colours, fractions of
 * the layer size for points, 1-based indices for popups); the canonical values
 * are straight RGBA 0..1 (alpha 1), layer pixels, and 0-based indices.
 */
object AepEffectKeys {

    /** No sensible coordinate, angle or percentage comes near this; larger numbers (and
     * NaN or infinity) mean damaged data. */
    const val LIMIT = 1e9

    /** The unit conversion of one effect parameter, with the layer size it is read against. */
    sealed class Kind {
        /** An effect slider or angle: the stored number times this factor. */
        class Number(val factor: Double) : Kind()

        /** An effect point on a layer this big (pixels): stored as fractions of the layer size. */
        class Point(val w: Double, val h: Double) : Kind()

        /** A 2-component vector in stored units (mask feather: pixels as stored). */
        object Vec2 : Kind()

        /** An effect colour: stored as ARGB 0..255, imported as straight RGBA 0..1 with alpha 1. */
        object Color : Kind()

        /** An effect popup with this many items: stored 1-based, imported 0-based. */
        class Popup(val items: Int) : Kind()

        /** An effect checkbox: stored 1.0 when on. */
        object Bool : Kind()

        /** Stored value to the unit shown, also applied to ease speeds. */
        fun factor(): Double = when (this) {
            is Number -> factor
            // A fractional point's spatial speed is pixels per second divided by the layer height.
            is Point -> h
            else -> 1.0
        }

        /** Whether a bezier ease of this kind can be imported. The ease of effect colours is
         * only sampled as speed 0 / influence 0; popups and checkboxes have no ease. */
        fun easeDecoded(): Boolean = this !is Color && this !is Popup && this !is Bool

        /** The stored components as the project stores them; null when a needed component is
         * missing or not a finite number. */
        fun value(c: List<Double>): JSONObject? {
            fun g(i: Int): Double? = c.getOrNull(i)?.takeIf { it.isFinite() }
            val json = when (this) {
                is Number -> scalar(g(0)?.times(factor))
                is Point -> vec2(g(0)?.times(w), g(1)?.times(h))
                is Vec2 -> vec2(g(0), g(1))
                is Color -> color(g(1)?.div(255.0), g(2)?.div(255.0), g(3)?.div(255.0))
                is Popup -> {
                    val i = g(0)?.roundToLong() ?: return null
                    if (i < 1 || i > items) return null
                    enum(i - 1)
                }
                is Bool -> bool(g(0)?.let { it != 0.0 })
            } ?: return null
            return if (sane(json)) json else null
        }

        /** A tangent offset, in the same units as the value. */
        fun tangent(c: List<Double>): List<Double> {
            fun g(i: Int): Double = c.getOrNull(i)?.takeIf { it.isFinite() } ?: 0.0
            val t = when (this) {
                is Point -> listOf(g(0) * w, g(1) * h, 0.0)
                else -> listOf(g(0), g(1), g(2))
            }
            return if (t.all { abs(it) <= LIMIT }) t else listOf(0.0, 0.0, 0.0)
        }
    }

    /** A decoded value in EffectCraft's units, null when it does not fit the kind or is not a
     * valid number. Mirrors `convert` in the official importer: colours go through the stored
     * 0..255 form, so the reader's ARGB fractions come back as straight RGBA 0..1. */
    fun convertValue(value: AepEffects.ParamValue, kind: Kind): JSONObject? {
        val raw = when (value) {
            is AepEffects.ParamValue.Argb -> if (kind is Kind.Color) {
                listOf(value.a * 255.0, value.r * 255.0, value.g * 255.0, value.b * 255.0)
            } else null
            is AepEffects.ParamValue.Point -> if (kind is Kind.Point) listOf(value.x, value.y) else null
            is AepEffects.ParamValue.Number -> if (kind is Kind.Number) listOf(value.value) else null
            is AepEffects.ParamValue.Index -> if (kind is Kind.Popup) listOf(value.value.toDouble()) else null
            is AepEffects.ParamValue.Bool -> if (kind is Kind.Bool) listOf(if (value.value) 1.0 else 0.0) else null
            is AepEffects.ParamValue.Layer -> null
        } ?: return null
        return kind.value(raw)
    }

    /** The imported keyframes of `node`, as canonical JSON, with the value the property
     * takes at its first key. Null when the property has no key list or no readable key. */
    data class ImportedKeys(val keys: JSONArray, val firstValue: JSONObject)

    fun importKeys(
        node: AepTypedView.Property,
        kind: Kind,
        layerName: String,
        name: String,
        timescale: Long,
        col: AepImportReport.Collector
    ): ImportedKeys? {
        val what = "$layerName: $name"
        val list = node.keys() ?: return null
        val scale = node.timeScale()?.takeIf { it != 0L } ?: timescale
        if (scale == 0L) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: the time scale is zero, the keyframes are skipped"
            )
            return null
        }
        val keys = ArrayList<Pair<Long, JSONObject>>()
        for (rec in list.keyframes()) {
            val units = rec.timeUnits()
            val raw = rec.value()
            if (units == null || raw == null) {
                col.lose(AepImportReport.LossCategory.DamagedData, "$what: unreadable keyframe")
                continue
            }
            val value = kind.value(raw)
            if (value == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: keyframe value is missing or not a number"
                )
                continue
            }
            var inInterp = interp(rec.interpolationIn(), col, what)
            var outInterp = interp(rec.interpolationOut(), col, what)
            val spatial = rec.spatial()
            val spatialIn = spatial?.let { kind.tangent(it.inTangent) }
            val spatialOut = spatial?.let { kind.tangent(it.outTangent) }
            val spatialAuto = spatial?.let { it.autoBezierFlag != 0L }
            val roving = rec.isRoving() ?: false
            val label = rec.label()?.takeIf { it <= 16 } ?: 0
            var inEase = emptyList<JSONObject>()
            var outEase = emptyList<JSONObject>()
            // Colour keys switched to bezier store speed 0 and influence 0 on both sides,
            // which After Effects interpolates linearly.
            if (kind is Kind.Color && (inInterp == "Bezier" || outInterp == "Bezier")) {
                val e = rec.ease()
                if (e != null && listOf(e.inSpeed, e.inInfluence, e.outSpeed, e.outInfluence) == listOf(0.0, 0.0, 0.0, 0.0)) {
                    if (inInterp == "Bezier") inInterp = "Linear"
                    if (outInterp == "Bezier") outInterp = "Linear"
                }
            }
            if (inInterp == "Bezier" || outInterp == "Bezier") {
                val undecoded = !kind.easeDecoded()
                val eases = if (undecoded) null else keyEases(rec, kind.factor())
                if (eases == null) {
                    val why = if (undecoded) "the ease of this effect parameter is not decoded" else "ease data not decodable"
                    col.lose(
                        AepImportReport.LossCategory.ApproximatedKeyframes,
                        "$what: bezier key read as linear ($why)"
                    )
                    if (inInterp == "Bezier") inInterp = "Linear"
                    if (outInterp == "Bezier") outInterp = "Linear"
                } else {
                    if (inInterp == "Bezier") inEase = eases.first
                    if (outInterp == "Bezier") outEase = eases.second
                }
            }
            val obj = JSONObject()
                .put("time", (units.toDouble() / scale.toDouble() * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong())
                .put("value", value)
                .put("in_interp", inInterp)
                .put("out_interp", outInterp)
            if (inEase.isNotEmpty()) obj.put("in_ease", JSONArray(inEase))
            if (outEase.isNotEmpty()) obj.put("out_ease", JSONArray(outEase))
            if (spatialIn != null) obj.put("spatial_in", numberArray(spatialIn))
            if (spatialOut != null) obj.put("spatial_out", numberArray(spatialOut))
            if (spatialAuto == false) obj.put("spatial_auto", false)
            if (roving) obj.put("roving", true)
            if (label != 0) obj.put("label", label)
            keys.add(obj.getLong("time") to obj)
        }
        if (keys.isEmpty()) return null
        keys.sortBy { it.first }
        val deduped = ArrayList<JSONObject>(keys.size)
        var lastTime: Long? = null
        var hadDupes = false
        for ((time, obj) in keys) {
            if (time == lastTime) {
                hadDupes = true
                continue
            }
            deduped.add(obj)
            lastTime = time
        }
        if (hadDupes) {
            col.lose(AepImportReport.LossCategory.DamagedData, "$what: keyframes at the same time")
        }
        // The first key's incoming and the last key's outgoing ease mean nothing.
        if (deduped.isNotEmpty()) {
            deduped.first().remove("in_ease")
            deduped.last().remove("out_ease")
        }
        col.imported.keyframes += deduped.size
        val firstValue = deduped.first().getJSONObject("value")
        return ImportedKeys(JSONArray(deduped), firstValue)
    }

    /** The interpolation of one side of a key; unknown codes read as linear. */
    fun interp(interp: AepKeys.Interpolation?, col: AepImportReport.Collector, what: String): String =
        when (interp) {
            null, AepKeys.Interpolation.Linear -> "Linear"
            AepKeys.Interpolation.Bezier -> "Bezier"
            AepKeys.Interpolation.Hold -> "Hold"
            is AepKeys.Interpolation.Other -> {
                col.lose(
                    AepImportReport.LossCategory.ApproximatedKeyframes,
                    "$what: unknown interpolation ${interp.code} read as linear"
                )
                "Linear"
            }
        }

    /** The temporal ease of both sides of a key: one entry per dimension, or null where the
     * data cannot be used. */
    private fun keyEases(rec: AepTypedView.Keyframe, factor: Double): Pair<List<JSONObject>, List<JSONObject>>? {
        fun ease(speed: Double, influence: Double): JSONObject? {
            val scaled = speed * factor
            if (!scaled.isFinite() || abs(scaled) > LIMIT * 100.0 || !influence.isFinite()) return null
            return JSONObject().put("speed", scaled).put("influence", influence.coerceIn(0.001, 1.0))
        }
        return when (val kind = rec.kind()) {
            is AepKeys.KeyframeKind.Spatial, is AepKeys.KeyframeKind.Plain -> {
                if (kind is AepKeys.KeyframeKind.Plain && kind.components != 1) {
                    val all = rec.easeComponents() ?: return null
                    if (all.size != kind.components) return null
                    val inEases = ArrayList<JSONObject>(all.size)
                    val outEases = ArrayList<JSONObject>(all.size)
                    for (e in all) {
                        inEases.add(ease(e.inSpeed, e.inInfluence) ?: return null)
                        outEases.add(ease(e.outSpeed, e.outInfluence) ?: return null)
                    }
                    inEases to outEases
                } else {
                    val e = rec.ease() ?: return null
                    listOf(ease(e.inSpeed, e.inInfluence) ?: return null) to
                        listOf(ease(e.outSpeed, e.outInfluence) ?: return null)
                }
            }
            else -> null
        }
    }

    /** Whether every component of a canonical value is finite and within the sane range. */
    fun sane(value: JSONObject): Boolean {
        val v = value.opt("v") ?: return false
        val components: List<Double> = when (v) {
            is Number -> listOf(v.toDouble())
            is JSONArray -> (0 until v.length()).map { v.optDouble(it) }
            is Boolean -> return true
            else -> return false
        }
        return components.all { it.isFinite() && abs(it) <= LIMIT }
    }

    fun scalar(v: Double?): JSONObject? =
        if (v == null) null else JSONObject().put("t", "Scalar").put("v", v)

    fun vec2(x: Double?, y: Double?): JSONObject? =
        if (x == null || y == null) null
        else JSONObject().put("t", "Vec2").put("v", JSONArray().put(x).put(y))

    fun color(r: Double?, g: Double?, b: Double?): JSONObject? =
        if (r == null || g == null || b == null) null
        else JSONObject().put("t", "Color").put("v", JSONArray().put(r).put(g).put(b).put(1.0))

    fun enum(i: Long): JSONObject = JSONObject().put("t", "Enum").put("v", i)

    fun bool(b: Boolean?): JSONObject? =
        if (b == null) null else JSONObject().put("t", "Bool").put("v", b)

    fun numberArray(values: List<Double>): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value)
        return array
    }
}
