package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.AeInterchangeConverter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Layer masks: path shapes, modes, feather/opacity/expansion, with keyframes and
 * expressions, in EffectCraft's units.
 *
 * Port of `container/masks.rs` + `import/masks.rs` from the official EffectCraft
 * project (storytold/effectcraft, MIT OR Apache-2.0). Units: the path is stored
 * as `f32` points normalised inside a box that is itself normalised to the
 * **layer source** size, so vertices become layer pixels with the origin at the
 * layer's top left; a tangent is the handle minus the vertex. Feather and
 * expansion are pixels, opacity a fraction (percent here).
 */
object AepMasks {

    /** The most vertices one mask path may have. Larger paths are reported as
     * damaged data and the mask is not imported. */
    const val MAX_VERTICES = 65_536

    /** Mask colours cycle like After Effects' default mask colours. */
    val MASK_COLORS: List<List<Int>> = listOf(
        listOf(0xb1, 0xb4, 0x4f),
        listOf(0x4f, 0x8c, 0xc9),
        listOf(0xc9, 0x4f, 0x8c),
        listOf(0x4f, 0xc9, 0x7a),
        listOf(0xc9, 0x8c, 0x4f),
        listOf(0x8c, 0x4f, 0xc9),
        listOf(0x4f, 0xc9, 0xc9),
        listOf(0xc9, 0x4f, 0x4f)
    )

    /** Where a stored value came from. */
    enum class ValueSource { Default, Stored, Keyframed, Unreadable }

    /** A stored value with its source. */
    data class Tracked<T>(val value: T, val source: ValueSource)

    /** The mask mode (`mkif` `u32` at byte 4). */
    sealed class MaskMode(val jsonName: String) {
        object None : MaskMode("None")
        object Add : MaskMode("Add")
        object Subtract : MaskMode("Subtract")
        object Intersect : MaskMode("Intersect")
        object Lighten : MaskMode("Lighten")
        object Darken : MaskMode("Darken")
        object Difference : MaskMode("Difference")
        class Other(val code: Long) : MaskMode("Add")

        companion object {
            fun fromId(id: Long): MaskMode = when (id) {
                0L -> None
                1L -> Add
                2L -> Subtract
                3L -> Intersect
                4L -> Lighten
                5L -> Darken
                6L -> Difference
                else -> Other(id)
            }
        }
    }

    /** A vertex of a bezier path with its two control points as absolute positions. */
    data class PathVertex(
        val position: List<Double>,
        val inHandle: List<Double>,
        val outHandle: List<Double>
    ) {
        /** The in tangent: the in handle minus the vertex. */
        fun inTangent(): List<Double> =
            listOf(inHandle[0] - position[0], inHandle[1] - position[1])

        /** The out tangent: the out handle minus the vertex. */
        fun outTangent(): List<Double> =
            listOf(outHandle[0] - position[0], outHandle[1] - position[1])
    }

    /** A decoded mask path, or why it cannot be used. */
    sealed class DecodedPath {
        class Ok(val json: JSONObject) : DecodedPath()
        class Err(val why: String) : DecodedPath()
    }

    /** One path shape (`LIST shap`): a bounding box and the control points normalised
     * inside it. */
    class ShapePath(
        /** Left, top, right, bottom. */
        val bbox: List<Float>,
        /** The byte at offset 20 of `shph` (`1` in every sample). */
        val closedFlag: Int,
        /** True for an open path: bit `0x08` of the last byte of the first `shph` word. */
        val open: Boolean,
        /** The normalised points as stored, three per vertex. */
        val points: List<Pair<Float, Float>>
    ) {
        companion object {
            /** Decodes a `LIST shap`; null when `shph` or the point list is malformed. */
            fun fromChunk(shap: AepContainer.Chunk): ShapePath? {
                val shph = shap.child("shph")?.bytes() ?: return null
                val bbox = listOf(
                    beF32(shph, 4) ?: return null,
                    beF32(shph, 8) ?: return null,
                    beF32(shph, 12) ?: return null,
                    beF32(shph, 16) ?: return null
                )
                val closedFlag = shph.getOrNull(20)?.toInt()?.and(0xFF) ?: return null
                val open = (shph.getOrNull(3)?.toInt()?.and(0xFF) ?: return null) and 0x08 != 0
                val list = AepTypedView.KeyList.fromChunk(shap.childList("list") ?: return null)
                    ?: return null
                if (list.header.recordSize != 8L) return null
                val points = ArrayList<Pair<Float, Float>>()
                for (r in list.records()) {
                    points.add((beF32(r, 0) ?: return null) to (beF32(r, 4) ?: return null))
                }
                return ShapePath(bbox, closedFlag, open, points)
            }
        }

        /** The number of vertices (`points / 3`). */
        fun vertexCount(): Int = points.size / 3

        /** The vertices with absolute control points: `box.left + u * (box.right -
         * box.left)` (and the same for y), multiplied by `scale` (the layer size in
         * pixels for a mask). */
        fun vertices(scale: Pair<Double, Double>): List<PathVertex> {
            val left = bbox[0].toDouble()
            val top = bbox[1].toDouble()
            val right = bbox[2].toDouble()
            val bottom = bbox[3].toDouble()
            fun abs(p: Pair<Float, Float>?): List<Double> {
                val u = p?.first?.toDouble() ?: 0.0
                val v = p?.second?.toDouble() ?: 0.0
                return listOf(
                    (left + u * (right - left)) * scale.first,
                    (top + v * (bottom - top)) * scale.second
                )
            }
            val n = vertexCount()
            val total = n * 3
            val out = ArrayList<PathVertex>(n)
            for (i in 0 until n) {
                val base = i * 3
                out.add(
                    PathVertex(
                        position = abs(points.getOrNull(base)),
                        outHandle = abs(points.getOrNull(base + 1)),
                        // The in handle of vertex i is the record before vertex i
                        // (the last one for vertex 0).
                        inHandle = abs(points.getOrNull((base + total - 1) % total.coerceAtLeast(1)))
                    )
                )
            }
            return out
        }

        /** The path in layer pixels as a canonical `Path` value, or why it cannot be used. */
        fun toJson(scale: Pair<Double, Double>): DecodedPath {
            if (points.size % 3 != 0) return DecodedPath.Err("its point count is not a multiple of three")
            val n = vertexCount()
            if (n == 0) return DecodedPath.Err("its path has no vertices")
            if (n > MAX_VERTICES) return DecodedPath.Err("its path has too many vertices")
            val vertices = vertices(scale)
            val ok = { p: List<Double> -> p.all { it.isFinite() && abs(it) <= AepEffectKeys.LIMIT } }
            val verts = JSONArray()
            val inTangents = JSONArray()
            val outTangents = JSONArray()
            for (v in vertices) {
                val (i, o) = v.inTangent() to v.outTangent()
                if (!(ok(v.position) && ok(i) && ok(o))) {
                    return DecodedPath.Err("its path holds a number that is not valid")
                }
                verts.put(JSONArray().put(v.position[0]).put(v.position[1]))
                inTangents.put(JSONArray().put(i[0]).put(i[1]))
                outTangents.put(JSONArray().put(o[0]).put(o[1]))
            }
            return DecodedPath.Ok(
                JSONObject()
                    .put("vertices", verts)
                    .put("in_tangents", inTangents)
                    .put("out_tangents", outTangents)
                    .put("closed", !open)
            )
        }
    }

    /** One mask of a layer. */
    class Mask(
        /** The mask name (`Mask 1`). */
        val name: String?,
        /** The mode, from this mask's `mkif`. */
        val mode: MaskMode?,
        /** The invert switch (`mkif` byte 0). */
        val inverted: Boolean?,
        /** The running mask id counter of the layer (`mkif` `u32` at byte 8). */
        val counter: Long?,
        /** Feather in pixels `[x, y]`; absent = `[0, 0]`. */
        val feather: Tracked<List<Double>>,
        /** Opacity in percent (stored as a fraction); absent = 100. */
        val opacityPercent: Tracked<Double>,
        /** Expansion in pixels; absent = 0. */
        val expansion: Tracked<Double>,
        /** True when the path has keyframes. */
        val pathAnimated: Boolean,
        /** The path keyframe times in seconds (empty for a static path). */
        val pathKeySeconds: List<Double>,
        /** The path shapes: one for a static path, one per keyframe otherwise. */
        val shapes: List<ShapePath>
    )

    /** One mask with the views of its records: the keyframes and expressions of the
     * mask properties and the path keyframes paired with their shapes. */
    class MaskAtom(
        val mask: Mask,
        /** The atom group (`tdgp[ADBE Mask Atom]`). */
        val atom: AepTypedView.PropGroup,
        /** The path property (`om-s[ADBE Mask Shape]/tdbs`). */
        val path: AepTypedView.Property?,
        /** Every `LIST shap` of `omks` in file order, null where one is malformed, so
         * that index `i` stays the shape of path keyframe `i` (one entry for a static path). */
        val pathShapes: List<ShapePath?>
    )

    /** The layer's masks with the views of their records, in stack order. */
    fun maskAtoms(root: AepTypedView.PropGroup?): List<MaskAtom> {
        val parade = (root?.get("ADBE Mask Parade") as? AepTypedView.PropNode.Group)?.group
            ?: return emptyList()
        val masks = ArrayList<MaskAtom>()
        var name: ByteArray? = null
        var mkif: ByteArray? = null
        for (c in parade.chunk.children()) {
            if (c.hasId("tdmn")) {
                name = c.bytes()
            } else if (c.hasId("mkif")) {
                mkif = c.bytes()
            } else if (c.isList("tdgp")) {
                val isAtom = name?.let { AepTypedView.matchName(it) == "ADBE Mask Atom" } == true
                if (isAtom) {
                    val atom = AepTypedView.PropGroup.fromChunk(c)
                    if (atom != null) masks.add(maskAtomFrom(atom, mkif))
                }
                name = null
                mkif = null
            }
        }
        return masks
    }

    private fun maskAtomFrom(atom: AepTypedView.PropGroup, mkif: ByteArray?): MaskAtom {
        val opacity = read(atom, "ADBE Mask Opacity", listOf(100.0), 100.0)
        val expansion = read(atom, "ADBE Mask Offset", listOf(0.0), 1.0)
        val mask = Mask(
            name = atom.userName(),
            mode = mkif?.let { beU32(it, 4) }?.let { MaskMode.fromId(it) },
            inverted = mkif?.firstOrNull()?.let { it.toInt() != 0 },
            counter = mkif?.let { beU32(it, 8) },
            feather = read(atom, "ADBE Mask Feather", listOf(0.0, 0.0), 1.0),
            opacityPercent = Tracked(opacity.value[0], opacity.source),
            expansion = Tracked(expansion.value[0], expansion.source),
            pathAnimated = false,
            pathKeySeconds = emptyList(),
            shapes = emptyList()
        )
        var path: AepTypedView.Property? = null
        var pathShapes = emptyList<ShapePath?>()
        var pathAnimated = false
        var pathKeySeconds = emptyList<Double>()
        val shapes = ArrayList<ShapePath>()
        val shapeNode = atom.get("ADBE Mask Shape")
        if (shapeNode is AepTypedView.PropNode.Other) {
            val oms = shapeNode.chunk
            path = oms.childList("tdbs")?.let { AepTypedView.Property.fromChunk(it) }
            if (path != null) {
                pathAnimated = path.isAnimated()
                val keys = path.keys()
                val scale = path.timeScale()
                if (keys != null && scale != null) {
                    pathKeySeconds = keys.keyframes().mapNotNull { it.seconds(scale) }
                }
            }
            val omks = oms.childList("omks")
            if (omks != null) {
                pathShapes = omks.children().filter { it.isList("shap") }
                    .map { ShapePath.fromChunk(it) }
                shapes.addAll(pathShapes.filterNotNull())
            }
        }
        return MaskAtom(
            Mask(
                mask.name, mask.mode, mask.inverted, mask.counter, mask.feather,
                mask.opacityPercent, mask.expansion, pathAnimated, pathKeySeconds, shapes
            ),
            atom, path, pathShapes
        )
    }

    /** Reads `N` components from the property `name` of `group` and multiplies them by
     * `scale` (stored fractions to percent); `default` is in the output units and
     * used when the property is absent. */
    private fun read(
        group: AepTypedView.PropGroup,
        name: String,
        default: List<Double>,
        scale: Double
    ): Tracked<List<Double>> {
        val unreadable = Tracked(default, ValueSource.Unreadable)
        val node = group.get(name) ?: return Tracked(default, ValueSource.Default)
        val property = (node as? AepTypedView.PropNode.Property)?.property ?: return unreadable
        val (values, source) = if (property.isAnimated()) {
            property.firstKeyValue() to ValueSource.Keyframed
        } else {
            property.staticValue() to ValueSource.Stored
        }
        val v = values ?: return unreadable
        if (v.size < default.size) return unreadable
        return Tracked(v.take(default.size).map { it * scale }, source)
    }

    // ------------------------------------------------------------------ import

    /** The layer whose masks are imported. */
    class MaskLayer(
        /** The layer's source size (1 x 1 on a layer without a source, whose mask
         * boxes are pixels). */
        val size: Pair<Double, Double>,
        /** Whether the layer has a source (a mask without a stored path is then its rectangle). */
        val source: Boolean
    )

    /** Imports the masks of a layer into mask groups, in stack order. */
    fun importMasks(
        root: AepTypedView.PropGroup?,
        compName: String,
        layerName: String,
        size: Pair<Double, Double>,
        hasSource: Boolean,
        allocBlock: (Int) -> Long,
        timescale: Long,
        col: AepImportReport.Collector
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val ml = MaskLayer(size, hasSource)
        for ((i, atom) in maskAtoms(root).withIndex()) {
            val shown = atom.mask.name ?: "Mask ${i + 1}"
            val what = "$compName: '$layerName' $shown"
            val group = importMask(atom, i, shown, what, ml, allocBlock, timescale, col)
            if (group != null) {
                out.add(group)
                col.imported.masks += 1
            }
        }
        return out
    }

    private fun importMask(
        atom: MaskAtom,
        index: Int,
        name: String,
        what: String,
        ml: MaskLayer,
        allocBlock: (Int) -> Long,
        timescale: Long,
        col: AepImportReport.Collector
    ): JSONObject? {
        val m = atom.mask
        // The path: the first shape (the static path, or the shape of the first key).
        // A mask atom without a Mask Path entry is a mask whose path was never set:
        // After Effects reads it back as the layer's source rectangle.
        val unset = atom.pathShapes.isEmpty() && atom.atom.get("ADBE Mask Shape") == null
        val first: DecodedPath = if (atom.pathShapes.isEmpty()) {
            when {
                unset && ml.source -> DecodedPath.Ok(sourceRectangle(ml.size))
                unset -> DecodedPath.Err("it has no path (the default path of a layer without a source is not sampled)")
                else -> DecodedPath.Err("it has no path")
            }
        } else {
            atom.pathShapes[0]?.toJson(ml.size)
                ?: DecodedPath.Err("its path is unreadable")
        }
        val pathJson = when (first) {
            is DecodedPath.Ok -> first.json
            is DecodedPath.Err -> {
                col.lose(
                    AepImportReport.LossCategory.Masks,
                    "$what: not imported, ${first.why}"
                )
                return null
            }
        }
        val maskMode = when (val code = m.mode) {
            null -> {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: mode and invert switch unreadable, Add used"
                )
                MaskMode.Add
            }
            is MaskMode.Other -> {
                col.lose(
                    AepImportReport.LossCategory.Masks,
                    "$what: mode code ${code.code} is not known, imported as Add"
                )
                MaskMode.Add
            }
            else -> code
        }
        val color = MASK_COLORS[index % MASK_COLORS.size]
        val base = allocBlock(5)
        val group = JSONObject()
            .put("node", "Group")
            .put("uid", base)
            .put("match", "mask")
            .put("name", name)
            .put(
                "kind",
                JSONObject()
                    .put("kind", "Mask")
                    .put("mode", maskMode.jsonName)
                    .put("inverted", m.inverted ?: false)
                    .put("color", JSONArray(color))
                    .put("locked", false)
            )
            .put("enabled", true)
        val children = JSONArray()
        val pathProp = prop(
            base + 1, "path", "Mask Path",
            JSONObject().put("t", "Path").put("v", pathJson),
            JSONObject().put("kind", "Path")
        )
        val featherProp = prop(
            base + 2, "feather", "Mask Feather",
            AepEffectKeys.vec2(0.0, 0.0)!!,
            JSONObject().put("kind", "Pixels")
        )
        val opacityProp = prop(
            base + 3, "opacity", "Mask Opacity",
            AepEffectKeys.scalar(100.0)!!,
            JSONObject()
                .put("kind", "Slider")
                .put("min", 0.0).put("max", 100.0)
                .put("slider_min", 0.0).put("slider_max", 100.0)
                .put("decimals", 0)
        )
        val expansionProp = prop(
            base + 4, "expansion", "Mask Expansion",
            AepEffectKeys.scalar(0.0)!!,
            JSONObject().put("kind", "Pixels")
        )
        children.put(pathProp).put(featherProp).put(opacityProp).put(expansionProp)
        group.put("children", children)
        // The path keyframes.
        val path = atom.path
        if (path != null) {
            if (m.pathAnimated) {
                importPathKeys(atom.pathShapes, path, what, ml.size, timescale, pathProp, col)
            }
            importExpression(path, pathProp, col)
        }
        // Feather, opacity and expansion.
        setStatic(featherProp, "feather", m.feather, { v -> v.all { it >= 0.0 } }, what, col)
        setStatic(opacityProp, "opacity", Tracked(listOf(m.opacityPercent.value), m.opacityPercent.source), { v -> v.all { it in 0.0..100.0 } }, what, col)
        setStatic(expansionProp, "expansion", Tracked(listOf(m.expansion.value), m.expansion.source), { true }, what, col)
        for ((matchName, id, kind) in listOf(
            Triple("ADBE Mask Feather", "feather", AepEffectKeys.Kind.Vec2),
            Triple("ADBE Mask Opacity", "opacity", AepEffectKeys.Kind.Number(100.0)),
            Triple("ADBE Mask Offset", "expansion", AepEffectKeys.Kind.Number(1.0))
        )) {
            val node = (atom.atom.get(matchName) as? AepTypedView.PropNode.Property)?.property
                ?: continue
            val target = when (id) {
                "feather" -> featherProp
                "opacity" -> opacityProp
                else -> expansionProp
            }
            if (node.isAnimated()) {
                val keys = AepEffectKeys.importKeys(node, kind, what, matchName, timescale, col)
                if (keys != null) {
                    target.put("keys", keys.keys)
                    target.put("value", keys.firstValue)
                }
            }
            importExpression(node, target, col)
        }
        return group
    }

    /** The path of a mask whose path was never set: the source rectangle, clockwise
     * from the top left as After Effects reads it back, no tangents. */
    private fun sourceRectangle(size: Pair<Double, Double>): JSONObject {
        val (w, h) = size
        fun pt(x: Double, y: Double) = JSONArray().put(x).put(y)
        return JSONObject()
            .put("vertices", JSONArray().put(pt(0.0, 0.0)).put(pt(0.0, h)).put(pt(w, h)).put(pt(w, 0.0)))
            .put("in_tangents", JSONArray().put(pt(0.0, 0.0)).put(pt(0.0, 0.0)).put(pt(0.0, 0.0)).put(pt(0.0, 0.0)))
            .put("out_tangents", JSONArray().put(pt(0.0, 0.0)).put(pt(0.0, 0.0)).put(pt(0.0, 0.0)).put(pt(0.0, 0.0)))
            .put("closed", true)
    }

    /** Sets the static mask value when it is valid; otherwise the default stays and
     * it is reported. */
    private fun setStatic(
        prop: JSONObject,
        id: String,
        tracked: Tracked<List<Double>>,
        valid: (List<Double>) -> Boolean,
        what: String,
        col: AepImportReport.Collector
    ) {
        when (tracked.source) {
            ValueSource.Default, ValueSource.Keyframed -> Unit
            ValueSource.Unreadable -> col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: $id unreadable, default used"
            )
            ValueSource.Stored -> {
                val value = when (id) {
                    "feather" -> AepEffectKeys.vec2(tracked.value[0], tracked.value.getOrElse(1) { 0.0 })
                    else -> AepEffectKeys.scalar(tracked.value[0])
                }
                if (value != null && AepEffectKeys.sane(value) && valid(tracked.value)) {
                    prop.put("value", value)
                } else {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$what: $id holds a number that is not valid, default used"
                    )
                }
            }
        }
    }

    /** The path keyframes: key `i` of the path property with shape `i` of `omks`. */
    private fun importPathKeys(
        shapes: List<ShapePath?>,
        path: AepTypedView.Property,
        what: String,
        size: Pair<Double, Double>,
        timescale: Long,
        prop: JSONObject,
        col: AepImportReport.Collector
    ) {
        val list = path.keys()
        if (list == null) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: path keyframes unreadable, the first shape is static"
            )
            return
        }
        val records = list.keyframes()
        if (records.size != shapes.size) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: ${records.size} path keyframes but ${shapes.size} shapes, the first shape is static"
            )
            return
        }
        val scale = path.timeScale()?.takeIf { it != 0L } ?: timescale
        val keys = ArrayList<Pair<Long, JSONObject>>()
        for ((rec, shape) in records.zip(shapes)) {
            val units = rec.timeUnits()
            if (units == null) {
                col.lose(AepImportReport.LossCategory.DamagedData, "$what: unreadable path keyframe")
                continue
            }
            val decoded = shape?.toJson(size)
            val pathJson = (decoded as? DecodedPath.Ok)?.json
            if (pathJson == null) {
                val why = when {
                    shape == null -> "its shape is unreadable"
                    decoded is DecodedPath.Err -> decoded.why
                    else -> "its shape is unreadable"
                }
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: a path keyframe was dropped, $why"
                )
                continue
            }
            var inInterp = AepEffectKeys.interp(rec.interpolationIn(), col, what)
            var outInterp = AepEffectKeys.interp(rec.interpolationOut(), col, what)
            var inEase = emptyList<JSONObject>()
            var outEase = emptyList<JSONObject>()
            if (inInterp == "Bezier" || outInterp == "Bezier") {
                // The ease at byte 24: influences as fractions; a speed of 0 is exact,
                // the unit of another speed is not verified (taken as progress per second).
                val e = rec.ease()
                if (e != null && listOf(e.inSpeed, e.inInfluence, e.outSpeed, e.outInfluence).all { it.isFinite() }) {
                    fun ease(speed: Double, influence: Double) =
                        JSONObject().put("speed", speed).put("influence", influence.coerceIn(0.001, 1.0))
                    if (inInterp == "Bezier") inEase = listOf(ease(e.inSpeed, e.inInfluence))
                    if (outInterp == "Bezier") outEase = listOf(ease(e.outSpeed, e.outInfluence))
                    if ((inInterp == "Bezier" && e.inSpeed != 0.0) || (outInterp == "Bezier" && e.outSpeed != 0.0)) {
                        col.lose(
                            AepImportReport.LossCategory.ApproximatedKeyframes,
                            "$what: path key ease speed imported unverified (only speed 0 is sampled)"
                        )
                    }
                } else {
                    col.lose(
                        AepImportReport.LossCategory.ApproximatedKeyframes,
                        "$what: bezier path key read as linear (its ease is unreadable)"
                    )
                    if (inInterp == "Bezier") inInterp = "Linear"
                    if (outInterp == "Bezier") outInterp = "Linear"
                }
            }
            val obj = JSONObject()
                .put("time", (units.toDouble() / scale.toDouble() * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong())
                .put("value", JSONObject().put("t", "Path").put("v", pathJson))
                .put("in_interp", inInterp)
                .put("out_interp", outInterp)
            if (inEase.isNotEmpty()) obj.put("in_ease", JSONArray(inEase))
            if (outEase.isNotEmpty()) obj.put("out_ease", JSONArray(outEase))
            if (rec.isRoving() == true) obj.put("roving", true)
            val label = rec.label()?.takeIf { it <= 16 } ?: 0
            if (label != 0) obj.put("label", label)
            keys.add(obj.getLong("time") to obj)
        }
        if (keys.isEmpty()) return
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
            col.lose(AepImportReport.LossCategory.DamagedData, "$what: path keyframes at the same time")
        }
        // The first key's incoming and the last key's outgoing ease mean nothing.
        if (deduped.isNotEmpty()) {
            deduped.first().remove("in_ease")
            deduped.last().remove("out_ease")
        }
        col.imported.keyframes += deduped.size
        prop.put("value", deduped.first().getJSONObject("value"))
        prop.put("keys", JSONArray(deduped))
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

    /** A canonical property node. */
    private fun prop(
        uid: Long,
        match: String,
        name: String,
        value: JSONObject,
        ui: JSONObject
    ): JSONObject = JSONObject()
        .put("node", "Prop")
        .put("uid", uid)
        .put("match", match)
        .put("name", name)
        .put("value", value)
        .put("keys", JSONArray())
        .put("ui", ui)
        .put("spatial", false)
        .put("hold_only", false)
}
