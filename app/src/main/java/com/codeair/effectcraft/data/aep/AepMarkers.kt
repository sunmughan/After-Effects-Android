package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.AeInterchangeConverter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Layer and composition markers, and Time Remapping.
 *
 * Port of `container/textdoc.rs` (`markers_in`) + `import/markers.rs` from the
 * official EffectCraft project (storytold/effectcraft, MIT OR Apache-2.0).
 * Markers: the time is a key of the `mrst` property in its own time scale
 * (layer time for layer markers, as EffectCraft keeps them), the duration
 * counts 1/600 s, the strings are comment, chapter, URL, frame target and cue
 * point name, then the cue type (event 4, navigation 5) and the cue
 * parameters; the label is a byte of the marker header.
 *
 * Time Remapping: an enabled remap is a keyed `ADBE Time Remapping` property
 * whose plain one-component keys are layer time -> source time in seconds; it
 * becomes EffectCraft's `timeRemap` property (seconds, keys in layer time) at
 * the top of the layer's properties.
 */
object AepMarkers {

    /** Marker label colours, in After Effects' label order. */
    val LABELS: List<String> = listOf(
        "None", "Red", "Yellow", "Aqua", "Pink", "Lavender", "Peach", "SeaFoam",
        "Blue", "Green", "Purple", "Orange", "Brown", "Fuchsia", "Cyan",
        "Sandstone", "DarkGreen"
    )

    /** The cue point type of a marker (`NmHd` `u32` at byte 0). */
    sealed class CueType {
        /** `4`: an event cue (also for markers without a cue point name). */
        object Event : CueType()

        /** `5`: a navigation cue. */
        object Navigation : CueType()

        /** Any other value. */
        class Other(val code: Long) : CueType()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Event && other is Event -> true
            this is Navigation && other is Navigation -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Event -> 4
            is Navigation -> 5
            is Other -> code.hashCode()
        }
    }

    /** One marker as stored in the file. */
    class Marker(
        /** The time in the property's time units (layer time). */
        val timeUnits: Int,
        /** The time in seconds, when the time scale is known. */
        val seconds: Double?,
        val comment: String,
        /** The duration in seconds (`NmHd` duration / per-second), null when absent. */
        val durationSeconds: Double?,
        val chapter: String,
        val url: String,
        val frameTarget: String,
        val cuePointName: String,
        /** The label byte (`NmHd` byte 16), null when absent. */
        val label: Int?,
        val cueType: CueType?,
        /** Cue parameter pairs, in file order. */
        val cueParams: List<Pair<String, String>>
    )

    /** Reads the markers below `holder` (a layer or the composition's `SecL` list):
     * the `mrst` list has a property whose 16-byte records give the times and one
     * `Nmrd` list per marker with the strings. */
    fun markersIn(holder: AepContainer.Chunk): List<Marker> {
        val mrst = holder.findList("mrst") ?: return emptyList()
        val prop = mrst.childList("tdbs")?.let { AepTypedView.Property.fromChunk(it) }
            ?: return emptyList()
        val keys = prop.keys() ?: return emptyList()
        val scale = prop.timeScale() ?: 0L
        val details = mrst.walk().map { it.second }.filter { it.isList("Nmrd") }.toList()
        val out = ArrayList<Marker>()
        for ((k, nmrd) in keys.keyframes().zip(details)) {
            val timeUnits = k.timeUnits() ?: continue
            // The five strings, in the order comment, chapter, URL, frame target,
            // cue point name.
            val strings = nmrd.children().filter { it.hasId("Utf8") }
                .map { it.bytes()?.let { b -> bytesToText(b) } ?: "" }
            fun next(i: Int): String = strings.getOrNull(i) ?: ""
            val extras = strings.drop(5)
            val header = nmrd.child("NmHd")?.bytes()
            val durationSeconds = header?.let { h ->
                val duration = beU32(h, 8)
                val perSecond = beU32(h, 12)
                if (duration != null && perSecond != null && perSecond != 0L) {
                    duration.toDouble() / perSecond.toDouble()
                } else null
            }
            val pairs = header?.let { beU32(it, 4) }?.toInt() ?: 0
            val cueParams = extras.chunked(2).take(pairs)
                .mapNotNull { pair -> if (pair.size == 2) pair[0] to pair[1] else null }
            val cueType = header?.let { beU32(it, 0) }?.let { t ->
                when (t) {
                    // 0: no cue point (a zero-initialised header), a plain marker, not an
                    // unknown cue type: the marker imports whole without a loss.
                    0L -> null
                    4L -> CueType.Event
                    5L -> CueType.Navigation
                    else -> CueType.Other(t)
                }
            }
            out.add(
                Marker(
                    timeUnits = timeUnits,
                    seconds = k.seconds(scale),
                    comment = next(0),
                    durationSeconds = durationSeconds,
                    chapter = next(1),
                    url = next(2),
                    frameTarget = next(3),
                    cuePointName = next(4),
                    label = header?.getOrNull(16)?.toInt()?.and(0xFF),
                    cueType = cueType,
                    cueParams = cueParams
                )
            )
        }
        return out
    }

    /** The time scale of the marker list below `holder` (the layer, or the
     * composition's `SecL`). */
    private fun markerScale(holder: AepContainer.Chunk): Long? {
        val mrst = holder.findList("mrst") ?: return null
        return mrst.childList("tdbs")?.let { AepTypedView.Property.fromChunk(it) }
            ?.timeScale()?.takeIf { it != 0L }
    }

    /** The markers of a layer (`holder` = the layer chunk) or a composition
     * (`holder` = its `SecL`), as canonical marker JSON, in time order. */
    fun importMarkers(
        list: List<Marker>,
        holder: AepContainer.Chunk,
        what: String,
        timescale: Long,
        col: AepImportReport.Collector
    ): JSONArray {
        val scale = markerScale(holder) ?: timescale
        val out = ArrayList<Pair<Long, JSONObject>>()
        for (m in list) {
            val time = (m.timeUnits.toDouble() / scale.toDouble() * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong()
            val duration = when (val d = m.durationSeconds) {
                null -> 0L
                else -> if (d.isFinite() && d in 0.0..1e7) {
                    (d * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong()
                } else {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$what: a marker duration is not valid, 0 used"
                    )
                    0L
                }
            }
            val label = m.label?.let { LABELS.getOrNull(it) } ?: "None"
            var navigation = false
            when (val cue = m.cueType) {
                is CueType.Navigation -> navigation = true
                is CueType.Event, null -> Unit
                is CueType.Other -> {
                    col.lose(
                        AepImportReport.LossCategory.Markers,
                        "$what: marker at ${"%.3f".format(m.seconds ?: 0.0)} s has the cue type ${cue.code}, read as an event cue"
                    )
                }
            }
            val obj = JSONObject()
                .put("time", time)
                .put("duration", duration)
                .put("comment", m.comment)
                .put("label", label)
                .put("chapter", m.chapter)
                .put("url", m.url)
                .put("protected", false)
            if (m.frameTarget.isNotEmpty()) obj.put("frame_target", m.frameTarget)
            if (m.cuePointName.isNotEmpty() || m.cueParams.isNotEmpty()) {
                val params = JSONArray()
                for ((k, v) in m.cueParams) params.put(JSONArray().put(k).put(v))
                obj.put(
                    "cue_point",
                    JSONObject()
                        .put("name", m.cuePointName)
                        .put("navigation", navigation)
                        .put("params", params)
                )
            }
            out.add(time to obj)
            col.imported.markers += 1
        }
        out.sortBy { it.first }
        val array = JSONArray()
        for ((_, obj) in out) array.put(obj)
        return array
    }

    /** The Time Remapping of a layer, as EffectCraft's `timeRemap` property; null
     * when the remap is off. */
    fun importTimeRemap(
        root: AepTypedView.PropGroup?,
        layerName: String,
        timescale: Long,
        allocUid: () -> Long,
        col: AepImportReport.Collector
    ): JSONObject? {
        val p = (root?.get("ADBE Time Remapping") as? AepTypedView.PropNode.Property)?.property
            ?: return null
        if (!p.isAnimated() && p.flags() != 1L && p.expression() == null) return null
        val prop = JSONObject()
            .put("node", "Prop")
            .put("uid", allocUid())
            .put("match", "timeRemap")
            .put("name", "Time Remap")
            .put("value", AepEffectKeys.scalar(0.0)!!)
            .put("keys", JSONArray())
            .put("ui", JSONObject().put("kind", "Number"))
            .put("spatial", false)
            .put("hold_only", false)
        val static = p.staticValue()?.firstOrNull()?.takeIf { it.isFinite() }
        if (static != null) prop.put("value", AepEffectKeys.scalar(static)!!)
        if (p.isAnimated()) {
            val before = col.imported.keyframes
            val keys = AepEffectKeys.importKeys(
                p, AepEffectKeys.Kind.Number(1.0), layerName, "ADBE Time Remapping",
                timescale, col
            )
            if (keys == null || col.imported.keyframes == before) {
                col.lose(
                    AepImportReport.LossCategory.TimeRemapping,
                    "$layerName: Time Remapping has no readable keyframe, not imported"
                )
                return null
            }
            prop.put("keys", keys.keys)
            prop.put("value", keys.firstValue)
        }
        val enabled = p.expressionEnabled()
        val text = p.expression()
        if (enabled != null && text != null) {
            prop.put("expr", JSONObject().put("text", text).put("enabled", enabled))
            col.imported.expressions += 1
        }
        col.imported.timeRemaps += 1
        return prop
    }

    /** One imported orientation keyframe: time in ticks, degrees [x, y, z]. */
    data class OrientedKey(
        val timeTicks: Long,
        val value: List<Double>,
        val inInterp: String,
        val outInterp: String
    )

    /** The keys of a keyed Orientation. Linear and hold sides arrive as they are;
     * the ease of a bezier side is not sampled, so it becomes linear and is
     * reported. EffectCraft interpolates the three angles one by one, After
     * Effects by a slerp of the rotations (a renderer difference). */
    fun importOrientationKeys(
        layer: AepTypedView.Layer,
        layerName: String,
        timescale: Long,
        col: AepImportReport.Collector
    ): List<OrientedKey>? {
        val stored = layer.orientationKeys()
        if (stored == null || stored.isEmpty()) {
            col.lose(
                AepImportReport.LossCategory.TransformProperties,
                "$layerName: ADBE Orientation keys unreadable, the static value is used"
            )
            return null
        }
        // Key times count in the property's time scale (a stretched layer's is
        // scaled), as in `import_keys`.
        val scale = layer.orientationTimeScale()?.takeIf { it != 0L } ?: timescale
        val what = "$layerName: ADBE Orientation"
        var approximated = false
        val keys = ArrayList<Pair<Long, OrientedKey>>()
        for (k in stored) {
            if (!k.degrees.all { it.isFinite() && kotlin.math.abs(it) <= AepEffectKeys.LIMIT }) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: keyframe value is not a number"
                )
                continue
            }
            fun side(interp: AepKeys.Interpolation?): String {
                val s = AepEffectKeys.interp(interp, col, what)
                if (s == "Bezier") {
                    approximated = true
                    return "Linear"
                }
                return s
            }
            val time = (k.timeUnits.toDouble() / scale.toDouble() * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong()
            keys.add(
                time to OrientedKey(
                    timeTicks = time,
                    value = k.degrees,
                    inInterp = side(k.interpolationIn),
                    outInterp = side(k.interpolationOut)
                )
            )
        }
        if (approximated) {
            col.lose(
                AepImportReport.LossCategory.ApproximatedKeyframes,
                "$what: bezier key read as linear (the Orientation ease is not sampled)"
            )
        }
        keys.sortBy { it.first }
        val deduped = ArrayList<OrientedKey>(keys.size)
        var lastTime: Long? = null
        for ((time, key) in keys) {
            if (time == lastTime) continue
            deduped.add(key)
            lastTime = time
        }
        col.imported.keyframes += deduped.size
        return deduped
    }
}
