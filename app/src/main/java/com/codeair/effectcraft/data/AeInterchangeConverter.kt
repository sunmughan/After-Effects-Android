package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Converts an [AeProject] (After Effects interchange, version 1) into a
 * canonical EffectCraft .ecproj document (schema 2).
 *
 * Every JSON shape here mirrors the upstream canonical model exactly
 * (crates/project: Layer, PropGroup, Property, build::transform,
 * build::text, build::shape_group, build::material_options, and the
 * fixture in NativeSessionInstrumentedTest.FIXTURE_JSON). The native
 * inspector is the final judge: the instrumented test feeds this
 * output to NativeProjectBridge.inspectEcproj.
 *
 * Time is ticks at 254,016,000,000 per second (effectcraft_time).
 * Media is never copied here: [mediaResolver] maps an interchange
 * relative path ("media/Clip.mp4") to an absolute device path, or
 * returns null when the file is absent — the footage is then kept
 * with its relative path and marked missing instead of failing the
 * whole import.
 */
object AeInterchangeConverter {
    const val TICKS_PER_SECOND = 254_016_000_000L

    fun toCanonicalJson(
        project: AeProject,
        mediaResolver: (String) -> String? = { null }
    ): String {
        val ids = Ids()
        val items = JSONObject()
        for (comp in project.comps) {
            convertComp(comp, ids, items, mediaResolver)
        }
        return JSONObject()
            .put("savedBy", "effectcraft-ae-import-1")
            .put("schema", 2)
            .put("settings", projectSettings())
            .put("items", items)
            .put("next_id", ids.next)
            .toString()
    }

    private class Ids {
        var next = 1L
        fun alloc(): Long = next++
    }

    private fun ticks(seconds: Double): Long =
        (seconds * TICKS_PER_SECOND).roundToLong()

    /** Frames per second as an exact rational; NTSC rates keep their 1001 denominator. */
    private fun frameRate(fps: Double): Pair<Long, Long> {
        val known = mapOf(23.976 to (24000L to 1001L), 29.97 to (30000L to 1001L), 59.94 to (60000L to 1001L))
        known.entries.firstOrNull { abs(it.key - fps) < 0.005 }?.let { return it.value }
        val rounded = fps.roundToLong()
        if (abs(fps - rounded.toDouble()) < 0.001) return rounded to 1L
        var bestNum = rounded
        var bestDen = 1L
        var bestErr = abs(fps - rounded.toDouble())
        for (den in 2..1001) {
            val num = (fps * den).roundToLong()
            val err = abs(fps - num.toDouble() / den)
            if (err < bestErr) {
                bestErr = err
                bestNum = num
                bestDen = den.toLong()
            }
        }
        return bestNum to bestDen
    }

    // ------------------------------------------------------------------ project

    /** The settings block of the canonical fixture, verbatim. */
    private fun projectSettings(): JSONObject = JSONObject()
        .put("bit_depth", "Bpc8")
        .put("linearize", false)
        .put("working_space", JSONObject.NULL)
        .put("blend_linear", false)
        .put("time_display", "Timecode")
        .put("frame_start", 0)
        .put("audio_sample_rate", 48000)
        .put("gpu_acceleration", true)
        .put("color_engine", "Adobe")
        .put("hdr", "Clip")
        .put("output_space", JSONObject.NULL)
        .put("working_gamma", 2.2)

    private fun globalLight(): JSONObject {
        fun angleProp(match: String, name: String, value: Double): JSONObject = JSONObject()
            .put("uid", 0)
            .put("match", match)
            .put("name", name)
            .put("value", JSONObject().put("t", "Scalar").put("v", value))
            .put("ui", JSONObject().put("kind", "Angle"))
            .put("spatial", false)
            .put("hold_only", false)
            .put("three_d_only", false)
            .put("two_d_only", false)
            .put("shown_dims", 0)
            .put("static_only", false)
        return JSONObject()
            .put("angle", angleProp("globalLightAngle", "Global Light Angle", 120.0))
            .put("altitude", angleProp("globalLightAltitude", "Global Light Altitude", 30.0))
    }

    // ------------------------------------------------------------------ comps

    private data class Resolved(val ae: AeLayer, val layerId: Long, val itemId: Long?)

    private fun convertComp(
        comp: AeComp,
        ids: Ids,
        items: JSONObject,
        mediaResolver: (String) -> String?
    ) {
        val compId = ids.alloc()
        val (rateNum, rateDen) = frameRate(comp.frameRate)
        val durationTicks = ticks(comp.durationSeconds)
        val layersJson = JSONArray()
        // Allocate every id first so parent references can point forward.
        val resolved = comp.layers.map { layer ->
            val itemId = when (layer.type) {
                AeLayerType.FOOTAGE, AeLayerType.SOLID -> ids.alloc()
                else -> null
            }
            Resolved(layer, ids.alloc(), itemId)
        }
        val layerIdByName = resolved.associate { it.ae.name to it.layerId }
        for (entry in resolved) {
            val layer = convertLayer(entry.ae, entry.layerId, entry.itemId, layerIdByName, ids)
            layersJson.put(layer)
            if (entry.itemId != null) {
                items.put(entry.itemId.toString(), buildSourceItem(entry.ae, entry.itemId, comp, mediaResolver))
            }
        }
        val kind = JSONObject()
            .put("type", "Comp")
            .put("width", comp.width)
            .put("height", comp.height)
            .put("pixel_aspect", 1.0)
            .put("frame_rate", JSONObject().put("num", rateNum).put("den", rateDen))
            .put("duration", durationTicks)
            .put("display_start", 0)
            .put("background", numberArray(comp.background))
            .put("work_area", JSONArray().put(0).put(durationTicks))
            .put("layers", layersJson)
            .put("markers", JSONArray())
            .put("shutter_angle", 180.0)
            .put("shutter_phase", -90.0)
            .put("motion_blur_samples", 16)
            .put("motion_blur_adaptive_limit", 128)
            .put("renderer", "Classic3D")
            .put("hide_shy", false)
            .put("enable_motion_blur", true)
            .put("enable_frame_blending", true)
            .put("draft_3d", false)
            .put("preserve_frame_rate", false)
            .put("preserve_resolution", false)
            .put("poster_time", 0)
            .put("global_light", globalLight())
        items.put(
            compId.toString(),
            JSONObject()
                .put("id", compId)
                .put("name", comp.name)
                .put("label", "Sandstone")
                .put("comment", "")
                .put("parent", JSONObject.NULL)
                .put("kind", kind)
        )
    }

    // ------------------------------------------------------------------ layers

    private fun convertLayer(
        layer: AeLayer,
        layerId: Long,
        itemId: Long?,
        layerIdByName: Map<String, Long>,
        ids: Ids
    ): JSONObject {
        val source = when (layer.type) {
            AeLayerType.FOOTAGE -> JSONObject().put("type", "Footage").put("item", itemId)
            AeLayerType.SOLID -> JSONObject().put("type", "Solid").put("item", itemId)
            AeLayerType.TEXT -> JSONObject().put("type", "Text")
            AeLayerType.SHAPE -> JSONObject().put("type", "Shape")
            AeLayerType.NULL -> JSONObject().put("type", "Null")
        }
        val label = when (layer.type) {
            AeLayerType.TEXT -> "Red"
            AeLayerType.SHAPE -> "Blue"
            AeLayerType.FOOTAGE -> "Aqua"
            AeLayerType.SOLID -> "Red"
            AeLayerType.NULL -> "Red"
        }
        val parentId = layer.parentName?.let { layerIdByName[it] }
        return JSONObject()
            .put("id", layerId)
            .put("name", layer.name)
            .put("source", source)
            .put("label", label)
            .put("comment", "")
            .put("start_time", ticks(layer.startTime))
            .put("in_point", ticks(layer.inPoint))
            .put("out_point", ticks(layer.outPoint))
            .put("stretch", 100.0)
            .put("switches", switches())
            .put("blend_mode", "Normal")
            .put("auto_orient", "Off")
            .put(
                "parent",
                if (parentId != null) parentId else JSONObject.NULL
            )
            .put("props", layerProps(layer, ids))
    }

    private fun switches(): JSONObject = JSONObject()
        .put("video", true)
        .put("audio", true)
        .put("solo", false)
        .put("locked", false)
        .put("shy", false)
        .put("collapse", false)
        .put("quality", "Best")
        .put("sampling", "Bilinear")
        .put("effects", true)
        .put("frame_blend", "Off")
        .put("motion_blur", false)
        .put("adjustment", false)
        .put("three_d", false)
        .put("guide", false)

    // ------------------------------------------------------------------ props tree

    private fun layerProps(layer: AeLayer, ids: Ids): JSONObject {
        val children = mutableListOf<JSONObject>()
        when (layer.type) {
            AeLayerType.TEXT -> children += textGroup(layer, ids)
            AeLayerType.SHAPE -> children += contentsGroup(layer, ids)
            else -> Unit
        }
        // build::layer: AV layers get Masks + Effects; nulls get Effects only.
        if (layer.type != AeLayerType.NULL) children += emptyGroup(ids, "masks", "Masks")
        children += emptyGroup(ids, "effects", "Effects")
        children += transformGroup(layer, ids)
        if (layer.type == AeLayerType.FOOTAGE || layer.type == AeLayerType.SOLID ||
            layer.type == AeLayerType.TEXT || layer.type == AeLayerType.SHAPE
        ) {
            children += materialOptionsGroup(ids)
        }
        if (layer.type == AeLayerType.FOOTAGE && layer.hasAudio) {
            children += audioGroup(ids)
        }
        return JSONObject()
            .put("uid", ids.alloc())
            .put("match", "layer")
            .put("name", layer.name)
            .put("kind", "Plain")
            .put("enabled", true)
            .put("children", JSONArray(children))
    }

    private fun emptyGroup(ids: Ids, match: String, name: String): JSONObject =
        groupNode(ids, match, name, emptyList())

    /** A PropGroup as a tree node: {"node": "Group", ...fields}. */
    private fun groupNode(ids: Ids, match: String, name: String, children: List<JSONObject>, kind: String = "Plain"): JSONObject =
        JSONObject()
            .put("node", "Group")
            .put("uid", ids.alloc())
            .put("match", match)
            .put("name", name)
            .put("kind", kind)
            .put("enabled", true)
            .put("children", JSONArray(children))

    /** A Property as a tree node: {"node": "Prop", ...fields}. */
    private fun propNode(
        ids: Ids,
        match: String,
        name: String,
        value: JSONObject,
        uiKind: String,
        uiExtras: JSONObject? = null,
        spatial: Boolean = false,
        holdOnly: Boolean = false,
        threeDOnly: Boolean = false,
        twoDOnly: Boolean = false,
        shownDims: Int = 0,
        staticOnly: Boolean = false,
        keys: JSONArray? = null
    ): JSONObject {
        val ui = JSONObject().put("kind", uiKind)
        if (uiExtras != null) {
            val names = uiExtras.keys()
            while (names.hasNext()) {
                val key = names.next()
                ui.put(key, uiExtras.get(key))
            }
        }
        return JSONObject()
            .put("node", "Prop")
            .put("uid", ids.alloc())
            .put("match", match)
            .put("name", name)
            .put("value", value)
            .put("keys", keys ?: JSONArray())
            .put("ui", ui)
            .put("spatial", spatial)
            .put("hold_only", holdOnly)
            .put("three_d_only", threeDOnly)
            .put("two_d_only", twoDOnly)
            .put("shown_dims", shownDims)
            .put("static_only", staticOnly)
    }

    private fun sliderUi(min: Double, max: Double, sliderMin: Double, sliderMax: Double, decimals: Int): JSONObject =
        JSONObject()
            .put("min", min).put("max", max)
            .put("slider_min", sliderMin).put("slider_max", sliderMax)
            .put("decimals", decimals)

    private fun popupUi(options: List<String>): JSONObject =
        JSONObject().put("options", JSONArray(options))

    // Value constructors: {"t": "...", "v": ...} (effectcraft_keyframe::Value).
    private fun scalar(value: Double): JSONObject = JSONObject().put("t", "Scalar").put("v", value)
    private fun vec2(x: Double, y: Double): JSONObject =
        JSONObject().put("t", "Vec2").put("v", numberArray(listOf(x, y)))
    private fun vec3(x: Double, y: Double, z: Double): JSONObject =
        JSONObject().put("t", "Vec3").put("v", numberArray(listOf(x, y, z)))
    private fun colorValue(rgba: List<Double>): JSONObject =
        JSONObject().put("t", "Color").put("v", numberArray(rgba))
    private fun boolValue(value: Boolean): JSONObject = JSONObject().put("t", "Bool").put("v", value)
    private fun enumValue(index: Int): JSONObject = JSONObject().put("t", "Enum").put("v", index)

    private fun keyframe(timeSeconds: Double, value: JSONObject): JSONObject =
        JSONObject()
            .put("time", ticks(timeSeconds))
            .put("value", value)
            .put("in_interp", "Linear")
            .put("out_interp", "Linear")

    private fun keyframesFor(
        frames: List<AeKeyframe>?,
        toValue: (AeValue) -> JSONObject
    ): JSONArray? {
        if (frames.isNullOrEmpty()) return null
        val array = JSONArray()
        for (frame in frames) array.put(keyframe(frame.timeSeconds, toValue(frame.value)))
        return array
    }

    /** Mirrors build::transform exactly, then applies the layer's keyframes. */
    private fun transformGroup(layer: AeLayer, ids: Ids): JSONObject {
        val anchorKeys = keyframesFor(layer.keyframes["anchorPoint"]) { value ->
            val v = value as AeValue.Vec2
            vec3(v.x, v.y, 0.0)
        }
        val positionKeys = keyframesFor(layer.keyframes["position"]) { value ->
            val v = value as AeValue.Vec2
            vec3(v.x, v.y, 0.0)
        }
        val scaleKeys = keyframesFor(layer.keyframes["scale"]) { value ->
            val v = value as AeValue.Vec2
            vec3(v.x, v.y, 100.0)
        }
        val rotationKeys = keyframesFor(layer.keyframes["rotation"]) { value ->
            scalar((value as AeValue.Scalar).value)
        }
        val opacityKeys = keyframesFor(layer.keyframes["opacity"]) { value ->
            scalar((value as AeValue.Scalar).value)
        }
        val children = listOf(
            propNode(ids, "anchor", "Anchor Point", vec3(layer.anchorPoint.first, layer.anchorPoint.second, 0.0),
                "Point", spatial = true, shownDims = 2, keys = anchorKeys),
            propNode(ids, "position", "Position", vec3(layer.position.first, layer.position.second, 0.0),
                "Point", spatial = true, shownDims = 2, keys = positionKeys),
            propNode(ids, "scale", "Scale", vec3(layer.scale.first, layer.scale.second, 100.0),
                "Percent", shownDims = 2, keys = scaleKeys),
            propNode(ids, "orientation", "Orientation", vec3(0.0, 0.0, 0.0),
                "Angle", threeDOnly = true),
            propNode(ids, "rotationX", "X Rotation", scalar(0.0), "Angle", threeDOnly = true),
            propNode(ids, "rotationY", "Y Rotation", scalar(0.0), "Angle", threeDOnly = true),
            propNode(ids, "rotation", "Rotation", scalar(layer.rotation), "Angle", keys = rotationKeys),
            propNode(ids, "opacity", "Opacity", scalar(layer.opacity),
                "Slider", uiExtras = sliderUi(0.0, 100.0, 0.0, 100.0, 0), keys = opacityKeys)
        )
        return groupNode(ids, "transform", "Transform", children)
    }

    /** Mirrors build::text: Source Text + Per-character 3D + Path/More Options + Animators. */
    private fun textGroup(layer: AeLayer, ids: Ids): JSONObject {
        val fill = layer.color.padTo(4, 1.0)
        val doc = JSONObject()
            .put("text", layer.text)
            .put("font", layer.font)
            .put("style", "Regular")
            .put("size", layer.fontSize)
            .put("fill", numberArray(fill))
            .put("stroke", numberArray(listOf(0.0, 0.0, 0.0, 1.0)))
            .put("stroke_width", 0.0)
            .put("apply_fill", true)
            .put("apply_stroke", false)
            .put("stroke_over_fill", true)
            .put("line_join", "Miter")
            .put("tracking", 0.0)
            .put("leading", JSONObject.NULL)
            .put("justify", "Left")
            .put("baseline_shift", 0.0)
            .put("h_scale", 100.0)
            .put("v_scale", 100.0)
            .put("faux_bold", false)
            .put("faux_italic", false)
            .put("all_caps", false)
            .put("small_caps", false)
            .put("box_size", JSONObject.NULL)
            .put("box_pos", numberArray(listOf(0.0, 0.0)))
            .put("tsume", 0.0)
            .put("baseline", "Normal")
            .put("kerning", "Metrics")
            .put("ligatures", true)
            .put("tate_chu_yoko", false)
            .put("vertical_roman_upright", false)
            .put("indent_left", 0.0)
            .put("indent_right", 0.0)
            .put("indent_first", 0.0)
            .put("space_before", 0.0)
            .put("space_after", 0.0)
            .put("direction", "Ltr")
            .put("composer", "EveryLine")
            .put("hanging_punctuation", false)
            .put("vertical", false)
            .put("runs", JSONArray())
            .put("paragraphs", JSONArray())
        val pathOptions = groupNode(
            ids, "pathOptions", "Path Options", listOf(
                propNode(ids, "path", "Path", enumValue(0), "Popup",
                    uiExtras = popupUi(listOf("None")), staticOnly = true),
                propNode(ids, "reversePath", "Reverse Path", boolValue(false), "Checkbox"),
                propNode(ids, "perpendicular", "Perpendicular To Path", boolValue(true), "Checkbox"),
                propNode(ids, "forceAlignment", "Force Alignment", boolValue(false), "Checkbox"),
                propNode(ids, "firstMargin", "First Margin", scalar(0.0), "Pixels"),
                propNode(ids, "lastMargin", "Last Margin", scalar(0.0), "Pixels")
            )
        )
        val moreOptions = groupNode(
            ids, "moreOptions", "More Options", listOf(
                propNode(ids, "anchorGrouping", "Anchor Point Grouping", enumValue(0), "Popup",
                    uiExtras = popupUi(listOf("Character", "Word", "Line", "All")), staticOnly = true),
                propNode(ids, "groupingAlignment", "Grouping Alignment", vec2(0.0, 0.0), "Percent"),
                propNode(ids, "fillStroke", "Fill & Stroke", enumValue(0), "Popup",
                    uiExtras = popupUi(listOf("Per Character Palette", "All Fills Over All Strokes", "All Strokes Over All Fills")),
                    staticOnly = true),
                propNode(ids, "interCharBlend", "Inter-Character Blending", enumValue(0), "Popup",
                    uiExtras = popupUi(INTER_CHAR_BLEND_MODES), staticOnly = true)
            )
        )
        return groupNode(
            ids, "text", "Text", listOf(
                propNode(ids, "sourceText", "Source Text",
                    JSONObject().put("t", "Text").put("v", doc),
                    "Text", holdOnly = true),
                propNode(ids, "perChar3d", "Per-character 3D", boolValue(false),
                    "Hidden", staticOnly = true),
                pathOptions,
                moreOptions,
                groupNode(ids, "animators", "Animators", emptyList())
            )
        )
    }

    /** Mirrors build::shape_group with one rectangle + fill. */
    private fun contentsGroup(layer: AeLayer, ids: Ids): JSONObject {
        val fill = layer.color.padTo(4, 1.0)
        val rect = groupNode(
            ids, "rect", "Rectangle Path 1", listOf(
                propNode(ids, "direction", "Path Direction", enumValue(0), "Hidden"),
                propNode(ids, "size", "Size", vec2(layer.size[0], layer.size[1]), "Pixels"),
                propNode(ids, "position", "Position", vec2(0.0, 0.0), "Point"),
                propNode(ids, "roundness", "Roundness", scalar(0.0), "Pixels"),
                propNode(ids, "perCorner", "Per-Corner Roundness", boolValue(false), "Checkbox"),
                propNode(ids, "topLeftRoundness", "Top Left Roundness", scalar(0.0), "Pixels"),
                propNode(ids, "topRightRoundness", "Top Right Roundness", scalar(0.0), "Pixels"),
                propNode(ids, "bottomRightRoundness", "Bottom Right Roundness", scalar(0.0), "Pixels"),
                propNode(ids, "bottomLeftRoundness", "Bottom Left Roundness", scalar(0.0), "Pixels")
            ), kind = "Indexed"
        )
        val fillGroup = groupNode(
            ids, "fill", "Fill 1", listOf(
                propNode(ids, "blend", "Blend Mode", enumValue(0), "Hidden"),
                propNode(ids, "composite", "Composite", enumValue(0), "Popup",
                    uiExtras = popupUi(listOf("Below Previous in Same Group", "Above Previous in Same Group"))),
                propNode(ids, "rule", "Fill Rule", enumValue(0), "Popup",
                    uiExtras = popupUi(listOf("Non-Zero Winding", "Even-Odd"))),
                propNode(ids, "color", "Color", colorValue(fill), "Color"),
                propNode(ids, "opacity", "Opacity", scalar(100.0),
                    "Slider", uiExtras = sliderUi(0.0, 100.0, 0.0, 100.0, 0))
            ), kind = "Indexed"
        )
        val shapeGroup = groupNode(
            ids, "group", layer.name, listOf(
                propNode(ids, "blend", "Blend Mode", enumValue(0), "Hidden"),
                groupNode(ids, "contents", "Contents", listOf(rect, fillGroup)),
                groupNode(
                    ids, "transform", "Transform", listOf(
                        propNode(ids, "anchor", "Anchor Point", vec2(0.0, 0.0), "Point"),
                        propNode(ids, "position", "Position", vec2(0.0, 0.0), "Point"),
                        propNode(ids, "scale", "Scale", vec2(100.0, 100.0), "Percent"),
                        propNode(ids, "skew", "Skew", scalar(0.0),
                            "Slider", uiExtras = sliderUi(-85.0, 85.0, -85.0, 85.0, 1)),
                        propNode(ids, "skewAxis", "Skew Axis", scalar(0.0), "Angle"),
                        propNode(ids, "rotation", "Rotation", scalar(0.0), "Angle"),
                        propNode(ids, "opacity", "Opacity", scalar(100.0),
                            "Slider", uiExtras = sliderUi(0.0, 100.0, 0.0, 100.0, 0))
                    )
                )
            ), kind = "Indexed"
        )
        return groupNode(ids, "contents", "Contents", listOf(shapeGroup))
    }

    /** Mirrors build::material_options. */
    private fun materialOptionsGroup(ids: Ids): JSONObject = groupNode(
        ids, "materialOptions", "Material Options", listOf(
            propNode(ids, "castsShadows", "Casts Shadows", enumValue(0), "Popup",
                uiExtras = popupUi(listOf("Off", "On", "Only"))),
            propNode(ids, "lightTransmission", "Light Transmission", scalar(0.0), "Percent"),
            propNode(ids, "acceptsShadows", "Accepts Shadows", enumValue(1), "Popup",
                uiExtras = popupUi(listOf("Off", "On", "Only"))),
            propNode(ids, "acceptsLights", "Accepts Lights", boolValue(true), "Checkbox"),
            propNode(ids, "ambient", "Ambient", scalar(100.0), "Percent"),
            propNode(ids, "diffuse", "Diffuse", scalar(50.0), "Percent"),
            propNode(ids, "specularIntensity", "Specular Intensity", scalar(50.0), "Percent"),
            propNode(ids, "specularShininess", "Specular Shininess", scalar(5.0), "Percent"),
            propNode(ids, "metal", "Metal", scalar(100.0), "Percent")
        )
    )

    /** Mirrors build::audio. */
    private fun audioGroup(ids: Ids): JSONObject = groupNode(
        ids, "audio", "Audio", listOf(
            propNode(ids, "levels", "Audio Levels", vec2(0.0, 0.0), "Number")
        )
    )

    // ------------------------------------------------------------------ source items

    private fun buildSourceItem(
        layer: AeLayer,
        itemId: Long,
        comp: AeComp,
        mediaResolver: (String) -> String?
    ): JSONObject {
        return when (layer.type) {
            AeLayerType.FOOTAGE -> {
                val resolved = layer.file?.let { mediaResolver(it) }
                val missing = layer.file != null && resolved == null
                val path = resolved ?: layer.file ?: ""
                val (rateNum, rateDen) = frameRate(layer.frameRate ?: comp.frameRate)
                val kind = when (layer.kind) {
                    "still" -> "Still"
                    "audio" -> "Audio"
                    else -> "Video"
                }
                val kindJson = JSONObject()
                    .put("type", "Footage")
                    .put("path", path)
                    .put("kind", kind)
                    .put("width", layer.width)
                    .put("height", layer.height)
                    .put("pixel_aspect", 1.0)
                    .put("frame_rate", JSONObject().put("num", rateNum).put("den", rateDen))
                    .put("duration", ticks(layer.durationSeconds))
                    .put("has_video", kind != "Audio")
                    .put("has_audio", layer.hasAudio)
                    .put("alpha", "Straight")
                    .put("premul_color", numberArray(listOf(0.0, 0.0, 0.0)))
                    .put("loop_count", 1)
                    .put("codec", "")
                    .put("missing", missing)
                itemJson(itemId, layer.file?.substringAfterLast('/') ?: layer.name, "Aqua", kindJson)
            }
            AeLayerType.SOLID -> {
                val rgb = layer.color.padTo(3, 0.0)
                val kindJson = JSONObject()
                    .put("type", "Solid")
                    .put("color", numberArray(rgb))
                    .put("width", layer.width)
                    .put("height", layer.height)
                    .put("pixel_aspect", 1.0)
                itemJson(itemId, layer.name, "Red", kindJson)
            }
            else -> throw IllegalArgumentException("Layer ${layer.name} has no source item")
        }
    }

    private fun itemJson(itemId: Long, name: String, label: String, kind: JSONObject): JSONObject =
        JSONObject()
            .put("id", itemId)
            .put("name", name)
            .put("label", label)
            .put("comment", "")
            .put("parent", JSONObject.NULL)
            .put("kind", kind)

    // ------------------------------------------------------------------ helpers

    private fun numberArray(values: List<Double>): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value)
        return array
    }

    private fun List<Double>.padTo(size: Int, pad: Double): List<Double> {
        if (this.size >= size) return take(size)
        return this + List(size - this.size) { pad }
    }

    private val INTER_CHAR_BLEND_MODES = listOf(
        "Normal", "Darken", "Multiply", "Color Burn", "Linear Burn",
        "Darker Color", "Add", "Lighten", "Screen", "Color Dodge",
        "Lighter Color", "Overlay", "Soft Light", "Hard Light",
        "Linear Light", "Vivid Light", "Pin Light", "Hard Mix",
        "Difference", "Exclusion", "Subtract", "Divide", "Hue",
        "Saturation", "Color", "Luminosity"
    )
}
