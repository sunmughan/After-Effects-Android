package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.engine.NativeProjectBridge
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Imports the effects of one layer into its `effects` group, in stack order.
 *
 * Port of `import/effects.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). The effect group skeleton (the
 * parameters with their defaults and UI) comes from the native engine, exactly
 * as the official importer instantiates it; this file only patches the values,
 * keyframes and expressions the file stores into that skeleton.
 */
object AepEffectImport {

    /** Provides effect group skeletons from the native engine. */
    fun interface EffectSpecProvider {
        /** The instantiated effect group JSON for [effectId] (instance name, layer size),
         * or null when the engine has no such effect. */
        fun instantiate(effectId: String, instanceName: String, layerWidth: Double, layerHeight: Double): JSONObject?
    }

    /** Production provider: the group skeleton comes from the native engine
     * (`effectcraft-effects`, via `NativeProjectBridge`). */
    class NativeEffectSpecs : EffectSpecProvider {
        override fun instantiate(effectId: String, instanceName: String, layerWidth: Double, layerHeight: Double): JSONObject? {
            val json = NativeProjectBridge.instantiateEffectGroup(effectId, instanceName, layerWidth, layerHeight)
                ?: return null
            return runCatching { JSONObject(json) }.getOrNull()
        }
    }

    /** A layer parameter that names another layer, set once every layer of the
     * composition exists (the layer can sit below the effect's layer). */
    class PendingLayerRef(
        /** The index of the layer that owns the effect, in the composition. */
        val layerIndex: Int,
        /** The uid of the parameter (in patch uid space; remapped with the groups). */
        var propUid: Long,
        /** The file's id of the named layer. */
        val target: Long,
        /** Where it is, for the report. */
        val what: String
    )

    class Result(val groups: List<JSONObject>, val refs: List<PendingLayerRef>)

    /** The explicit "no layer" id: `Value::Layer(Some(NO_LAYER))` in the engine. */
    const val NO_LAYER = 0L

    /**
     * Imports the effects of [layer] into effect group JSONs.
     *
     * @param layerIndex the layer's index in the composition (for pending refs).
     * @param size the layer's source size in pixels (effect points are fractions of it).
     * @param allocBlock allocates a contiguous block of patch uids for one group and
     *   returns its base; the group's uids from the provider (1..N) are offset by it.
     */
    fun importEffects(
        layer: AepTypedView.Layer,
        compName: String,
        layerName: String,
        layerIndex: Int,
        aepLayerId: Long,
        size: Pair<Double, Double>,
        defs: AepEffects.EffectDefinitions,
        provider: EffectSpecProvider,
        allocBlock: (Int) -> Long,
        timescale: Long,
        col: AepImportReport.Collector
    ): Result {
        val groups = ArrayList<JSONObject>()
        val refs = ArrayList<PendingLayerRef>()
        for (fx in AepEffects.effectsOf(layer)) {
            val shown = fx.displayName() ?: fx.matchName
            val map = AepEffectMap.byMatchName(fx.matchName)
            if (map == null) {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$compName: '$layerName' has the effect '$shown' (${fx.matchName})"
                )
                continue
            }
            val group = provider.instantiate(map.effect, shown, size.first, size.second)
            if (group == null) {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$compName: '$layerName' has the effect '$shown' (${fx.matchName})"
                )
                continue
            }
            // The provider numbers its uids from 1; move the group into patch uid space.
            val maxUid = maxUid(group)
            val base = allocBlock(maxUid)
            remapUids(group, base - 1)
            when (fx.enabled()) {
                true, false -> group.put("enabled", fx.enabled()!!)
                null -> col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$compName: '$layerName': the fx switch of '$shown' is unreadable, imported switched on"
                )
            }
            if (defs.source(fx) == AepEffects.DefinitionSource.MISSING) {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$compName: '$layerName' has the effect '$shown' (${fx.matchName}) without readable parameter definitions, not imported"
                )
                continue
            }
            val params = defs.params(fx)
            // Borrowed definitions (another instance's or the project copy) may not name every
            // value record this instance has: such a record has nothing to import into.
            if (params.firstOrNull()?.borrowedDefinition == true) {
                val defined = params.map { it.matchName }.toSet()
                for (name in fx.valueRecordNames()) {
                    if (name !in defined) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$compName: '$layerName' effect '$shown' (${fx.matchName}): the value record $name has no parameter definition, not imported"
                        )
                    }
                }
            }
            if (map.extra == AepEffectMap.Extra.CRYPTOMATTE) {
                importCryptomatte(fx, params, compName, layerName, shown, group, col)
            } else {
                for (p in params) {
                    importParam(fx, map, p, compName, layerName, shown, aepLayerId, size, group, refs, layerIndex, timescale, col)
                }
            }
            col.imported.effects += 1
            groups.add(group)
        }
        return Result(groups, refs)
    }

    /** Imports one parameter of `fx` into its EffectCraft property: its keyframes, the value
     * in its record or the `pard` default, with a loss for what cannot be read or mapped. */
    private fun importParam(
        fx: AepEffects.Effect,
        map: AepEffectMap.EffectMap,
        p: AepEffects.EffectParam,
        compName: String,
        layerName: String,
        shown: String,
        aepLayerId: Long,
        size: Pair<Double, Double>,
        group: JSONObject,
        refs: MutableList<PendingLayerRef>,
        layerIndex: Int,
        timescale: Long,
        col: AepImportReport.Collector
    ) {
        if (carriesNothing(p)) return
        val label = if (p.name.isEmpty()) p.matchName else "${p.name} (${p.matchName})"
        val here = "$compName: '$layerName' effect '$shown' (${fx.matchName})"
        if (p.kind == AepEffects.ParamKind.ARBITRARY_DATA && map.extra != AepEffectMap.Extra.NONE) {
            importExtra(fx, map.extra, p, here, size, group, col)
            return
        }
        val m = map.params.firstOrNull { it.index == p.index }
        if (m == null) {
            // Not in the table: lost only when the file stores something for it. A layer
            // parameter set to no layer holds nothing.
            val stored = p.storedValue()
            if (p.stored != null && stored !is AepEffects.ParamValue.Layer) {
                col.lose(AepImportReport.LossCategory.Effects, "$here: parameter $label not imported")
            } else if (p.stored != null && stored is AepEffects.ParamValue.Layer && stored.id != 0L) {
                col.lose(AepImportReport.LossCategory.Effects, "$here: parameter $label not imported")
            }
            return
        }
        if (p.kind != m.kind) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: parameter $label has type ${p.kind}, expected ${m.kind}; not imported"
            )
            return
        }
        when (val conv = m.conv) {
            is AepEffectMap.Conv.Skip -> return
            is AepEffectMap.Conv.LayerRef -> {
                importLayerRef(p, m.target, conv.own, aepLayerId, here, label, group, refs, layerIndex, col)
                return
            }
            else -> Unit
        }
        val checked: (Double) -> Boolean = { validMax ->
            // The scale comes from the file: a slider whose valid maximum is not the one the
            // table was verified with is not guessed at.
            val found = p.validMax()
            val ok = found != null && abs(found - validMax) <= abs(validMax) * 1e-4
            if (!ok) {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: parameter $label has the valid maximum $found, expected $validMax; not imported"
                )
            }
            ok
        }
        // The popup's items: the file's, else After Effects' when the item count agrees.
        val items: List<String> = when (val conv = m.conv) {
            is AepEffectMap.Conv.Popup -> when {
                p.items != null -> p.items
                p.popupItemCount() == conv.items.size -> conv.items
                else -> {
                    col.lose(
                        AepImportReport.LossCategory.Effects,
                        "$here: parameter $label: the file names no popup items and has ${p.popupItemCount()} of them, not imported"
                    )
                    return
                }
            }
            else -> emptyList()
        }
        val kind = when (val conv = m.conv) {
            is AepEffectMap.Conv.Color -> AepEffectKeys.Kind.Color
            is AepEffectMap.Conv.Point -> AepEffectKeys.Kind.Point(size.first, size.second)
            is AepEffectMap.Conv.Angle -> AepEffectKeys.Kind.Number(1.0)
            is AepEffectMap.Conv.Popup -> AepEffectKeys.Kind.Popup(items.size)
            is AepEffectMap.Conv.Checkbox -> AepEffectKeys.Kind.Bool
            is AepEffectMap.Conv.Percent -> {
                if (!checked(conv.validMax)) return
                AepEffectKeys.Kind.Number(100.0 / conv.validMax)
            }
            is AepEffectMap.Conv.Plain -> {
                if (!checked(conv.validMax)) return
                AepEffectKeys.Kind.Number(1.0)
            }
            is AepEffectMap.Conv.Factor -> {
                if (!checked(conv.validMax)) return
                AepEffectKeys.Kind.Number(conv.factor)
            }
            else -> return
        }
        // Grouped parameters are addressed by path (`master/exposure`).
        val prop = propMut(group, m.target)
        if (prop == null) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: parameter $label has no counterpart '${m.target}'"
            )
            return
        }
        // For a popup: file item (0-based over all items) -> EffectCraft item, by name.
        val popupMap: List<Int?>? = if (m.conv is AepEffectMap.Conv.Popup) {
            popupMapping(items, prop) ?: run {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: parameter $label: the popup items do not match EffectCraft's, not imported"
                )
                return
            }
        } else null
        // The expression of an effect parameter is stored as on any property.
        val stored = p.stored
        if (stored != null) {
            // The expression evaluates in the units After Effects shows (a Glow midpoint 0.25,
            // a popup's 1-based item), the property here holds the converted value (25, a
            // 0-based item): with a conversion the expression would read and write the wrong
            // scale, so it is reported instead.
            val converted = when (kind) {
                is AepEffectKeys.Kind.Number -> abs(kind.factor - 1.0) > Double.MIN_VALUE
                is AepEffectKeys.Kind.Popup -> true
                else -> false
            }
            if (converted && stored.expression() != null) {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: the expression on parameter $label is not imported (the value is converted here)"
                )
            } else {
                importExpression(stored, prop, col)
            }
        }
        if (p.isAnimated()) {
            if (kind is AepEffectKeys.Kind.Bool) {
                // Keys on a checkbox are not sampled by the corpus.
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: the keyframes of parameter $label are not imported (not decoded), default used"
                )
                return
            }
            if (stored != null) {
                val before = prop.optJSONArray("keys")?.length() ?: 0
                val imported = AepEffectKeys.importKeys(stored, kind, layerName, p.matchName, timescale, col)
                if (imported != null) {
                    prop.put("keys", imported.keys)
                    prop.put("value", imported.firstValue)
                }
                val after = prop.optJSONArray("keys")?.length() ?: 0
                if (after == before) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: parameter $label has no readable keyframe, default used"
                    )
                }
                if (popupMap != null) remapPopupKeys(prop, popupMap, here, label, col)
                val keyValues = (0 until (prop.optJSONArray("keys")?.length() ?: 0))
                    .mapNotNull { prop.optJSONArray("keys")?.optJSONObject(it)?.optJSONObject("value") }
                reportBeyondLimit(m.limit, keyValues, here, label, col)
            }
            return
        }
        // A record in the default state (`tdsb` 3) still holds the value After Effects uses.
        val value: AepEffects.ParamValue? = when {
            stored != null -> {
                val recorded = p.recordedValue()
                if (recorded != null) recorded
                else {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: parameter $label holds an unreadable value, default used"
                    )
                    return
                }
            }
            // Absent = default. A borrowed popup's default slot is not reliable (a selection
            // writes into it): After Effects' default for a fresh effect is used.
            p.kind == AepEffects.ParamKind.POPUP && m.conv is AepEffectMap.Conv.Popup -> {
                val def = p.pardDefault()
                if (def is AepEffects.ParamValue.Index && def.value != 0L) def
                else AepEffects.ParamValue.Index(m.conv.default)
            }
            else -> p.pardDefault()
        }
        if (value == null) return
        val converted: JSONObject? = when {
            value is AepEffects.ParamValue.Index && popupMap != null -> {
                val i = value.value - 1
                when {
                    i < 0 || i >= popupMap.size -> null
                    popupMap[i.toInt()] != null -> AepEffectKeys.enum(popupMap[i.toInt()]!!.toLong())
                    else -> {
                        val item = items.getOrNull(i.toInt()) ?: ""
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: parameter $label: item '$item' has no counterpart, default used"
                        )
                        return
                    }
                }
            }
            else -> AepEffectKeys.convertValue(value, kind)
        }
        if (converted != null) {
            reportBeyondLimit(m.limit, listOf(converted), here, label, col)
            prop.put("value", converted)
        } else {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$here: parameter $label holds a value that is not valid ($value), default used"
            )
        }
    }

    /** Parameters that never carry a value to import. */
    private fun carriesNothing(p: AepEffects.EffectParam): Boolean =
        p.kind == AepEffects.ParamKind.BUTTON || p.kind == AepEffects.ParamKind.NO_DATA ||
            p.kind == AepEffects.ParamKind.GROUP_START || p.kind == AepEffects.ParamKind.GROUP_END ||
            (p.index == 0 && p.kind == AepEffects.ParamKind.LAYER)

    /** Reports a value After Effects accepts but EffectCraft's effect limits: it is imported
     * as it is, and the effect renders it as the nearest end of its range. */
    private fun reportBeyondLimit(
        limit: Pair<Double, Double>?,
        values: List<JSONObject>,
        here: String,
        label: String,
        col: AepImportReport.Collector
    ) {
        val (lo, hi) = limit ?: return
        val beyond = values.filter { it.optString("t") == "Scalar" }
            .map { it.optDouble("v") }
            .firstOrNull { it < lo || it > hi } ?: return
        col.lose(
            AepImportReport.LossCategory.Effects,
            "$here: parameter $label is $beyond, EffectCraft's effect uses $lo to $hi (rendered as the nearest of them)"
        )
    }

    /** A layer parameter: None stays unset, the owning layer is itself (`own`) or unset,
     * another layer is resolved by [resolveLayerRefs] once the composition's layers exist. */
    private fun importLayerRef(
        p: AepEffects.EffectParam,
        target: String,
        own: Boolean,
        aepLayerId: Long,
        here: String,
        label: String,
        group: JSONObject,
        refs: MutableList<PendingLayerRef>,
        layerIndex: Int,
        col: AepImportReport.Collector
    ) {
        // After Effects hands the named layer's source to the effect, without its masks and
        // effects; EffectCraft's companion choice defaults to Effects & Masks.
        propMut(group, "${target}Source")?.put("value", AepEffectKeys.enum(0))
        val prop = propMut(group, target)
        if (prop == null) {
            col.lose(AepImportReport.LossCategory.Effects, "$here: parameter $label has no counterpart '$target'")
            return
        }
        val stored = p.stored
        if (p.isAnimated() || (stored != null && stored.expression() != null)) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: keyframes or an expression on the layer parameter $label are not imported"
            )
        }
        // No record: the default, the owning layer. The owning layer's project id does not
        // exist yet (the converter assigns it), so it is resolved in the patch step like
        // any other layer reference.
        when (val ref = p.layerRef(aepLayerId) ?: AepEffects.LayerRef.This) {
            is AepEffects.LayerRef.Nothing -> prop.put(
                "value",
                layerValue(if (own) null else NO_LAYER)
            )
            is AepEffects.LayerRef.This -> {
                prop.put("value", layerValue(null))
                if (own) {
                    refs.add(
                        PendingLayerRef(
                            layerIndex = layerIndex,
                            propUid = prop.optLong("uid"),
                            target = aepLayerId,
                            what = "$here: parameter $label"
                        )
                    )
                }
            }
            is AepEffects.LayerRef.Layer -> {
                prop.put("value", layerValue(null))
                refs.add(
                    PendingLayerRef(
                        layerIndex = layerIndex,
                        propUid = prop.optLong("uid"),
                        target = ref.id,
                        what = "$here: parameter $label"
                    )
                )
            }
        }
    }

    /** Sets the layer parameters that name another layer of the composition
     * ([fileIdToEcId]: file id -> project layer id); one whose layer was not imported
     * stays unset and is reported. Call from the patch step, where the layer ids exist. */
    fun resolveLayerRefs(
        compLayers: JSONArray,
        fileIdToEcId: Map<Long, Long>,
        refs: List<PendingLayerRef>,
        col: AepImportReport.Collector,
        reportMissing: Boolean = true
    ) {
        for (r in refs) {
            val target = fileIdToEcId[r.target]
            if (target == null) {
                if (reportMissing) {
                    col.lose(
                        AepImportReport.LossCategory.Effects,
                        "${r.what} names a layer that is not imported (id ${r.target}), left unset"
                    )
                }
                continue
            }
            // The owning layer is the one at r.layerIndex; find the prop by uid inside it.
            val owner = compLayers.optJSONObject(r.layerIndex) ?: continue
            val prop = findPropByUid(owner.optJSONObject("props"), r.propUid) ?: continue
            prop.put("value", layerValue(target))
        }
    }

    /** The EffectCraft item of each file item (by name, ignoring case and separators);
     * null when the property here is not a popup. */
    private fun popupMapping(fileItems: List<String>, prop: JSONObject): List<Int?>? {
        val ui = prop.optJSONObject("ui") ?: return null
        if (ui.optString("kind") != "Popup") return null
        val options = ui.optJSONArray("options") ?: return null
        val optionNames = (0 until options.length()).map { options.optString(it) }
        return fileItems.map { item ->
            if (item.trim() == "(-") null
            else optionNames.indexOfFirst { AepEffectMap.popupKey(it) == AepEffectMap.popupKey(item) }
                .takeIf { it >= 0 }
        }
    }

    /** Keys of a popup were imported as file indices (0-based); maps them to EffectCraft's items. */
    private fun remapPopupKeys(
        prop: JSONObject,
        map: List<Int?>,
        here: String,
        label: String,
        col: AepImportReport.Collector
    ) {
        val keys = prop.optJSONArray("keys") ?: return
        val before = keys.length()
        val kept = JSONArray()
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            val value = key.optJSONObject("value")
            if (value != null && value.optString("t") == "Enum") {
                val index = value.optLong("v").toInt()
                val ec = map.getOrNull(index)
                if (ec != null) {
                    value.put("v", ec)
                    kept.put(key)
                }
            } else {
                kept.put(key)
            }
        }
        if (kept.length() != before) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: a keyframe of parameter $label has an item without a counterpart, dropped"
            )
        }
        prop.put("keys", kept)
        if (kept.length() > 0) prop.put("value", kept.getJSONObject(0).getJSONObject("value"))
    }

    /** Hue/Saturation's master values, Levels' RGB values: from the arbitrary data of `p`. */
    private fun importExtra(
        fx: AepEffects.Effect,
        extra: AepEffectMap.Extra,
        p: AepEffects.EffectParam,
        here: String,
        size: Pair<Double, Double>,
        group: JSONObject,
        col: AepImportReport.Collector
    ) {
        val stored = p.stored
        if (p.isAnimated() || (stored != null && stored.expression() != null)) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: keyframes or an expression on ${p.name} are not imported"
            )
        }
        val data = fx.arbitraryData(p.matchName) ?: return // No data: every value is at its default.
        fun set(id: String, v: Double) {
            if (!v.isFinite()) return
            propMut(group, id)?.put("value", AepEffectKeys.scalar(v))
        }
        when (extra) {
            AepEffectMap.Extra.HUE_SATURATION -> {
                val d = AepArbitrary.HueSaturationData.parse(data)
                if (d == null) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: the Hue/Saturation data is unreadable, defaults used"
                    )
                } else {
                    for ((id, v) in AepEffectMap.HUE_SATURATION_MASTER.zip(d.master.toList())) {
                        set(id, v.toDouble())
                    }
                    if (!d.rangesAreDefault()) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: the colour ranges (Reds ... Magentas) are not imported (not sampled)"
                        )
                    }
                }
            }
            AepEffectMap.Extra.LEVELS -> {
                val d = AepArbitrary.LevelsData.parse(data)
                if (d == null) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: the Levels data is unreadable, defaults used"
                    )
                } else {
                    for ((id, v) in AepEffectMap.LEVELS_RGB.zip(d.channels[0].toList())) {
                        set(id, v.toDouble())
                    }
                    if (d.channels.drop(1).any { !it.contentEquals(AepArbitrary.LevelsData.DEFAULT_CHANNEL) }) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: the per-channel values (Red, Green, Blue, Alpha) are not imported (not sampled)"
                        )
                    }
                }
            }
            AepEffectMap.Extra.CURVES -> {
                val d = AepArbitrary.CurvesData.parse(data)
                if (d == null) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: the Curves data is unreadable, defaults used"
                    )
                } else {
                    // EffectCraft's curves are `x,y` points in 0..1 (the stored inputs and
                    // outputs / 255); EffectCraft interpolates them its own way (a renderer
                    // difference, the stored tables are After Effects' natural spline).
                    val ids = listOf("rgb", "red", "green", "blue", "alpha")
                    for ((id, pts) in ids.zip(d.curves)) {
                        val s = pts.joinToString(" ") { (x, y) ->
                            "${AepArbitrary.fraction(x)},${AepArbitrary.fraction(y)}"
                        }
                        propMut(group, id)?.put(
                            "value",
                            JSONObject().put("t", "Str").put("v", s)
                        )
                    }
                }
            }
            AepEffectMap.Extra.MESH_WARP -> {
                val m = AepArbitrary.MeshData.parse(data)
                if (m == null) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: the Distortion Mesh is unreadable, defaults used"
                    )
                } else {
                    importMesh(m, here, size, group, col)
                }
            }
            else -> Unit
        }
    }

    /** The Cryptomatte plug-in: 1 Settings data (the layer by name and the selection, both
     * carried as text), 2 Output (EffectCraft's Display and Matte Only), 3 Selection Mode
     * (reported when on). Anything else stored is reported. */
    private fun importCryptomatte(
        fx: AepEffects.Effect,
        params: List<AepEffects.EffectParam>,
        compName: String,
        layerName: String,
        shown: String,
        group: JSONObject,
        col: AepImportReport.Collector
    ) {
        val here = "$compName: '$layerName' effect '$shown' (${fx.matchName})"
        fun set(id: String, v: JSONObject) {
            propMut(group, id)?.put("value", v)
        }
        // The plug-in's Output starts at Colors (EffectCraft's Display starts at Preview);
        // an Output that cannot be imported keeps it.
        set("display", AepEffectKeys.enum(0))
        set("matteOnly", AepEffectKeys.bool(false)!!)
        for (p in params) {
            if (carriesNothing(p)) continue
            val label = if (p.name.isEmpty()) p.matchName else "${p.name} (${p.matchName})"
            val stored = p.stored
            val moving = p.isAnimated() || (stored != null && stored.expression() != null)
            when {
                p.index == 1 && p.kind == AepEffects.ParamKind.ARBITRARY_DATA -> {
                    if (moving) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: keyframes or an expression on $label are not imported"
                        )
                    }
                    val data = fx.arbitraryData(p.matchName)
                    if (data == null) {
                        if (stored != null) {
                            col.lose(
                                AepImportReport.LossCategory.DamagedData,
                                "$here: the Cryptomatte settings (layer, selection) have no data, none used"
                            )
                        }
                        continue
                    }
                    val d = AepArbitrary.CryptomatteData.parse(data)
                    if (d == null) {
                        col.lose(
                            AepImportReport.LossCategory.DamagedData,
                            "$here: the Cryptomatte settings (layer, selection) are unreadable, none used"
                        )
                    } else {
                        set("layerName", JSONObject().put("t", "Str").put("v", d.layer))
                        set("selection", JSONObject().put("t", "Str").put("v", d.selection))
                    }
                }
                p.index == 2 && p.kind == AepEffects.ParamKind.POPUP -> {
                    if (moving) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: keyframes or an expression on $label are not imported, Colors used"
                        )
                        continue
                    }
                    val (display, matteOnly) = cryptoOutput(p, here, label, col)
                    set("display", AepEffectKeys.enum(display.toLong()))
                    set("matteOnly", AepEffectKeys.bool(matteOnly)!!)
                }
                p.index == 3 && p.kind == AepEffects.ParamKind.CHECKBOX -> {
                    if (moving) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: keyframes or an expression on $label are not imported"
                        )
                    } else {
                        when (selectionModeOn(p)) {
                            true -> col.lose(
                                AepImportReport.LossCategory.Effects,
                                "$here: parameter $label is on and has no counterpart"
                            )
                            false -> Unit
                            null -> col.lose(
                                AepImportReport.LossCategory.DamagedData,
                                "$here: parameter $label holds an unreadable value, off used"
                            )
                        }
                    }
                }
                else -> {
                    val storedValue = p.storedValue()
                    if (stored != null && !(storedValue is AepEffects.ParamValue.Layer && storedValue.id == 0L)) {
                        col.lose(
                            AepImportReport.LossCategory.Effects,
                            "$here: parameter $label (${p.kind}) not imported"
                        )
                    }
                }
            }
        }
    }

    /** EffectCraft's (Display, Matte Only) for the plug-in's Output Colors, its default. */
    private val CRYPTO_COLORS = 0 to false

    /** Is Selection Mode on? The plug-in writes it, when on, as a record in the default state
     * (`tdsb` 3) holding 1.0, which `storedValue` does not read; so the record's value counts
     * whatever its state; no record is off (the `pard` default). */
    private fun selectionModeOn(p: AepEffects.EffectParam): Boolean? =
        when (val stored = p.stored) {
            null -> p.value() == AepEffects.ParamValue.Bool(true)
            else -> stored.staticValue()?.firstOrNull()?.let { it != 0.0 }
        }

    /** Cryptomatte's Output as EffectCraft's (Display, Matte Only): Colors is Colors, Matted
     * RGBA is Matte (the layer keyed by the selection), Matte Only is Matte Only. Matted Colors
     * becomes Matte, the same matte over the layer's own colours, and is reported. */
    private fun cryptoOutput(
        p: AepEffects.EffectParam,
        here: String,
        label: String,
        col: AepImportReport.Collector
    ): Pair<Int, Boolean> {
        val items: List<String> = when {
            p.items != null -> p.items
            p.popupItemCount() == AepEffectMap.CRYPTOMATTE_OUTPUT.size -> AepEffectMap.CRYPTOMATTE_OUTPUT
            else -> {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: parameter $label: the file names no popup items and has ${p.popupItemCount()} of them, not imported, Colors used"
                )
                return CRYPTO_COLORS
            }
        }
        val index: Long = when {
            p.stored != null -> when (val v = p.storedValue()) {
                is AepEffects.ParamValue.Index -> v.value
                else -> {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "$here: parameter $label holds an unreadable value, Colors used"
                    )
                    return CRYPTO_COLORS
                }
            }
            // No record: the default. The plug-in's is Colors (1). An instance's own definition
            // with another default keeps it; a borrowed one has no reliable popup default.
            else -> when (val def = p.pardDefault()) {
                is AepEffects.ParamValue.Index -> if (def.value != 0L && !p.borrowedDefinition) def.value else return CRYPTO_COLORS
                else -> return CRYPTO_COLORS
            }
        }
        val item = items.getOrNull((index - 1).toInt()) ?: ""
        return when (AepEffectMap.popupKey(item)) {
            "colors" -> CRYPTO_COLORS
            "matted rgba" -> 1 to false
            "matte only" -> 1 to true
            "matted colors" -> {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: parameter $label: item '$item' ($index) has no counterpart, imported as Matte (the layer's colours keyed by the selection, not the ID colours)"
                )
                1 to false
            }
            else -> {
                col.lose(
                    AepImportReport.LossCategory.Effects,
                    "$here: parameter $label: item '$item' ($index) has no counterpart, Colors used"
                )
                CRYPTO_COLORS
            }
        }
    }

    /** The Distortion Mesh as EffectCraft's per-vertex offsets in layer pixels (row-major
     * `dx,dy`). The grid size comes from the data; After Effects' Bezier handles are reported
     * when they are not the default ones (EffectCraft's mesh is bilinear). */
    private fun importMesh(
        m: AepArbitrary.MeshData,
        here: String,
        size: Pair<Double, Double>,
        group: JSONObject,
        col: AepImportReport.Collector
    ) {
        val w = size.first.coerceAtLeast(1.0)
        val h = size.second.coerceAtLeast(1.0)
        val rowsStored = propMut(group, "rows")?.optJSONObject("value")?.optDouble("v")?.let { it.toLong() }
        val colsStored = propMut(group, "columns")?.optJSONObject("value")?.optDouble("v")?.let { it.toLong() }
        if (rowsStored != m.rows.toLong() || colsStored != m.columns.toLong()) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: the Distortion Mesh is ${m.columns} x ${m.rows} cells, Rows / Columns say otherwise; not imported"
            )
            return
        }
        val offs = ArrayList<String>(m.vertices.size)
        var handlesMoved = false
        for ((k, v) in m.vertices.withIndex()) {
            val i = k % (m.columns + 1)
            val j = k / (m.columns + 1)
            val d = AepArbitrary.MeshData.defaultVertex(m.columns, m.rows, i, j)
            val dx = v[0][0] - d[0][0]
            val dy = v[0][1] - d[0][1]
            // A dragged vertex carries its handles along: each handle's own move is what is
            // left after the vertex's (pixels; top, right, bottom, left).
            val own = DoubleArray(8)
            for (n in 1 until 5) {
                val a = v[n]
                val b = d[n]
                if (b[0] == 0.0f && b[1] == 0.0f) continue
                val hx = (a[0] - b[0]) - dx
                val hy = (a[1] - b[1]) - dy
                own[2 * (n - 1)] = hx * w
                own[2 * (n - 1) + 1] = hy * h
            }
            if (!dx.isFinite() || !dy.isFinite() || own.any { !it.isFinite() }) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$here: the Distortion Mesh holds a number that is not valid, not imported"
                )
                return
            }
            // EffectCraft's mesh reads the first two numbers of a vertex; moved handles follow
            // as eight more (kept for the export, not drawn).
            var token = "${px(dx * w)},${px(dy * h)}"
            if (own.any { abs(it) > 0.01 }) {
                handlesMoved = true
                for (x in own) token += ",${px(x)}"
            }
            offs.add(token)
        }
        if (handlesMoved) {
            col.lose(
                AepImportReport.LossCategory.Effects,
                "$here: Distortion Mesh Bezier handles moved on their own are not drawn (EffectCraft's mesh is bilinear; they are kept for the export)"
            )
        }
        propMut(group, "rows")?.put("value", AepEffectKeys.scalar(m.rows.toDouble()))
        propMut(group, "columns")?.put("value", AepEffectKeys.scalar(m.columns.toDouble()))
        propMut(group, "mesh")?.put(
            "value",
            JSONObject().put("t", "Str").put("v", offs.joinToString(" "))
        )
    }

    /** A mesh offset in pixels: three decimals, and the float32 noise of an untouched vertex
     * (below 1/100 px, stored fractions of the layer) as 0. */
    private fun px(v: Double): String =
        if (abs(v) < 0.01) "0" else "%.3f".format(v)

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

    /** The property at `target` (`master/exposure` walks through the `master` group), or
     * null when the group has no such property. */
    fun propMut(group: JSONObject, target: String): JSONObject? {
        val parts = target.split("/")
        var current = group
        for ((index, part) in parts.withIndex()) {
            val children = current.optJSONArray("children") ?: return null
            var found: JSONObject? = null
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                if (child.optString("match") == part) {
                    found = child
                    break
                }
            }
            if (found == null) return null
            if (index == parts.lastIndex) return found
            if (found.optString("node") != "Group") return null
            current = found
        }
        return null
    }

    /** Finds the property with this uid anywhere under `props` (groups included). */
    fun findPropByUid(props: JSONObject?, uid: Long): JSONObject? {
        if (props == null) return null
        if (props.optLong("uid") == uid && props.optString("node") == "Prop") return props
        val children = props.optJSONArray("children") ?: return null
        for (i in 0 until children.length()) {
            val found = findPropByUid(children.optJSONObject(i), uid)
            if (found != null) return found
        }
        return null
    }

    /** The largest uid in the group tree (the provider numbers from 1). */
    fun maxUid(node: JSONObject): Int {
        var max = node.optInt("uid", 0)
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                max = maxOf(max, maxUid(child))
            }
        }
        return max
    }

    /** Offsets every uid in the group tree by [offset]. */
    fun remapUids(node: JSONObject, offset: Long) {
        if (node.has("uid")) node.put("uid", node.optLong("uid") + offset)
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                remapUids(child, offset)
            }
        }
    }

    /** A Layer value: an id, or null for unset. */
    fun layerValue(id: Long?): JSONObject =
        if (id == null) JSONObject().put("t", "Layer").put("v", JSONObject.NULL)
        else JSONObject().put("t", "Layer").put("v", id)
}
