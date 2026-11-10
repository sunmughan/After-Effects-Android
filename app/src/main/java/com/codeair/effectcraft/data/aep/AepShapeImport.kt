package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.AeInterchangeConverter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Shape layer contents: groups (nested, with their transform), rectangle, ellipse,
 * polystar and bezier paths, fill, stroke, gradient fill and stroke, trim paths, round
 * corners, merge paths, offset paths and repeater, with keyframes and expressions,
 * into EffectCraft's shape model.
 *
 * Port of `import/shapes.rs` + the shape builders of `build.rs` from the official
 * EffectCraft project (storytold/effectcraft, MIT OR Apache-2.0). Every value of a
 * shape item is stored as After Effects shows it: sizes, positions, radii, widths
 * and the group anchor in pixels, the group scale, every opacity, the trim start
 * and end and the polystar roundness in percent, angles in degrees, colours as ARGB
 * 0..255, popups 1-based. Contents keep the file order, which is also EffectCraft's
 * (the first item is on top, a fill paints the paths above it in its group).
 *
 * Groups are built with patch-space uids (from 1 within each top-level group) and
 * remapped with the converter's `next_id` in the patch step, like effect and mask
 * groups.
 *
 * Not imported, and reported in [AepImportReport.LossCategory.ShapeLayers]: items
 * other than the ones above (wiggle, zig zag, pucker, twist, ...), stroke dashes,
 * taper and wave with set values, blend modes and composite order of items, shape
 * directions, the colour stops of a gradient (the default white-to-black arrives),
 * and the material options of extruded shapes.
 */
object AepShapeImport {

    /** Deeper groups than this are not imported (and reported). */
    const val MAX_DEPTH = 32

    /** The import context: what is being imported, and where losses go. */
    private class Cx(
        val what: String,
        val timescale: Long,
        val col: AepImportReport.Collector
    )

    /** Patch-space uids within one top-level group: 1-based, remapped by the caller. */
    private class Uids {
        var next = 1L
        fun alloc(): Long = next++
    }

    /**
     * Imports the contents of a shape layer: the groups under `ADBE Root Vectors Group`,
     * in file order. Empty when the layer has no shape contents.
     */
    fun importShapes(
        root: AepTypedView.PropGroup?,
        what: String,
        timescale: Long,
        allocBlock: (Int) -> Long,
        col: AepImportReport.Collector
    ): List<JSONObject> {
        val vectors = (root?.get("ADBE Root Vectors Group") as? AepTypedView.PropNode.Group)?.group
            ?: return emptyList()
        val cx = Cx(what, timescale, col)
        return contents(vectors, 0, cx, Uids()) { group, uids ->
            val base = allocBlock((uids.next - 1).toInt())
            AepEffectImport.remapUids(group, base - 1)
        }
    }

    // ------------------------------------------------------------------ JSON builders

    /** A canonical group node, mirroring the converter's `groupNode`. */
    private fun grp(
        uids: Uids,
        match: String,
        name: String,
        children: List<JSONObject>,
        kind: String = "Plain"
    ): JSONObject = JSONObject()
        .put("node", "Group")
        .put("uid", uids.alloc())
        .put("match", match)
        .put("name", name)
        .put("kind", JSONObject().put("kind", kind))
        .put("enabled", true)
        .put("children", JSONArray(children))

    /** A canonical property node, mirroring the converter's `propNode`. */
    private fun prop(
        uids: Uids,
        match: String,
        name: String,
        value: JSONObject,
        uiKind: String,
        uiExtras: JSONObject? = null
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
            .put("uid", uids.alloc())
            .put("match", match)
            .put("name", name)
            .put("value", value)
            .put("keys", JSONArray())
            .put("ui", ui)
            .put("spatial", false)
            .put("hold_only", false)
            .put("three_d_only", false)
            .put("two_d_only", false)
            .put("shown_dims", 0)
            .put("static_only", false)
    }

    private fun sliderUi(min: Double, max: Double, sliderMin: Double, sliderMax: Double, decimals: Int): JSONObject =
        JSONObject()
            .put("min", min).put("max", max)
            .put("slider_min", sliderMin).put("slider_max", sliderMax)
            .put("decimals", decimals)

    private fun popupUi(options: List<String>): JSONObject =
        JSONObject().put("options", JSONArray(options))

    // Value constructors: {"t": "...", "v": ...} (effectcraft_keyframe::Value).
    private fun scalar(v: Double): JSONObject = JSONObject().put("t", "Scalar").put("v", v)
    private fun vec2(x: Double, y: Double): JSONObject =
        JSONObject().put("t", "Vec2").put("v", JSONArray().put(x).put(y))
    private fun colorValue(rgba: List<Double>): JSONObject =
        JSONObject().put("t", "Color").put("v", JSONArray().apply { rgba.forEach { put(it) } })
    private fun boolValue(v: Boolean): JSONObject = JSONObject().put("t", "Bool").put("v", v)
    private fun enumValue(i: Int): JSONObject = JSONObject().put("t", "Enum").put("v", i)
    private fun pathValue(path: JSONObject): JSONObject = JSONObject().put("t", "Path").put("v", path)

    /** The default gradient (white to black); the stops of a stored gradient are not decoded. */
    private fun gradientValue(): JSONObject = JSONObject().put("t", "Gradient").put(
        "v", JSONObject()
            .put(
                "colors", JSONArray()
                    .put(JSONArray().put(0.0).put(JSONArray().put(1.0).put(1.0).put(1.0).put(1.0)))
                    .put(JSONArray().put(1.0).put(JSONArray().put(0.0).put(0.0).put(0.0).put(1.0)))
            )
            .put(
                "opacities", JSONArray()
                    .put(JSONArray().put(0.0).put(1.0))
                    .put(JSONArray().put(1.0).put(1.0))
            )
    )

    // ------------------------------------------------------- build::shape_* builders

    /** Shape group transform (2D). */
    private fun shapeTransform(uids: Uids): JSONObject = grp(
        uids, "transform", "Transform", listOf(
            prop(uids, "anchor", "Anchor Point", vec2(0.0, 0.0), "Point"),
            prop(uids, "position", "Position", vec2(0.0, 0.0), "Point"),
            prop(uids, "scale", "Scale", vec2(100.0, 100.0), "Percent"),
            prop(uids, "skew", "Skew", scalar(0.0), "Slider", sliderUi(-85.0, 85.0, -85.0, 85.0, 1)),
            prop(uids, "skewAxis", "Skew Axis", scalar(0.0), "Angle"),
            prop(uids, "rotation", "Rotation", scalar(0.0), "Angle"),
            prop(uids, "opacity", "Opacity", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 0))
        )
    )

    private fun shapeGroup(uids: Uids, name: String, items: List<JSONObject>): JSONObject {
        val contents = grp(uids, "contents", "Contents", items)
        return grp(
            uids, "group", name, listOf(
                prop(uids, "blend", "Blend Mode", enumValue(0), "Hidden"),
                contents,
                shapeTransform(uids)
            ), kind = "Indexed"
        )
    }

    private fun shapeRect(
        uids: Uids, size: Pair<Double, Double>, position: Pair<Double, Double>, roundness: Double
    ): JSONObject {
        // Per-corner radii start from the uniform Roundness, so ticking the box keeps the shape.
        val r = if (roundness.isFinite()) roundness.coerceAtLeast(0.0) else 0.0
        return grp(
            uids, "rect", "Rectangle Path 1", listOf(
                prop(uids, "direction", "Path Direction", enumValue(0), "Hidden"),
                prop(uids, "size", "Size", vec2(size.first, size.second), "Pixels"),
                prop(uids, "position", "Position", vec2(position.first, position.second), "Point"),
                prop(uids, "roundness", "Roundness", scalar(roundness), "Pixels"),
                prop(uids, "perCorner", "Per-Corner Roundness", boolValue(false), "Checkbox"),
                prop(uids, "topLeftRoundness", "Top Left Roundness", scalar(r), "Pixels"),
                prop(uids, "topRightRoundness", "Top Right Roundness", scalar(r), "Pixels"),
                prop(uids, "bottomRightRoundness", "Bottom Right Roundness", scalar(r), "Pixels"),
                prop(uids, "bottomLeftRoundness", "Bottom Left Roundness", scalar(r), "Pixels")
            ), kind = "Indexed"
        )
    }

    private fun shapeEllipse(
        uids: Uids, size: Pair<Double, Double>, position: Pair<Double, Double>
    ): JSONObject = grp(
        uids, "ellipse", "Ellipse Path 1", listOf(
            prop(uids, "direction", "Path Direction", enumValue(0), "Hidden"),
            prop(uids, "size", "Size", vec2(size.first, size.second), "Pixels"),
            prop(uids, "position", "Position", vec2(position.first, position.second), "Point")
        ), kind = "Indexed"
    )

    /** Polystar: `star = false` makes a polygon. */
    private fun shapeStar(
        uids: Uids, star: Boolean, points: Double,
        position: Pair<Double, Double>, outer: Double, inner: Double
    ): JSONObject {
        val children = ArrayList<JSONObject>()
        children.add(prop(uids, "direction", "Path Direction", enumValue(0), "Hidden"))
        children.add(
            prop(
                uids, "type", "Type", enumValue(if (star) 0 else 1), "Popup",
                popupUi(listOf("Star", "Polygon"))
            )
        )
        children.add(prop(uids, "points", "Points", scalar(points), "Slider", sliderUi(3.0, 100.0, 3.0, 20.0, 1)))
        children.add(prop(uids, "position", "Position", vec2(position.first, position.second), "Point"))
        children.add(prop(uids, "rotation", "Rotation", scalar(0.0), "Angle"))
        if (star) children.add(prop(uids, "innerRadius", "Inner Radius", scalar(inner), "Pixels"))
        children.add(prop(uids, "outerRadius", "Outer Radius", scalar(outer), "Pixels"))
        if (star) children.add(prop(uids, "innerRoundness", "Inner Roundness", scalar(0.0), "Percent"))
        children.add(prop(uids, "outerRoundness", "Outer Roundness", scalar(0.0), "Percent"))
        return grp(uids, "star", "Polystar Path 1", children, kind = "Indexed")
    }

    private fun shapePath(uids: Uids, path: JSONObject): JSONObject = grp(
        uids, "path", "Path 1", listOf(
            prop(uids, "direction", "Path Direction", enumValue(0), "Hidden"),
            prop(uids, "path", "Path", pathValue(path), "Path")
        ), kind = "Indexed"
    )

    private fun shapeFill(uids: Uids, color: List<Double>): JSONObject = grp(
        uids, "fill", "Fill 1", listOf(
            prop(uids, "blend", "Blend Mode", enumValue(0), "Hidden"),
            prop(uids, "composite", "Composite", enumValue(0), "Popup", popupUi(listOf("Below Previous in Same Group", "Above Previous in Same Group"))),
            prop(uids, "rule", "Fill Rule", enumValue(0), "Popup", popupUi(listOf("Non-Zero Winding", "Even-Odd"))),
            prop(uids, "color", "Color", colorValue(color), "Color"),
            prop(uids, "opacity", "Opacity", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 0))
        ), kind = "Indexed"
    )

    /** Stroke ▸ Dashes. */
    private fun strokeDashes(uids: Uids): JSONObject = grp(
        uids, "dashes", "Dashes", listOf(
            prop(uids, "dash", "Dash", scalar(0.0), "Pixels"),
            prop(uids, "gap", "Gap", scalar(0.0), "Pixels"),
            prop(uids, "offset", "Offset", scalar(0.0), "Pixels")
        )
    )

    /** Stroke ▸ Taper (After Effects 17.1+): width ramps at the start and end of each path. */
    private fun strokeTaper(uids: Uids): JSONObject = grp(
        uids, "taper", "Taper", listOf(
            prop(uids, "units", "Length Units", enumValue(0), "Popup", popupUi(listOf("Pixels", "Percent"))),
            prop(uids, "startLength", "Start Length", scalar(0.0), "Pixels"),
            prop(uids, "endLength", "End Length", scalar(0.0), "Pixels"),
            prop(uids, "startWidth", "Start Width", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "endWidth", "End Width", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "startEase", "Start Ease", scalar(0.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "endEase", "End Ease", scalar(0.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1))
        )
    )

    /** Stroke ▸ Wave: the width oscillates along the path. */
    private fun strokeWave(uids: Uids): JSONObject = grp(
        uids, "wave", "Wave", listOf(
            prop(uids, "amount", "Amount", scalar(0.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "units", "Units", enumValue(0), "Popup", popupUi(listOf("Pixels", "Cycles"))),
            prop(uids, "wavelength", "Wavelength", scalar(20.0), "Pixels"),
            prop(uids, "cycles", "Cycles", scalar(10.0), "Slider", sliderUi(0.0, 1000.0, 0.0, 50.0, 1)),
            prop(uids, "phase", "Phase", scalar(0.0), "Angle")
        )
    )

    private fun shapeStroke(uids: Uids, color: List<Double>, width: Double): JSONObject = grp(
        uids, "stroke", "Stroke 1", listOf(
            prop(uids, "blend", "Blend Mode", enumValue(0), "Hidden"),
            prop(uids, "composite", "Composite", enumValue(0), "Popup", popupUi(listOf("Below Previous in Same Group", "Above Previous in Same Group"))),
            prop(uids, "color", "Color", colorValue(color), "Color"),
            prop(uids, "opacity", "Opacity", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 0)),
            prop(uids, "width", "Stroke Width", scalar(width), "Pixels"),
            prop(uids, "cap", "Line Cap", enumValue(0), "Popup", popupUi(listOf("Butt Cap", "Round Cap", "Projecting Cap"))),
            prop(uids, "join", "Line Join", enumValue(0), "Popup", popupUi(listOf("Miter Join", "Round Join", "Bevel Join"))),
            prop(uids, "miter", "Miter Limit", scalar(4.0), "Number"),
            strokeDashes(uids),
            strokeTaper(uids),
            strokeWave(uids)
        ), kind = "Indexed"
    )

    private fun shapeGradientFill(
        uids: Uids, radial: Boolean, start: Pair<Double, Double>, end: Pair<Double, Double>
    ): JSONObject = grp(
        uids, "gfill", "Gradient Fill 1", listOf(
            prop(uids, "blend", "Blend Mode", enumValue(0), "Hidden"),
            prop(uids, "rule", "Fill Rule", enumValue(0), "Popup", popupUi(listOf("Non-Zero Winding", "Even-Odd"))),
            prop(uids, "type", "Type", enumValue(if (radial) 1 else 0), "Popup", popupUi(listOf("Linear", "Radial"))),
            prop(uids, "start", "Start Point", vec2(start.first, start.second), "Point"),
            prop(uids, "end", "End Point", vec2(end.first, end.second), "Point"),
            prop(uids, "highlightLength", "Highlight Length", scalar(0.0), "Percent"),
            prop(uids, "highlightAngle", "Highlight Angle", scalar(0.0), "Angle"),
            prop(uids, "colors", "Colors", gradientValue(), "Gradient"),
            prop(uids, "opacity", "Opacity", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 0))
        ), kind = "Indexed"
    )

    /** Gradient Stroke: a stroke painted with a linear or radial gradient. */
    private fun shapeGradientStroke(
        uids: Uids, radial: Boolean, start: Pair<Double, Double>,
        end: Pair<Double, Double>, width: Double
    ): JSONObject = grp(
        uids, "gstroke", "Gradient Stroke 1", listOf(
            prop(uids, "blend", "Blend Mode", enumValue(0), "Hidden"),
            prop(uids, "composite", "Composite", enumValue(0), "Popup", popupUi(listOf("Below Previous in Same Group", "Above Previous in Same Group"))),
            prop(uids, "type", "Type", enumValue(if (radial) 1 else 0), "Popup", popupUi(listOf("Linear", "Radial"))),
            prop(uids, "start", "Start Point", vec2(start.first, start.second), "Point"),
            prop(uids, "end", "End Point", vec2(end.first, end.second), "Point"),
            prop(uids, "highlightLength", "Highlight Length", scalar(0.0), "Percent"),
            prop(uids, "highlightAngle", "Highlight Angle", scalar(0.0), "Angle"),
            prop(uids, "colors", "Colors", gradientValue(), "Gradient"),
            prop(uids, "opacity", "Opacity", scalar(100.0), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 0)),
            prop(uids, "width", "Stroke Width", scalar(width), "Pixels"),
            prop(uids, "cap", "Line Cap", enumValue(0), "Popup", popupUi(listOf("Butt Cap", "Round Cap", "Projecting Cap"))),
            prop(uids, "join", "Line Join", enumValue(0), "Popup", popupUi(listOf("Miter Join", "Round Join", "Bevel Join"))),
            prop(uids, "miter", "Miter Limit", scalar(4.0), "Number"),
            strokeDashes(uids),
            strokeTaper(uids),
            strokeWave(uids)
        ), kind = "Indexed"
    )

    private fun shapeTrim(uids: Uids, start: Double, end: Double, offset: Double): JSONObject = grp(
        uids, "trim", "Trim Paths 1", listOf(
            prop(uids, "start", "Start", scalar(start), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "end", "End", scalar(end), "Slider", sliderUi(0.0, 100.0, 0.0, 100.0, 1)),
            prop(uids, "offset", "Offset", scalar(offset), "Angle"),
            prop(uids, "mode", "Trim Multiple Shapes", enumValue(0), "Popup", popupUi(listOf("Simultaneously", "Individually")))
        ), kind = "Indexed"
    )

    private fun shapeRepeater(uids: Uids, copies: Double, offsetPos: Pair<Double, Double>): JSONObject {
        val tr = grp(
            uids, "transform", "Transform", listOf(
                prop(uids, "anchor", "Anchor Point", vec2(0.0, 0.0), "Point"),
                prop(uids, "position", "Position", vec2(offsetPos.first, offsetPos.second), "Point"),
                prop(uids, "scale", "Scale", vec2(100.0, 100.0), "Percent"),
                prop(uids, "rotation", "Rotation", scalar(0.0), "Angle"),
                prop(uids, "startOpacity", "Start Opacity", scalar(100.0), "Percent"),
                prop(uids, "endOpacity", "End Opacity", scalar(100.0), "Percent")
            )
        )
        return grp(
            uids, "repeater", "Repeater 1", listOf(
                prop(uids, "copies", "Copies", scalar(copies), "Number"),
                prop(uids, "offset", "Offset", scalar(0.0), "Number"),
                prop(uids, "composite", "Composite", enumValue(0), "Popup", popupUi(listOf("Below", "Above"))),
                tr
            ), kind = "Indexed"
        )
    }

    private fun shapeRound(uids: Uids): JSONObject = grp(
        uids, "round", "Round Corners 1", listOf(
            prop(uids, "radius", "Radius", scalar(10.0), "Pixels")
        ), kind = "Indexed"
    )

    private fun shapeMerge(uids: Uids): JSONObject = grp(
        uids, "merge", "Merge Paths 1", listOf(
            prop(
                uids, "mode", "Mode", enumValue(0), "Popup",
                popupUi(listOf("Merge", "Add", "Subtract", "Intersect", "Exclude Intersections"))
            )
        ), kind = "Indexed"
    )

    private fun shapeOffset(uids: Uids): JSONObject = grp(
        uids, "offset", "Offset Paths 1", listOf(
            prop(uids, "amount", "Amount", scalar(10.0), "Pixels"),
            prop(uids, "join", "Line Join", enumValue(0), "Popup", popupUi(listOf("Miter Join", "Round Join", "Bevel Join"))),
            prop(uids, "miter", "Miter Limit", scalar(4.0), "Number"),
            prop(uids, "copies", "Copies", scalar(1.0), "Number"),
            prop(uids, "copyOffset", "Copy Offset", scalar(0.0), "Number")
        ), kind = "Indexed"
    )

    // ------------------------------------------------------------------ import

    /** The items of a contents group, in file order. */
    private fun contents(
        g: AepTypedView.PropGroup,
        depth: Int,
        cx: Cx,
        uids: Uids,
        onTopLevel: ((JSONObject, Uids) -> Unit)? = null
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (e in g.entries()) {
            when (val node = e.node) {
                is AepTypedView.PropNode.Group -> {
                    if (onTopLevel != null) {
                        // A top-level item gets its own patch-space uid block.
                        val childUids = Uids()
                        val item = item(e.matchName, node.group, depth, cx, childUids) ?: continue
                        node.group.flags()?.let { item.put("enabled", it and 1L != 0L) }
                        onTopLevel(item, childUids)
                        out.add(item)
                    } else {
                        val item = item(e.matchName, node.group, depth, cx, uids) ?: continue
                        node.group.flags()?.let { item.put("enabled", it and 1L != 0L) }
                        out.add(item)
                    }
                }
                is AepTypedView.PropNode.Property -> {
                    if (stored(node.property)) {
                        cx.col.lose(
                            AepImportReport.LossCategory.ShapeLayers,
                            "${cx.what}: shape contents property ${e.matchName} not imported"
                        )
                    }
                }
                is AepTypedView.PropNode.Other -> cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: shape contents entry ${e.matchName} not imported"
                )
            }
        }
        return out
    }

    /** Whether a property holds anything: a value record, keys or an expression. */
    private fun stored(p: AepTypedView.Property): Boolean =
        p.flags() == 1L || p.isAnimated() || p.expression() != null

    private fun named(g: JSONObject, aep: AepTypedView.PropGroup): JSONObject {
        val name = aep.userName()?.takeIf { it.isNotEmpty() }
        if (name != null) g.put("name", name)
        return g
    }

    private fun item(
        matchName: String,
        g: AepTypedView.PropGroup,
        depth: Int,
        cx: Cx,
        uids: Uids
    ): JSONObject? {
        val built: JSONObject = when (matchName) {
            "ADBE Vector Group" -> {
                if (depth >= MAX_DEPTH) {
                    cx.col.lose(
                        AepImportReport.LossCategory.ShapeLayers,
                        "${cx.what}: shape groups nested deeper than $MAX_DEPTH not imported"
                    )
                    return null
                }
                val children = when (val c = g.get("ADBE Vectors Group")) {
                    is AepTypedView.PropNode.Group -> contents(c.group, depth + 1, cx, uids)
                    else -> emptyList()
                }
                val grp = shapeGroup(uids, "Group 1", children)
                val transformNode = g.get("ADBE Vector Transform Group")
                if (transformNode is AepTypedView.PropNode.Group) {
                    val t = transformNode.group
                    val tr = AepEffectImport.propMut(grp, "transform")
                    if (tr != null) {
                        for ((m, id, kind) in listOf(
                            Triple("ADBE Vector Anchor", "anchor", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Position", "position", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Scale", "scale", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Skew", "skew", AepEffectKeys.Kind.Number(1.0)),
                            Triple("ADBE Vector Skew Axis", "skewAxis", AepEffectKeys.Kind.Number(1.0)),
                            Triple("ADBE Vector Rotation", "rotation", AepEffectKeys.Kind.Number(1.0)),
                            Triple("ADBE Vector Group Opacity", "opacity", AepEffectKeys.Kind.Number(1.0))
                        )) {
                            set(tr, id, t, m, kind, cx)
                        }
                        unknown(
                            t,
                            listOf(
                                "ADBE Vector Anchor",
                                "ADBE Vector Position",
                                "ADBE Vector Scale",
                                "ADBE Vector Skew",
                                "ADBE Vector Skew Axis",
                                "ADBE Vector Rotation",
                                "ADBE Vector Group Opacity"
                            ),
                            "group transform",
                            cx
                        )
                    }
                }
                notDefault(g, "ADBE Vector Blend Mode", "group blend mode", cx)
                val materials = (g.get("ADBE Vector Materials Group") as? AepTypedView.PropNode.Group)?.group
                if (materials != null && materials.entries().any { e ->
                    (e.node as? AepTypedView.PropNode.Property)?.let { stored(it.property) } == true
                }) {
                    cx.col.lose(
                        AepImportReport.LossCategory.ShapeLayers,
                        "${cx.what}: shape group material options not imported"
                    )
                }
                unknown(
                    g,
                    listOf(
                        "ADBE Vectors Group",
                        "ADBE Vector Transform Group",
                        "ADBE Vector Blend Mode",
                        "ADBE Vector Materials Group"
                    ),
                    "group",
                    cx
                )
                grp
            }
            "ADBE Vector Shape - Rect" -> {
                val r = shapeRect(uids, 100.0 to 100.0, 0.0 to 0.0, 0.0)
                set(r, "size", g, "ADBE Vector Rect Size", AepEffectKeys.Kind.Vec2, cx)
                set(r, "position", g, "ADBE Vector Rect Position", AepEffectKeys.Kind.Vec2, cx)
                set(r, "roundness", g, "ADBE Vector Rect Roundness", AepEffectKeys.Kind.Number(1.0), cx)
                notDefault(g, "ADBE Vector Shape Direction", "shape direction", cx)
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Rect Size",
                        "ADBE Vector Rect Position",
                        "ADBE Vector Rect Roundness",
                        "ADBE Vector Shape Direction"
                    ),
                    "rectangle",
                    cx
                )
                r
            }
            "ADBE Vector Shape - Ellipse" -> {
                val e = shapeEllipse(uids, 100.0 to 100.0, 0.0 to 0.0)
                set(e, "size", g, "ADBE Vector Ellipse Size", AepEffectKeys.Kind.Vec2, cx)
                set(e, "position", g, "ADBE Vector Ellipse Position", AepEffectKeys.Kind.Vec2, cx)
                notDefault(g, "ADBE Vector Shape Direction", "shape direction", cx)
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Ellipse Size",
                        "ADBE Vector Ellipse Position",
                        "ADBE Vector Shape Direction"
                    ),
                    "ellipse",
                    cx
                )
                e
            }
            "ADBE Vector Shape - Star" -> star(g, cx, uids)
            "ADBE Vector Shape - Group" -> path(g, cx, uids) ?: return null
            "ADBE Vector Graphic - Fill" -> {
                val f = shapeFill(uids, listOf(1.0, 0.0, 0.0, 1.0))
                set(f, "color", g, "ADBE Vector Fill Color", AepEffectKeys.Kind.Color, cx)
                set(f, "opacity", g, "ADBE Vector Fill Opacity", AepEffectKeys.Kind.Number(1.0), cx)
                set(f, "rule", g, "ADBE Vector Fill Rule", AepEffectKeys.Kind.Popup(2), cx)
                paintSwitches(g, cx)
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Fill Color",
                        "ADBE Vector Fill Opacity",
                        "ADBE Vector Fill Rule",
                        "ADBE Vector Blend Mode",
                        "ADBE Vector Composite Order"
                    ),
                    "fill",
                    cx
                )
                f
            }
            "ADBE Vector Graphic - Stroke" -> {
                val s = shapeStroke(uids, listOf(1.0, 1.0, 1.0, 1.0), 2.0)
                set(s, "color", g, "ADBE Vector Stroke Color", AepEffectKeys.Kind.Color, cx)
                strokeCommon(s, g, cx)
                unknown(g, STROKE_KNOWN, "stroke", cx)
                s
            }
            "ADBE Vector Graphic - G-Fill", "ADBE Vector Graphic - G-Stroke" -> {
                val fill = matchName == "ADBE Vector Graphic - G-Fill"
                val s = if (fill) {
                    shapeGradientFill(uids, false, 0.0 to 0.0, 100.0 to 0.0)
                } else {
                    shapeGradientStroke(uids, false, 0.0 to 0.0, 100.0 to 0.0, 2.0)
                }
                set(s, "type", g, "ADBE Vector Grad Type", AepEffectKeys.Kind.Popup(2), cx)
                set(s, "start", g, "ADBE Vector Grad Start Pt", AepEffectKeys.Kind.Vec2, cx)
                set(s, "end", g, "ADBE Vector Grad End Pt", AepEffectKeys.Kind.Vec2, cx)
                if (fill) {
                    set(s, "opacity", g, "ADBE Vector Fill Opacity", AepEffectKeys.Kind.Number(1.0), cx)
                    set(s, "rule", g, "ADBE Vector Fill Rule", AepEffectKeys.Kind.Popup(2), cx)
                    paintSwitches(g, cx)
                } else {
                    strokeCommon(s, g, cx)
                }
                // Highlight length and angle are stored, but only their default 0 is sampled.
                notZero(g, "ADBE Vector Grad HiLite Length", "gradient highlight length", cx)
                notZero(g, "ADBE Vector Grad HiLite Angle", "gradient highlight angle", cx)
                if (g.get("ADBE Vector Grad Colors") != null) {
                    cx.col.lose(
                        AepImportReport.LossCategory.ShapeLayers,
                        "${cx.what}: the colour stops of a gradient are not decoded, white to black used"
                    )
                }
                val known: ArrayList<String> = arrayListOf(
                    "ADBE Vector Grad Type",
                    "ADBE Vector Grad Start Pt",
                    "ADBE Vector Grad End Pt",
                    "ADBE Vector Grad HiLite Length",
                    "ADBE Vector Grad HiLite Angle",
                    "ADBE Vector Grad Colors"
                )
                if (fill) {
                    known.addAll(
                        listOf(
                            "ADBE Vector Fill Opacity",
                            "ADBE Vector Fill Rule",
                            "ADBE Vector Blend Mode",
                            "ADBE Vector Composite Order"
                        )
                    )
                } else {
                    known.addAll(STROKE_KNOWN.drop(1))
                }
                unknown(g, known, if (fill) "gradient fill" else "gradient stroke", cx)
                s
            }
            "ADBE Vector Filter - Trim" -> {
                val t = shapeTrim(uids, 0.0, 100.0, 0.0)
                set(t, "start", g, "ADBE Vector Trim Start", AepEffectKeys.Kind.Number(1.0), cx)
                set(t, "end", g, "ADBE Vector Trim End", AepEffectKeys.Kind.Number(1.0), cx)
                set(t, "offset", g, "ADBE Vector Trim Offset", AepEffectKeys.Kind.Number(1.0), cx)
                set(t, "mode", g, "ADBE Vector Trim Type", AepEffectKeys.Kind.Popup(2), cx)
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Trim Start",
                        "ADBE Vector Trim End",
                        "ADBE Vector Trim Offset",
                        "ADBE Vector Trim Type"
                    ),
                    "trim paths",
                    cx
                )
                t
            }
            "ADBE Vector Filter - RC" -> {
                val r = shapeRound(uids)
                set(r, "radius", g, "ADBE Vector RoundCorner Radius", AepEffectKeys.Kind.Number(1.0), cx)
                unknown(g, listOf("ADBE Vector RoundCorner Radius"), "round corners", cx)
                r
            }
            // Merge Paths: Mode 1-based over Merge, Add, Subtract, Intersect, Exclude
            // Intersections, EffectCraft's order.
            "ADBE Vector Filter - Merge" -> {
                val m = shapeMerge(uids)
                set(m, "mode", g, "ADBE Vector Merge Type", AepEffectKeys.Kind.Popup(5), cx)
                unknown(g, listOf("ADBE Vector Merge Type"), "merge paths", cx)
                m
            }
            // Offset Paths: Line Join 1-based (Miter, Round, Bevel); without a record Copy
            // Offset is 1. After Effects offsets copy i (0-based) by Amount x (i + Copy Offset),
            // EffectCraft by Amount x (i + 1 + copyOffset), so copyOffset = Copy Offset - 1
            // (the default 1 is 0).
            "ADBE Vector Filter - Offset" -> {
                val o = shapeOffset(uids)
                AepEffectImport.propMut(o, "copyOffset")?.put("value", scalar(1.0))
                set(o, "amount", g, "ADBE Vector Offset Amount", AepEffectKeys.Kind.Number(1.0), cx)
                set(o, "join", g, "ADBE Vector Offset Line Join", AepEffectKeys.Kind.Popup(3), cx)
                set(o, "miter", g, "ADBE Vector Offset Miter Limit", AepEffectKeys.Kind.Number(1.0), cx)
                set(o, "copies", g, "ADBE Vector Offset Copies", AepEffectKeys.Kind.Number(1.0), cx)
                set(o, "copyOffset", g, "ADBE Vector Offset Copy Offset", AepEffectKeys.Kind.Number(1.0), cx)
                shiftCopyOffset(AepEffectImport.propMut(o, "copyOffset"), cx)
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Offset Amount",
                        "ADBE Vector Offset Line Join",
                        "ADBE Vector Offset Miter Limit",
                        "ADBE Vector Offset Copies",
                        "ADBE Vector Offset Copy Offset"
                    ),
                    "offset paths",
                    cx
                )
                o
            }
            // Repeater: without records 1 copy, offset 0, Composite Below, an identity transform.
            "ADBE Vector Filter - Repeater" -> {
                val r = shapeRepeater(uids, 1.0, 0.0 to 0.0)
                set(r, "copies", g, "ADBE Vector Repeater Copies", AepEffectKeys.Kind.Number(1.0), cx)
                set(r, "offset", g, "ADBE Vector Repeater Offset", AepEffectKeys.Kind.Number(1.0), cx)
                set(r, "composite", g, "ADBE Vector Repeater Order", AepEffectKeys.Kind.Popup(2), cx)
                val transformNode = g.get("ADBE Vector Repeater Transform")
                if (transformNode is AepTypedView.PropNode.Group) {
                    val t = transformNode.group
                    val tr = AepEffectImport.propMut(r, "transform")
                    if (tr != null) {
                        for ((m, id, kind) in listOf(
                            Triple("ADBE Vector Repeater Anchor", "anchor", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Repeater Position", "position", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Repeater Scale", "scale", AepEffectKeys.Kind.Vec2),
                            Triple("ADBE Vector Repeater Rotation", "rotation", AepEffectKeys.Kind.Number(1.0)),
                            Triple("ADBE Vector Repeater Opacity 1", "startOpacity", AepEffectKeys.Kind.Number(1.0)),
                            Triple("ADBE Vector Repeater Opacity 2", "endOpacity", AepEffectKeys.Kind.Number(1.0))
                        )) {
                            set(tr, id, t, m, kind, cx)
                        }
                        unknown(
                            t,
                            listOf(
                                "ADBE Vector Repeater Anchor",
                                "ADBE Vector Repeater Position",
                                "ADBE Vector Repeater Scale",
                                "ADBE Vector Repeater Rotation",
                                "ADBE Vector Repeater Opacity 1",
                                "ADBE Vector Repeater Opacity 2"
                            ),
                            "repeater transform",
                            cx
                        )
                    }
                }
                unknown(
                    g,
                    listOf(
                        "ADBE Vector Repeater Copies",
                        "ADBE Vector Repeater Offset",
                        "ADBE Vector Repeater Order",
                        "ADBE Vector Repeater Transform"
                    ),
                    "repeater",
                    cx
                )
                r
            }
            else -> {
                cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: shape item $matchName not imported"
                )
                return null
            }
        }
        cx.col.imported.shapeItems += 1
        return named(built, g)
    }

    private val STROKE_KNOWN: List<String> = listOf(
        "ADBE Vector Stroke Color",
        "ADBE Vector Stroke Opacity",
        "ADBE Vector Stroke Width",
        "ADBE Vector Stroke Line Cap",
        "ADBE Vector Stroke Line Join",
        "ADBE Vector Stroke Miter Limit",
        "ADBE Vector Stroke Dashes",
        "ADBE Vector Stroke Taper",
        "ADBE Vector Stroke Wave",
        "ADBE Vector Blend Mode",
        "ADBE Vector Composite Order"
    )

    /** Opacity, width, cap, join, miter limit of a stroke or gradient stroke; dashes, taper
     * and wave are reported when they hold values. */
    private fun strokeCommon(s: JSONObject, g: AepTypedView.PropGroup, cx: Cx) {
        set(s, "opacity", g, "ADBE Vector Stroke Opacity", AepEffectKeys.Kind.Number(1.0), cx)
        set(s, "width", g, "ADBE Vector Stroke Width", AepEffectKeys.Kind.Number(1.0), cx)
        // Butt 1 and round 2 are sampled; the third item (projecting cap, bevel join) is not.
        for ((m, id, what) in listOf(
            Triple("ADBE Vector Stroke Line Cap", "cap", "line cap"),
            Triple("ADBE Vector Stroke Line Join", "join", "line join")
        )) {
            val node = (g.get(m) as? AepTypedView.PropNode.Property)?.property
            val v = node?.staticValue()?.firstOrNull()
            if (v != null && v.roundToLong() == 3L) {
                cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: $what 3 is not sampled, imported as the third item"
                )
            }
            set(s, id, g, m, AepEffectKeys.Kind.Popup(3), cx)
        }
        set(s, "miter", g, "ADBE Vector Stroke Miter Limit", AepEffectKeys.Kind.Number(1.0), cx)
        for ((m, what) in listOf(
            "ADBE Vector Stroke Dashes" to "dashes",
            "ADBE Vector Stroke Taper" to "taper",
            "ADBE Vector Stroke Wave" to "wave"
        )) {
            val sub = (g.get(m) as? AepTypedView.PropNode.Group)?.group
            if (sub != null && sub.entries().any { e ->
                when (val n = e.node) {
                    is AepTypedView.PropNode.Property -> stored(n.property)
                    else -> true
                }
            }) {
                cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: stroke $what not imported"
                )
            }
        }
        paintSwitches(g, cx)
    }

    /** The blend mode and composite order of a fill or stroke (only the defaults, 1, are sampled). */
    private fun paintSwitches(g: AepTypedView.PropGroup, cx: Cx) {
        notDefault(g, "ADBE Vector Blend Mode", "paint blend mode", cx)
        notDefault(g, "ADBE Vector Composite Order", "composite order", cx)
    }

    private fun star(g: AepTypedView.PropGroup, cx: Cx, uids: Uids): JSONObject {
        // Type 1 star, 2 polygon; EffectCraft's polygon has no inner radius and roundness.
        val polygon = ((g.get("ADBE Vector Star Type") as? AepTypedView.PropNode.Property)?.property
            ?.staticValue()?.firstOrNull()?.roundToLong() == 2L)
        val s = shapeStar(uids, !polygon, 5.0, 0.0 to 0.0, 100.0, 50.0)
        set(s, "type", g, "ADBE Vector Star Type", AepEffectKeys.Kind.Popup(2), cx)
        set(s, "points", g, "ADBE Vector Star Points", AepEffectKeys.Kind.Number(1.0), cx)
        set(s, "position", g, "ADBE Vector Star Position", AepEffectKeys.Kind.Vec2, cx)
        set(s, "rotation", g, "ADBE Vector Star Rotation", AepEffectKeys.Kind.Number(1.0), cx)
        set(s, "outerRadius", g, "ADBE Vector Star Outer Radius", AepEffectKeys.Kind.Number(1.0), cx)
        set(s, "outerRoundness", g, "ADBE Vector Star Outer Roundess", AepEffectKeys.Kind.Number(1.0), cx)
        if (!polygon) {
            set(s, "innerRadius", g, "ADBE Vector Star Inner Radius", AepEffectKeys.Kind.Number(1.0), cx)
            set(s, "innerRoundness", g, "ADBE Vector Star Inner Roundess", AepEffectKeys.Kind.Number(1.0), cx)
        }
        notDefault(g, "ADBE Vector Shape Direction", "shape direction", cx)
        unknown(
            g,
            listOf(
                "ADBE Vector Star Type",
                "ADBE Vector Star Points",
                "ADBE Vector Star Position",
                "ADBE Vector Star Rotation",
                "ADBE Vector Star Inner Radius",
                "ADBE Vector Star Outer Radius",
                "ADBE Vector Star Inner Roundess",
                "ADBE Vector Star Outer Roundess",
                "ADBE Vector Shape Direction"
            ),
            "polystar",
            cx
        )
        return s
    }

    /** A bezier path (`ADBE Vector Shape - Group`): the path in `om-s/omks/shap`, its box
     * in pixels relative to the layer origin. */
    private fun path(g: AepTypedView.PropGroup, cx: Cx, uids: Uids): JSONObject? {
        val oms = (g.get("ADBE Vector Shape") as? AepTypedView.PropNode.Other)?.chunk
            ?: run {
                cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: a shape path without a path not imported"
                )
                return null
            }
        val shapes: List<AepMasks.ShapePath?> = oms.childList("omks")?.children().orEmpty()
            .filter { it.isList("shap") }
            .map { AepMasks.ShapePath.fromChunk(it) }
        if (shapes.isEmpty()) {
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: a shape path not imported, it has no path"
            )
            return null
        }
        val first = shapes[0]
        if (first == null) {
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: a shape path not imported, its path is unreadable"
            )
            return null
        }
        val decoded = first.toJson(1.0 to 1.0)
        if (decoded !is AepMasks.DecodedPath.Ok) {
            val why = (decoded as? AepMasks.DecodedPath.Err)?.why ?: "its path is unreadable"
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: a shape path not imported, $why"
            )
            return null
        }
        val p = shapePath(uids, decoded.json)
        val node = oms.childList("tdbs")?.let { AepTypedView.Property.fromChunk(it) }
        if (node != null) {
            val prop = AepEffectImport.propMut(p, "path")
            if (prop != null) {
                if (node.isAnimated()) {
                    AepMasks.importPathKeys(shapes, node, cx.what, 1.0 to 1.0, cx.timescale, prop, cx.col)
                }
                importExpression(node, prop, cx.col)
            }
        }
        notDefault(g, "ADBE Vector Shape Direction", "shape direction", cx)
        unknown(g, listOf("ADBE Vector Shape", "ADBE Vector Shape Direction"), "path", cx)
        return p
    }

    /**
     * Sets property `id` of `target` from the stored property `matchName` of `g`
     * (static value or keys, and the expression); absent means the default, which
     * `target` already holds.
     */
    private fun set(
        target: JSONObject,
        id: String,
        g: AepTypedView.PropGroup,
        matchName: String,
        kind: AepEffectKeys.Kind,
        cx: Cx
    ) {
        val node = (g.get(matchName) as? AepTypedView.PropNode.Property)?.property ?: return
        val prop = AepEffectImport.propMut(target, id) ?: return
        if (node.isAnimated()) {
            val imported = AepEffectKeys.importKeys(node, kind, cx.what, matchName, cx.timescale, cx.col)
            if (imported != null) {
                prop.put("value", imported.firstValue)
                prop.put("keys", imported.keys)
            }
        } else {
            val raw = node.staticValue()
            if (raw != null) {
                val value = kind.value(raw)
                if (value != null) {
                    prop.put("value", value)
                } else {
                    cx.col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "${cx.what}: $matchName holds a value that is not valid, default used"
                    )
                }
            }
        }
        importExpression(node, prop, cx.col)
    }

    /** After Effects' Copy Offset (`i + Copy Offset`, default 1) as EffectCraft's
     * (`i + 1 + copyOffset`, default 0): the value and every key minus 1. An expression
     * gives After Effects' number, so it is kept but reported. */
    private fun shiftCopyOffset(p: JSONObject?, cx: Cx) {
        if (p == null) return
        fun shift(v: JSONObject) {
            if (v.optString("t") == "Scalar") v.put("v", v.optDouble("v") - 1.0)
        }
        p.optJSONObject("value")?.let { shift(it) }
        val keys = p.optJSONArray("keys")
        if (keys != null) {
            for (i in 0 until keys.length()) {
                keys.optJSONObject(i)?.optJSONObject("value")?.let { shift(it) }
            }
        }
        if (p.has("expr")) {
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: the expression on Offset Paths' Copy Offset gives After Effects' value " +
                    "(EffectCraft's is 1 less), kept unchanged"
            )
        }
    }

    /** Reports a property whose only sampled value is the default 1. */
    private fun notDefault(g: AepTypedView.PropGroup, matchName: String, what: String, cx: Cx) {
        val p = (g.get(matchName) as? AepTypedView.PropNode.Property)?.property ?: return
        val v = p.staticValue()?.firstOrNull()
        if (p.isAnimated() || p.expression() != null || (v != null && v != 1.0)) {
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: $what not imported"
            )
        }
    }

    /** Reports a property whose only sampled value is 0. */
    private fun notZero(g: AepTypedView.PropGroup, matchName: String, what: String, cx: Cx) {
        val p = (g.get(matchName) as? AepTypedView.PropNode.Property)?.property ?: return
        val v = p.staticValue()?.firstOrNull()
        if (p.isAnimated() || p.expression() != null || (v != null && v != 0.0)) {
            cx.col.lose(
                AepImportReport.LossCategory.ShapeLayers,
                "${cx.what}: $what not imported"
            )
        }
    }

    /** Reports stored entries of `g` that are not in `known`. */
    private fun unknown(
        g: AepTypedView.PropGroup,
        known: List<String>,
        what: String,
        cx: Cx
    ) {
        for (e in g.entries()) {
            if (e.matchName in known) continue
            val holds = when (val node = e.node) {
                is AepTypedView.PropNode.Property -> stored(node.property)
                else -> true
            }
            if (holds) {
                cx.col.lose(
                    AepImportReport.LossCategory.ShapeLayers,
                    "${cx.what}: $what entry ${e.matchName} not imported"
                )
            }
        }
    }

    /** Attaches the expression of `node` (source text and enable switch) to `prop`. */
    private fun importExpression(
        node: AepTypedView.Property,
        prop: JSONObject,
        col: AepImportReport.Collector
    ) {
        val enabled = node.expressionEnabled() ?: return
        val text = node.expression() ?: return
        prop.put("expr", JSONObject().put("text", text).put("enabled", enabled))
        col.imported.expressions += 1
    }
}
