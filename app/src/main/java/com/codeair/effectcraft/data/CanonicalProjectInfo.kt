package com.codeair.effectcraft.data

import org.json.JSONObject

/**
 * One curve-graph key as exposed by native metadata: frame, first-component
 * value (vector properties plot their first component), per-side
 * interpolation labels ("Linear", "Bezier" or "Hold") and the first ease
 * entry per side (speed in value units per second, influence 0..1).
 */
data class CanonicalCurveKey(
    val frame: Int,
    val value: Double,
    val inInterp: String = "Linear",
    val outInterp: String = "Linear",
    val inSpeed: Double = 0.0,
    val inInfluence: Double = 0.0,
    val outSpeed: Double = 0.0,
    val outInfluence: Double = 0.0,
    /** True when this key roves across time (constant speed along the motion path). */
    val roving: Boolean = false,
    /** Stored spatial (motion-path) tangents, relative to the key value; ignored when [spatialAuto] is true. */
    val spatialIn: List<Double> = listOf(0.0, 0.0, 0.0),
    val spatialOut: List<Double> = listOf(0.0, 0.0, 0.0),
    /** True when the renderer derives tangents from neighbours (Catmull-Rom auto-Bezier). */
    val spatialAuto: Boolean = true,
    /** Full value components (2 for Vec2, 3 for Vec3, 1 for scalars); [value] is components[0]. */
    val components: List<Double> = listOf(value)
)

/** One editable scalar parameter of a shape item or effect, addressed by its stable native uid. */
data class CanonicalScalarParam(
    val uid: Long,
    val name: String,
    val value: Double,
    val keys: List<CanonicalCurveKey> = emptyList()
)

/** One editable vector parameter; components has 2 entries for a Vec2 and 3 for a Vec3. */
data class CanonicalVecParam(
    val uid: Long,
    val name: String,
    val components: List<Double>,
    val keys: List<CanonicalCurveKey> = emptyList()
)

/** One editable straight-RGBA color parameter (components in 0..1) addressed by native uid. */
data class CanonicalColorParam(
    val uid: Long,
    val name: String,
    val components: List<Double>,
    val keys: List<CanonicalCurveKey> = emptyList(),
    val keyframes: List<Int> = emptyList()
)

/** One editable checkbox (Bool) parameter addressed by native uid; its keys are hold keys. */
data class CanonicalBoolParam(
    val uid: Long,
    val name: String,
    val value: Boolean,
    val keys: List<CanonicalCurveKey> = emptyList(),
    val keyframes: List<Int> = emptyList()
)

/** One editable popup (Enum) parameter addressed by native uid; [value] is the 0-based selected option index. */
data class CanonicalPopupParam(
    val uid: Long,
    val name: String,
    val value: Int,
    val options: List<String>,
    val keys: List<CanonicalCurveKey> = emptyList(),
    val keyframes: List<Int> = emptyList()
)

/**
 * Value a hold-only parameter (bool/popup) shows at [frame]: the last key
 * at or before the frame, else the static value. Hold keys never tween,
 * so the value between keys is exactly the earlier key's value.
 */
fun holdValueAt(keys: List<CanonicalCurveKey>, frame: Int, staticValue: Double): Double {
    return keys.filter { it.frame <= frame }.maxByOrNull { it.frame }?.value ?: staticValue
}

/** Text layer source parameters; null on layers that carry no Source Text property. */
data class CanonicalTextParams(
    val text: String,
    val font: String,
    val size: Double,
    val tracking: Double
)

/**
 * One shape item (for example a Rectangle path) or one effect group, with the
 * scalar, vector, straight-RGBA color, checkbox-bool and popup-enum
 * parameters the native model exposes for editing. Path, gradient and
 * layer-reference parameters remain non-editable.
 */
data class CanonicalParamGroup(
    val name: String,
    val kind: String,
    val scalars: List<CanonicalScalarParam> = emptyList(),
    val vecs: List<CanonicalVecParam> = emptyList(),
    val colors: List<CanonicalColorParam> = emptyList(),
    val bools: List<CanonicalBoolParam> = emptyList(),
    val popups: List<CanonicalPopupParam> = emptyList()
)

data class CanonicalLayerInfo(
    val id: Long,
    val name: String,
    val source: String,
    val visible: Boolean,
    val opacityPercent: Double,
    val positionX: Double,
    val positionY: Double,
    val scaleX: Double,
    val scaleY: Double,
    val rotationDegrees: Double,
    /** Canonical opacity keys, sorted by frame; empty when the layer is not keyframed. */
    val opacityKeys: List<PropertyKeyframe> = emptyList(),
    /** Full opacity curve keys (frame, value, per-side interpolation and ease). */
    val opacityCurves: List<CanonicalCurveKey> = emptyList(),
    /** Native 2D/3D Transform keyframe frame indices, sorted and unique. */
    val transformKeyframes: Map<String, List<Int>> = emptyMap(),
    /** Full Transform curve keys per property, including opacity. */
    val transformCurves: Map<String, List<CanonicalCurveKey>> = emptyMap(),
    val anchorX: Double = 0.0,
    val anchorY: Double = 0.0,
    val orientationX: Double = 0.0,
    val orientationY: Double = 0.0,
    val orientationZ: Double = 0.0,
    val rotationXDegrees: Double = 0.0,
    val rotationYDegrees: Double = 0.0,
    /** True when the canonical layer switch is locked; locked layers reject every edit. */
    val locked: Boolean = false,
    val threeD: Boolean = false,
    val textParams: CanonicalTextParams? = null,
    val shapeItems: List<CanonicalParamGroup> = emptyList(),
    val effects: List<CanonicalParamGroup> = emptyList()
)

data class CanonicalCompositionInfo(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val frameRateNum: Int,
    val frameRateDen: Int,
    val durationSeconds: Double,
    val layers: List<CanonicalLayerInfo>
) {
    val durationFrames: Int get() = (durationSeconds * frameRateNum / frameRateDen.coerceAtLeast(1)).toInt().coerceAtLeast(1)
}

data class CanonicalProjectItemInfo(
    val id: Long,
    val name: String,
    val type: String,
    val parentId: Long?,
    val path: String?,
    val width: Int,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val missing: Boolean,
    val sequence: List<String>
)

data class CanonicalProjectInfo(
    val compositions: List<CanonicalCompositionInfo>,
    val items: List<CanonicalProjectItemInfo> = emptyList()
) {
    companion object {
        fun parse(metadataJson: String): CanonicalProjectInfo {
            val root = JSONObject(metadataJson)
            require(root.optBoolean("valid")) { root.optString("error", "Invalid canonical project") }
            val comps = root.optJSONArray("compositions")
            val compositions = (0 until (comps?.length() ?: 0)).map { index ->
                val comp = comps!!.getJSONObject(index)
                val layerArray = comp.optJSONArray("layers")
                val layers = (0 until (layerArray?.length() ?: 0)).map { layerIndex ->
                    val layer = layerArray!!.getJSONObject(layerIndex)
                    val position = layer.optJSONArray("position")
                    val scale = layer.optJSONArray("scale")
                    // Opacity keys ride the same sorted-upsert semantics as the
                    // draft timeline editor, so a reordered or duplicated
                    // metadata entry can never surface as an unsorted track.
                    val opacityKeysJson = layer.optJSONArray("opacityKeys")
                    var opacityTrack = PropertyTrack("opacity", emptyList())
                    for (keyIndex in 0 until (opacityKeysJson?.length() ?: 0)) {
                        val key = opacityKeysJson!!.getJSONObject(keyIndex)
                        opacityTrack = PropertyTrackEditor.upsert(
                            opacityTrack,
                            key.getInt("frame"),
                            key.getDouble("value").toFloat()
                        )
                    }
                    val transformKeysJson = layer.optJSONObject("transformKeys")
                    val transformKeyframes = buildMap {
                        for (property in listOf("position", "scale", "rotation", "anchor", "orientation", "rotationX", "rotationY")) {
                            val framesJson = transformKeysJson?.optJSONArray(property) ?: continue
                            val frames = (0 until framesJson.length())
                                .map { framesJson.getInt(it) }
                                .filter { it >= 0 }
                                .distinct()
                                .sorted()
                            if (frames.isNotEmpty()) put(property, frames)
                        }
                    }
                    val transformCurvesJson = layer.optJSONObject("transformCurves")
                    val transformCurves = buildMap {
                        for (property in listOf("position", "scale", "rotation", "opacity", "anchor", "orientation", "rotationX", "rotationY")) {
                            val curveJson = transformCurvesJson?.optJSONArray(property) ?: continue
                            val keys = parseCurveKeys(curveJson)
                            if (keys.isNotEmpty()) put(property, keys)
                        }
                    }
                    val opacityCurves = parseCurveKeys(layer.optJSONArray("opacityCurves"))
                    val anchor = layer.optJSONArray("anchor")
                    val orientation = layer.optJSONArray("orientation")
                    val textJson = layer.optJSONObject("text")
                    CanonicalLayerInfo(
                        id = layer.getLong("id"),
                        name = layer.optString("name", "Layer"),
                        source = layer.optString("source", "Unknown"),
                        visible = layer.optBoolean("visible", true),
                        opacityPercent = layer.optDouble("opacityPercent", 100.0),
                        positionX = position?.optDouble(0) ?: 0.0,
                        positionY = position?.optDouble(1) ?: 0.0,
                        scaleX = scale?.optDouble(0) ?: 100.0,
                        scaleY = scale?.optDouble(1) ?: 100.0,
                        rotationDegrees = layer.optDouble("rotationDegrees", 0.0),
                        opacityKeys = opacityTrack.keyframes,
                        opacityCurves = opacityCurves,
                        transformKeyframes = transformKeyframes,
                        transformCurves = transformCurves,
                        anchorX = anchor?.optDouble(0) ?: 0.0,
                        anchorY = anchor?.optDouble(1) ?: 0.0,
                        orientationX = orientation?.optDouble(0) ?: 0.0,
                        orientationY = orientation?.optDouble(1) ?: 0.0,
                        orientationZ = orientation?.optDouble(2) ?: 0.0,
                        rotationXDegrees = layer.optDouble("rotationXDegrees", 0.0),
                        rotationYDegrees = layer.optDouble("rotationYDegrees", 0.0),
                        locked = layer.optBoolean("locked", false),
                        threeD = layer.optBoolean("threeD", false),
                        textParams = textJson?.let {
                            CanonicalTextParams(
                                text = it.optString("text", ""),
                                font = it.optString("font", ""),
                                size = it.optDouble("size", 0.0),
                                tracking = it.optDouble("tracking", 0.0)
                            )
                        },
                        shapeItems = parseParamGroups(layer.optJSONArray("shapeItems")),
                        effects = parseParamGroups(layer.optJSONArray("effects"))
                    )
                }
                CanonicalCompositionInfo(
                    id = comp.getLong("id"),
                    name = comp.optString("name", "Composition"),
                    width = comp.optInt("width"),
                    height = comp.optInt("height"),
                    frameRateNum = comp.optInt("frameRateNum", 30),
                    frameRateDen = comp.optInt("frameRateDen", 1),
                    durationSeconds = comp.optDouble("durationSeconds", 5.0),
                    layers = layers
                )
            }
            val itemArray = root.optJSONArray("items")
            val items = (0 until (itemArray?.length() ?: 0)).map { index ->
                val item = itemArray!!.getJSONObject(index)
                val media = item.optJSONObject("media")
                val sequenceArray = media?.optJSONArray("sequence")
                val sequence = (0 until (sequenceArray?.length() ?: 0)).map { sequenceArray!!.getString(it) }
                CanonicalProjectItemInfo(
                    id = item.getLong("id"),
                    name = item.optString("name", "Item"),
                    type = item.optString("type", "Unknown"),
                    parentId = if (item.isNull("parentId")) null else item.optLong("parentId"),
                    path = media?.optString("path")?.takeIf { it.isNotBlank() },
                    width = media?.optInt("width") ?: 0,
                    height = media?.optInt("height") ?: 0,
                    hasVideo = media?.optBoolean("hasVideo") ?: false,
                    hasAudio = media?.optBoolean("hasAudio") ?: false,
                    missing = media?.optBoolean("missing") ?: false,
                    sequence = sequence
                )
            }
            return CanonicalProjectInfo(compositions, items)
        }

        private fun parseCurveKeys(array: org.json.JSONArray?): List<CanonicalCurveKey> {
            return (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val key = array!!.optJSONObject(index) ?: return@mapNotNull null
                val frame = key.optInt("frame", -1)
                if (frame < 0) return@mapNotNull null
                val rawValue = key.opt("value")
                val value = when (rawValue) {
                    is Number -> rawValue.toDouble()
                    is Boolean -> if (rawValue) 1.0 else 0.0
                    is org.json.JSONArray -> if (rawValue.length() > 0) rawValue.optDouble(0, 0.0) else 0.0
                    else -> 0.0
                }
                // The full vector rides along for the motion-path overlay;
                // the graph still plots the first component. A bool key
                // rides as a single 1.0/0.0 component.
                val components = when (rawValue) {
                    is Number -> listOf(rawValue.toDouble())
                    is Boolean -> listOf(if (rawValue) 1.0 else 0.0)
                    is org.json.JSONArray -> (0 until rawValue.length()).map { rawValue.optDouble(it, 0.0) }
                    else -> listOf(0.0)
                }
                fun spatialTangents(name: String): List<Double> {
                    val array = key.optJSONArray(name) ?: return listOf(0.0, 0.0, 0.0)
                    return (0 until 3).map { array.optDouble(it, 0.0) }
                }
                fun ease(side: String): Pair<Double, Double> {
                    val easeArray = key.optJSONArray(side)
                    val entry = if (easeArray != null && easeArray.length() > 0) easeArray.optJSONObject(0) else null
                    return Pair(entry?.optDouble("speed", 0.0) ?: 0.0, entry?.optDouble("influence", 0.0) ?: 0.0)
                }
                val inEase = ease("inEase")
                val outEase = ease("outEase")
                CanonicalCurveKey(
                    frame = frame,
                    value = value,
                    inInterp = key.optString("inInterp", "Linear"),
                    outInterp = key.optString("outInterp", "Linear"),
                    inSpeed = inEase.first,
                    inInfluence = inEase.second,
                    outSpeed = outEase.first,
                    outInfluence = outEase.second,
                    roving = key.optBoolean("roving", false),
                    spatialIn = spatialTangents("spatialIn"),
                    spatialOut = spatialTangents("spatialOut"),
                    spatialAuto = key.optBoolean("spatialAuto", true),
                    components = components
                )
            }.sortedBy { it.frame }
        }

        private fun parseFrameIndices(array: org.json.JSONArray?): List<Int> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                array!!.optInt(index, -1).takeIf { it >= 0 }
            }.distinct().sorted()

        private fun parseParamGroups(array: org.json.JSONArray?): List<CanonicalParamGroup> {
            val groups = mutableListOf<CanonicalParamGroup>()
            for (index in 0 until (array?.length() ?: 0)) {
                val group = array!!.getJSONObject(index)
                val scalarArray = group.optJSONArray("scalars")
                val scalars = (0 until (scalarArray?.length() ?: 0)).map { scalarIndex ->
                    val param = scalarArray!!.getJSONObject(scalarIndex)
                    CanonicalScalarParam(
                        uid = param.getLong("uid"),
                        name = param.optString("name", "Parameter"),
                        value = param.optDouble("value", 0.0),
                        keys = parseCurveKeys(param.optJSONArray("keys"))
                    )
                }
                val vecArray = group.optJSONArray("vecs")
                val vecs = (0 until (vecArray?.length() ?: 0)).map { vecIndex ->
                    val param = vecArray!!.getJSONObject(vecIndex)
                    val componentsArray = param.optJSONArray("components")
                    val components = (0 until (componentsArray?.length() ?: 0)).map { componentsArray!!.getDouble(it) }
                    CanonicalVecParam(
                        uid = param.getLong("uid"),
                        name = param.optString("name", "Parameter"),
                        components = components,
                        keys = parseCurveKeys(param.optJSONArray("keys"))
                    )
                }
                val colorArray = group.optJSONArray("colors")
                val colors = (0 until (colorArray?.length() ?: 0)).map { colorIndex ->
                    val param = colorArray!!.getJSONObject(colorIndex)
                    val componentsArray = param.optJSONArray("components")
                    val components = (0 until (componentsArray?.length() ?: 0)).map { componentsArray!!.getDouble(it) }
                    CanonicalColorParam(
                        uid = param.getLong("uid"),
                        name = param.optString("name", "Color"),
                        components = components,
                        keys = parseCurveKeys(param.optJSONArray("keys")),
                        keyframes = parseFrameIndices(param.optJSONArray("keyframes"))
                    )
                }
                val boolArray = group.optJSONArray("bools")
                val bools = (0 until (boolArray?.length() ?: 0)).map { boolIndex ->
                    val param = boolArray!!.getJSONObject(boolIndex)
                    CanonicalBoolParam(
                        uid = param.getLong("uid"),
                        name = param.optString("name", "Checkbox"),
                        value = param.optBoolean("value", false),
                        keys = parseCurveKeys(param.optJSONArray("keys")),
                        keyframes = parseFrameIndices(param.optJSONArray("keyframes"))
                    )
                }
                val popupArray = group.optJSONArray("popups")
                val popups = (0 until (popupArray?.length() ?: 0)).map { popupIndex ->
                    val param = popupArray!!.getJSONObject(popupIndex)
                    val optionsArray = param.optJSONArray("options")
                    val options = (0 until (optionsArray?.length() ?: 0)).map { optionsArray!!.getString(it) }
                    CanonicalPopupParam(
                        uid = param.getLong("uid"),
                        name = param.optString("name", "Popup"),
                        value = param.optInt("value", 0),
                        options = options,
                        keys = parseCurveKeys(param.optJSONArray("keys")),
                        keyframes = parseFrameIndices(param.optJSONArray("keyframes"))
                    )
                }
                groups.add(
                    CanonicalParamGroup(
                        name = group.optString("name", "Parameter group"),
                        kind = group.optString("kind", ""),
                        scalars = scalars,
                        vecs = vecs,
                        colors = colors,
                        bools = bools,
                        popups = popups
                    )
                )
            }
            return groups
        }
    }
}
