package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.json.JSONObject

/**
 * Text animators, mirroring the official importer's text_anim.rs: the animator
 * properties and range selectors of a text layer into its `text/animators`
 * group.
 *
 * Every value is stored as After Effects shows it: positions in pixels, Start /
 * End / Offset, Amount, Smoothness and the eases in percent, angles in degrees,
 * colours as ARGB 0..255, popups 1-based (in EffectCraft's item order). A
 * property without a record keeps After Effects' default.
 *
 * Not imported, and reported in TextLayers: wiggly and expression selectors,
 * animator properties other than the ones mapped below, and a Tracking Type
 * other than the default.
 */
object AepTextAnimImport {

    /** More animators or selectors than this on one layer are not imported. */
    private const val MAX_ITEMS = 1024

    /** One animator property: stored match name, kind, canonical id, value kind. */
    private data class PropRow(
        val matchName: String,
        val kind: String,
        val id: String,
        val valueKind: AepEffectKeys.Kind,
        /** The third component when the stored value has only two (Position 0, Scale 100). */
        val padThird: Double? = null
    )

    private val PROPS: List<PropRow> = listOf(
        PropRow("ADBE Text Anchor Point 3D", "anchor", "anchor", AepEffectKeys.Kind.Vec3, 0.0),
        PropRow("ADBE Text Position 3D", "position", "position", AepEffectKeys.Kind.Vec3, 0.0),
        PropRow("ADBE Text Scale 3D", "scale", "scale", AepEffectKeys.Kind.Vec3, 100.0),
        PropRow("ADBE Text Skew", "skew", "skew", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Skew Axis", "skew", "skewAxis", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Rotation", "rotation", "rotation", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Opacity", "opacity", "opacity", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Fill Color", "fillColor", "fillColor", AepEffectKeys.Kind.Color),
        PropRow("ADBE Text Stroke Color", "strokeColor", "strokeColor", AepEffectKeys.Kind.Color),
        PropRow("ADBE Text Stroke Width", "strokeWidth", "strokeWidth", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Tracking Amount", "tracking", "tracking", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Line Spacing", "lineSpacing", "lineSpacing", AepEffectKeys.Kind.Vec2),
        PropRow("ADBE Text Blur", "blur", "blur", AepEffectKeys.Kind.Vec2),
        PropRow("ADBE Text Character Offset", "characterOffset", "characterOffset", AepEffectKeys.Kind.Number(1.0)),
        PropRow("ADBE Text Character Range", "characterOffset", "characterRange", AepEffectKeys.Kind.Popup(2))
    )

    /** One range or advanced selector property: stored match name, id, value kind. */
    private data class RangeRow(val matchName: String, val id: String, val valueKind: AepEffectKeys.Kind)

    private val RANGE: List<RangeRow> = listOf(
        RangeRow("ADBE Text Percent Start", "start", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Percent End", "end", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Percent Offset", "offset", AepEffectKeys.Kind.Number(1.0))
    )

    private val RANGE_INDEX: List<RangeRow> = listOf(
        RangeRow("ADBE Text Index Start", "start", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Index End", "end", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Index Offset", "offset", AepEffectKeys.Kind.Number(1.0))
    )

    private val ADVANCED: List<RangeRow> = listOf(
        RangeRow("ADBE Text Range Units", "units", AepEffectKeys.Kind.Popup(2)),
        RangeRow("ADBE Text Range Type2", "basedOn", AepEffectKeys.Kind.Popup(4)),
        RangeRow("ADBE Text Selector Mode", "mode", AepEffectKeys.Kind.Popup(6)),
        RangeRow("ADBE Text Selector Max Amount", "amount", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Range Shape", "shape", AepEffectKeys.Kind.Popup(6)),
        RangeRow("ADBE Text Selector Smoothness", "smoothness", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Levels Max Ease", "easeHigh", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Levels Min Ease", "easeLow", AepEffectKeys.Kind.Number(1.0)),
        RangeRow("ADBE Text Randomize Order", "randomize", AepEffectKeys.Kind.Bool),
        RangeRow("ADBE Text Random Seed", "randomSeed", AepEffectKeys.Kind.Number(1.0))
    )

    /**
     * Imports the animators of a text layer (its `ADBE Text Animators` group)
     * into animator groups for the patch step (uids in patch space).
     */
    fun importAnimators(
        animatorsGroup: AepTypedView.PropGroup?,
        what: String,
        timescale: Long,
        allocBlock: (Int) -> Long,
        col: AepImportReport.Collector
    ): List<JSONObject> {
        if (animatorsGroup == null) return emptyList()
        val b = Builder(allocBlock)
        val out = ArrayList<JSONObject>()
        for ((index, entry) in animatorsGroup.entries().withIndex()) {
            if (index >= MAX_ITEMS) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: more than $MAX_ITEMS text animators, the rest not imported"
                )
                break
            }
            val group = (entry.node as? AepTypedView.PropNode.Group)?.group
            if (entry.matchName != "ADBE Text Animator" || group == null) {
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$what: text animator entry ${entry.matchName} not imported"
                )
                continue
            }
            animator(group, index, b, what, timescale, col)?.let {
                out.add(it)
                col.imported.textAnimators++
            }
        }
        return out
    }

    private fun animator(
        g: AepTypedView.PropGroup,
        index: Int,
        b: Builder,
        what: String,
        timescale: Long,
        col: AepImportReport.Collector
    ): JSONObject {
        val name = g.userName()?.takeIf { it.isNotEmpty() } ?: "Animator ${index + 1}"
        val here = "$what: animator '$name'"
        // The properties, created in the order of their `Animate` entries.
        val kinds = ArrayList<String>()
        val aepProps = (g.get("ADBE Text Animator Properties") as? AepTypedView.PropNode.Group)?.group
        if (aepProps != null) {
            for (e in aepProps.entries()) {
                val row = PROPS.firstOrNull { it.matchName == e.matchName }
                when {
                    row != null && row.kind !in kinds -> kinds.add(row.kind)
                    row != null -> Unit
                    e.matchName == "ADBE Text Track Type" ||
                        e.matchName == "ADBE Text Character Change Type" -> Unit
                    else -> col.lose(
                        AepImportReport.LossCategory.TextLayers,
                        "$here: property ${e.matchName} not imported"
                    )
                }
            }
        }
        val propertiesGroup = b.group(
            "properties", "Properties", kinds.flatMap { textAnimProps(b, it) }
        )
        if (aepProps != null) {
            for (row in PROPS) {
                set(propertiesGroup, row.id, aepProps, row.matchName, row.valueKind, row.padThird, here, timescale, col)
            }
            // Tracking Type: only the default (Before & After, 1) is sampled.
            checkDefault(aepProps, "ADBE Text Track Type", "Tracking Type", "Before & After used", here, col)
            // Character Alignment: only the value After Effects adds with Character
            // Offset (1) is sampled; it stays EffectCraft's default.
            checkDefault(aepProps, "ADBE Text Character Change Type", "Character Alignment", "the default used", here, col)
        }
        // The selectors replace the default one.
        val selectorChildren = ArrayList<JSONObject>()
        val selectors = (g.get("ADBE Text Selectors") as? AepTypedView.PropNode.Group)?.group
        if (selectors != null) {
            for ((i, e) in selectors.entries().withIndex()) {
                if (i >= MAX_ITEMS) {
                    col.lose(
                        AepImportReport.LossCategory.TextLayers,
                        "$here: more than $MAX_ITEMS selectors, the rest not imported"
                    )
                    break
                }
                val sg = (e.node as? AepTypedView.PropNode.Group)?.group
                if (e.matchName != "ADBE Text Selector" || sg == null) {
                    col.lose(
                        AepImportReport.LossCategory.TextLayers,
                        "$here: selector ${e.matchName} not imported"
                    )
                    continue
                }
                selectorChildren.add(rangeSelectorFrom(sg, i, here, b, timescale, col))
            }
        } else {
            selectorChildren.add(rangeSelector(b, "Range Selector 1"))
        }
        val selectorsGroup = b.group("selectors", "Selectors", selectorChildren)
        return b.group("animator", name, listOf(selectorsGroup, propertiesGroup), kind = "Indexed")
    }

    private fun rangeSelectorFrom(
        g: AepTypedView.PropGroup,
        index: Int,
        animator: String,
        b: Builder,
        timescale: Long,
        col: AepImportReport.Collector
    ): JSONObject {
        val name = g.userName()?.takeIf { it.isNotEmpty() } ?: "Range Selector ${index + 1}"
        val here = "$animator: '$name'"
        val r = rangeSelector(b, name)
        val advanced = (g.get("ADBE Text Range Advanced") as? AepTypedView.PropNode.Group)?.group
        // With Units = Index (2.0) the selector's Index Start / End / Offset hold
        // the range in characters; EffectCraft's Start / End / Offset are characters then too.
        val indexUnits = advanced
            ?.let { (it.get("ADBE Text Range Units") as? AepTypedView.PropNode.Property)?.property?.staticValue() }
            ?.firstOrNull() == 2.0
        for (row in if (indexUnits) RANGE_INDEX else RANGE) {
            set(r, row.id, g, row.matchName, row.valueKind, null, here, timescale, col)
        }
        if (advanced != null) {
            val target = childOf(r, "advanced") ?: return r
            for (row in ADVANCED) {
                set(target, row.id, advanced, row.matchName, row.valueKind, null, here, timescale, col)
            }
        }
        return r
    }

    /** Sets property `id` of `target` from the stored property `matchName` of `g`. */
    private fun set(
        target: JSONObject,
        id: String,
        g: AepTypedView.PropGroup,
        matchName: String,
        kind: AepEffectKeys.Kind,
        padThird: Double?,
        here: String,
        timescale: Long,
        col: AepImportReport.Collector
    ) {
        val node = (g.get(matchName) as? AepTypedView.PropNode.Property)?.property ?: return
        val prop = childOf(target, id) ?: return
        setProp(node, prop, kind, padThird, matchName, here, timescale, col)
    }

    private fun setProp(
        node: AepTypedView.Property,
        prop: JSONObject,
        kind: AepEffectKeys.Kind,
        padThird: Double?,
        matchName: String,
        here: String,
        timescale: Long,
        col: AepImportReport.Collector
    ) {
        if (node.isAnimated()) {
            if (kind is AepEffectKeys.Kind.Bool) {
                // Keys on a checkbox are not sampled by the corpus.
                col.lose(
                    AepImportReport.LossCategory.TextLayers,
                    "$here: the keyframes of $matchName are not imported (not decoded), default used"
                )
                return
            }
            val imported = AepEffectKeys.importKeys(node, kind, here, matchName, timescale, col)
            if (imported == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$here: $matchName has no readable keyframe, default used"
                )
                return
            }
            prop.put("keys", imported.keys)
            prop.put("value", imported.firstValue)
        } else {
            val raw = node.staticValue()
            if (raw != null) {
                val padded = if (padThird != null && raw.size == 2) raw + padThird else raw
                val converted = kind.value(padded)
                if (converted != null) {
                    prop.put("value", converted)
                } else {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: $matchName holds a value that is not valid, default used"
                    )
                }
            }
        }
        val enabled = node.expressionEnabled()
        val text = node.expression()
        if (enabled != null && text != null) {
            prop.put("expr", JSONObject().put("text", text).put("enabled", enabled))
            col.imported.expressions += 1
        }
    }

    /** Only the default value (1) of a companion property is sampled. */
    private fun checkDefault(
        props: AepTypedView.PropGroup,
        matchName: String,
        label: String,
        fallback: String,
        here: String,
        col: AepImportReport.Collector
    ) {
        val node = (props.get(matchName) as? AepTypedView.PropNode.Property)?.property ?: return
        val stored = node.isAnimated() ||
            node.staticValue()?.firstOrNull()?.let { it != 1.0 } == true
        if (stored) {
            col.lose(
                AepImportReport.LossCategory.TextLayers,
                "$here: $label is not imported, $fallback"
            )
        }
    }

    private fun childOf(group: JSONObject, match: String): JSONObject? {
        val children = group.optJSONArray("children") ?: return null
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            if (child.optString("match") == match) return child
        }
        return null
    }

    /** A range selector skeleton, mirroring build::range_selector. */
    private fun rangeSelector(b: Builder, name: String): JSONObject {
        val advanced = b.group(
            "advanced", "Advanced", listOf(
                b.prop("units", "Units", b.enum(0), "Popup", uiExtras = b.popup(listOf("Percentage", "Index")), staticOnly = true),
                b.prop("basedOn", "Based On", b.enum(0), "Popup", uiExtras = b.popup(listOf("Characters", "Characters Excluding Spaces", "Words", "Lines")), staticOnly = true),
                b.prop("mode", "Mode", b.enum(0), "Popup", uiExtras = b.popup(listOf("Add", "Subtract", "Intersect", "Min", "Max", "Difference")), staticOnly = true),
                b.prop("amount", "Amount", b.scalar(100.0), "Slider", uiExtras = b.slider(-100.0, 100.0, -100.0, 100.0, 0)),
                b.prop("shape", "Shape", b.enum(0), "Popup", uiExtras = b.popup(listOf("Square", "Ramp Up", "Ramp Down", "Triangle", "Round", "Smooth")), staticOnly = true),
                b.prop("smoothness", "Smoothness", b.scalar(100.0), "Slider", uiExtras = b.slider(0.0, 100.0, 0.0, 100.0, 0)),
                b.prop("easeHigh", "Ease High", b.scalar(0.0), "Slider", uiExtras = b.slider(-100.0, 100.0, -100.0, 100.0, 0)),
                b.prop("easeLow", "Ease Low", b.scalar(0.0), "Slider", uiExtras = b.slider(-100.0, 100.0, -100.0, 100.0, 0)),
                b.prop("randomize", "Randomize Order", b.bool(false), "Checkbox", staticOnly = true),
                b.prop("randomSeed", "Random Seed", b.scalar(0.0), "Number")
            )
        )
        return b.group(
            "rangeSelector", name, listOf(
                b.prop("start", "Start", b.scalar(0.0), "Percent"),
                b.prop("end", "End", b.scalar(100.0), "Percent"),
                b.prop("offset", "Offset", b.scalar(0.0), "Percent"),
                advanced
            ), kind = "Indexed"
        )
    }

    /** The properties one Animate entry adds (build::text_anim_props, three_d false). */
    private fun textAnimProps(b: Builder, kind: String): List<JSONObject> = when (kind) {
        "anchor" -> listOf(
            b.prop("anchor", "Anchor Point", b.vec3(0.0, 0.0, 0.0), "Point", spatial = true, shownDims = 2)
        )
        "position" -> listOf(
            b.prop("position", "Position", b.vec3(0.0, 0.0, 0.0), "Point", spatial = true, shownDims = 2)
        )
        "scale" -> listOf(
            b.prop("scale", "Scale", b.vec3(100.0, 100.0, 100.0), "Percent", shownDims = 2)
        )
        "skew" -> listOf(
            b.prop("skew", "Skew", b.scalar(0.0), "Slider", uiExtras = b.slider(-85.0, 85.0, -70.0, 70.0, 1)),
            b.prop("skewAxis", "Skew Axis", b.scalar(0.0), "Angle")
        )
        "rotation" -> listOf(
            b.prop("rotation", "Rotation", b.scalar(0.0), "Angle")
        )
        "opacity" -> listOf(
            b.prop("opacity", "Opacity", b.scalar(100.0), "Slider", uiExtras = b.slider(0.0, 100.0, 0.0, 100.0, 0))
        )
        "tracking" -> listOf(
            b.prop("trackingType", "Tracking Type", b.enum(0), "Popup", uiExtras = b.popup(listOf("Before & After", "Before", "After")), staticOnly = true),
            b.prop("tracking", "Tracking Amount", b.scalar(0.0), "Number")
        )
        "lineSpacing" -> listOf(
            b.prop("lineSpacing", "Line Spacing", b.vec2(0.0, 0.0), "Number")
        )
        "blur" -> listOf(
            b.prop("blur", "Blur", b.vec2(0.0, 0.0), "Number")
        )
        "fillColor" -> listOf(
            b.prop("fillColor", "Fill Color", b.color(listOf(1.0, 0.0, 0.0, 1.0)), "Color")
        )
        "strokeColor" -> listOf(
            b.prop("strokeColor", "Stroke Color", b.color(listOf(1.0, 0.0, 0.0, 1.0)), "Color")
        )
        "strokeWidth" -> listOf(
            b.prop("strokeWidth", "Stroke Width", b.scalar(0.0), "Pixels")
        )
        "characterOffset" -> listOf(
            b.prop("characterAlignment", "Character Alignment", b.enum(1), "Popup", uiExtras = b.popup(listOf("Left or Top", "Center", "Right or Bottom", "Adjust Kerning")), staticOnly = true),
            b.prop("characterRange", "Character Range", b.enum(0), "Popup", uiExtras = b.popup(listOf("Preserve Case & Digits", "Full Unicode")), staticOnly = true),
            b.prop("characterOffset", "Character Offset", b.scalar(0.0), "Number")
        )
        else -> emptyList()
    }

    /** Canonical prop and group nodes, in the converter's shape, with patch-space uids. */
    private class Builder(private val allocBlock: (Int) -> Long) {
        fun scalar(v: Double): JSONObject = JSONObject().put("t", "Scalar").put("v", v)
        fun vec2(x: Double, y: Double): JSONObject =
            JSONObject().put("t", "Vec2").put("v", JSONArray().put(x).put(y))
        fun vec3(x: Double, y: Double, z: Double): JSONObject =
            JSONObject().put("t", "Vec3").put("v", JSONArray().put(x).put(y).put(z))
        fun bool(v: Boolean): JSONObject = JSONObject().put("t", "Bool").put("v", v)
        fun enum(v: Int): JSONObject = JSONObject().put("t", "Enum").put("v", v)
        fun color(rgba: List<Double>): JSONObject {
            val v = JSONArray()
            for (c in rgba) v.put(c)
            return JSONObject().put("t", "Color").put("v", v)
        }

        fun slider(min: Double, max: Double, sliderMin: Double, sliderMax: Double, decimals: Int): JSONObject =
            JSONObject()
                .put("min", min).put("max", max)
                .put("slider_min", sliderMin).put("slider_max", sliderMax)
                .put("decimals", decimals)

        fun popup(options: List<String>): JSONObject =
            JSONObject().put("options", JSONArray(options))

        fun prop(
            match: String,
            name: String,
            value: JSONObject,
            uiKind: String,
            uiExtras: JSONObject? = null,
            spatial: Boolean = false,
            holdOnly: Boolean = false,
            threeDOnly: Boolean = false,
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
                .put("uid", allocBlock(1))
                .put("match", match)
                .put("name", name)
                .put("value", value)
                .put("keys", keys ?: JSONArray())
                .put("ui", ui)
                .put("spatial", spatial)
                .put("hold_only", holdOnly)
                .put("three_d_only", threeDOnly)
                .put("two_d_only", false)
                .put("shown_dims", shownDims)
                .put("static_only", staticOnly)
        }

        fun group(match: String, name: String, children: List<JSONObject>, kind: String = "Plain"): JSONObject =
            JSONObject()
                .put("node", "Group")
                .put("uid", allocBlock(1))
                .put("match", match)
                .put("name", name)
                .put("kind", JSONObject().put("kind", kind))
                .put("enabled", true)
                .put("children", JSONArray(children))
    }
}
