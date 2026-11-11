package com.codeair.effectcraft.data.aep

/**
 * Effect instances: match name, instance name, the parameter list from
 * `parT`/`pard` and the values stored with the instance.
 *
 * Port of `container/effects.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). An effect on a layer is a
 * `LIST sspc` under `ADBE Effect Parade`. It holds `fnam` (the instance
 * name), `LIST parT` (the parameter definitions: `parn`, then a `tdmn`
 * and a 148-byte `pard` per parameter, a `pdnm` after a popup),
 * `LIST tdgp` (the values) and `pgui`. Only parameters that differ from
 * their `pard` default have a value record in the `tdgp` (the Layer
 * Control and button records are the exceptions: they always exist).
 *
 * When several layers carry an effect of the same type, only one
 * instance has a full `parT`; the others have an empty `LIST parT`.
 * Their parameter definitions are the ones in the project-level
 * `LIST EfdG` (one `EfDf` per effect type), or, in a file without
 * `EfdG`, the own `parT` of another instance of the same type.
 * Values are only ever read from the instance's own `tdgp`.
 */
object AepEffects {

    /** The parameter type, the `u32` at byte 12 of `pard`. */
    enum class ParamKind {
        /** `0`: a layer reference (also the implicit first parameter). */
        LAYER,
        /** `1`: an integer slider. */
        INT_SLIDER,
        /** `2`: a fixed-point (16.16) slider. */
        FIXED_SLIDER,
        /** `3`: an angle (16.16 degrees). */
        ANGLE,
        /** `4` */
        CHECKBOX,
        /** `5` */
        COLOR,
        /** `6` */
        POINT,
        /** `7`: a popup menu (the items are in the `pdnm` chunk). */
        POPUP,
        /** `9`: no data (the group-end entry `ADBE Effect Built In Params`). */
        NO_DATA,
        /** `10`: a floating-point slider. */
        FLOAT_SLIDER,
        /** `11`: arbitrary data (Hue/Saturation, Levels, Curves, Mesh Warp). */
        ARBITRARY_DATA,
        /** `12`: a path / mask reference. */
        PATH,
        /** `13`: the start of a twirl-down group. */
        GROUP_START,
        /** `14`: the end of a twirl-down group. */
        GROUP_END,
        /** `15`: a button (Tint's and Gradient Ramp's Swap Colors). */
        BUTTON,
        /** Any other value. */
        OTHER;

        companion object {
            fun fromU32(v: Long): ParamKind = when (v) {
                0L -> LAYER
                1L -> INT_SLIDER
                2L -> FIXED_SLIDER
                3L -> ANGLE
                4L -> CHECKBOX
                5L -> COLOR
                6L -> POINT
                7L -> POPUP
                9L -> NO_DATA
                10L -> FLOAT_SLIDER
                11L -> ARBITRARY_DATA
                12L -> PATH
                13L -> GROUP_START
                14L -> GROUP_END
                15L -> BUTTON
                else -> OTHER
            }
        }
    }

    /** A decoded parameter value, in the file's stored units. */
    sealed class ParamValue {
        /** A slider or angle value as stored. */
        class Number(val value: Double) : ParamValue()

        /** A checkbox: stored as 1.0 when on. */
        class Bool(val value: Boolean) : ParamValue()

        /** A point as stored: a fraction of the layer size. */
        class Point(val x: Double, val y: Double) : ParamValue()

        /** A layer control: the id of the chosen layer (`tdpi`). */
        class Layer(val id: Long) : ParamValue()

        /** A colour as ARGB fractions 0..1 (stored as doubles 0..255). */
        class Argb(val a: Double, val r: Double, val g: Double, val b: Double) : ParamValue()

        /** A popup's item index, 1-based. */
        class Index(val value: Long) : ParamValue()

        /** A 3-component vector as stored (3D Point Control: three doubles). */
        class Vec3(val x: Double, val y: Double, val z: Double) : ParamValue()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Number && other is Number -> value == other.value
            this is Bool && other is Bool -> value == other.value
            this is Point && other is Point -> x == other.x && y == other.y
            this is Layer && other is Layer -> id == other.id
            this is Argb && other is Argb -> a == other.a && r == other.r && g == other.g && b == other.b
            this is Index && other is Index -> value == other.value
            this is Vec3 && other is Vec3 -> x == other.x && y == other.y && z == other.z
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Number -> value.hashCode()
            is Bool -> value.hashCode()
            is Point -> 31 * x.hashCode() + y.hashCode()
            is Layer -> id.hashCode()
            is Argb -> (((a.hashCode() * 31 + r.hashCode()) * 31 + g.hashCode()) * 31 + b.hashCode())
            is Index -> value.hashCode()
            is Vec3 -> ((x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode())
        }
    }

    /** What a Layer Control points at, given the id of the layer that owns the effect. */
    sealed class LayerRef {
        /** The layer itself. */
        object This : LayerRef()

        /** None: the stored `tdpi` is 0. */
        object Nothing : LayerRef()

        /** Another layer, by id. */
        class Layer(val id: Long) : LayerRef()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is This && other is This -> true
            this is Nothing && other is Nothing -> true
            this is Layer && other is Layer -> id == other.id
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is This -> 1
            is Nothing -> 2
            is Layer -> id.hashCode()
        }
    }

    /** Where the parameter definitions of an effect instance come from. */
    enum class DefinitionSource {
        /** The instance's own `parT`. */
        INSTANCE,
        /** The project-level `EfdG/EfDf` copy for the effect's match name. */
        PROJECT,
        /** The own `parT` of another instance of the same match name in the file. */
        OTHER_INSTANCE,
        /** None of these has any: the effect has no readable parameter definitions. */
        MISSING
    }

    /** An effect instance on a layer. */
    class Effect(val matchName: String, val chunk: AepContainer.Chunk) {
        /** The instance name shown in the effect controls (`fnam`). */
        fun name(): String? =
            chunk.child("fnam")?.children()?.firstOrNull { it.hasId("Utf8") }
                ?.bytes()?.let { bytesToText(it) }

        /** The name the user gave the instance (`tdsn` of the value group). */
        fun userName(): String? {
            val values = chunk.childList("tdgp") ?: return null
            return AepTypedView.userName(values)
        }

        /** The name After Effects shows for the instance: user name, else `fnam`. */
        fun displayName(): String? = userName() ?: name()

        /** The instance's fx switch: `tdsb` of its value group is 1 when on, 0 when off. */
        fun enabled(): Boolean? {
            val tdsb = chunk.childList("tdgp")?.child("tdsb")?.bytes() ?: return null
            return when (beU32(tdsb, 0)) {
                1L -> true
                0L -> false
                else -> null
            }
        }

        /** True when the instance's own `parT` holds parameter definitions (a `pard`). */
        fun hasOwnDefinitions(): Boolean =
            chunk.childList("parT")?.children()?.any { it.hasId("pard") } == true

        /** The match names of the value records in the instance's own `tdgp`, each once. */
        fun valueRecordNames(): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            val group = chunk.childList("tdgp")?.let { AepTypedView.PropGroup.fromChunk(it) }
                ?: return out
            for (entry in group.entries()) {
                if (entry.node is AepTypedView.PropNode.Property && seen.add(entry.matchName)) {
                    out.add(entry.matchName)
                }
            }
            return out
        }

        /** The parameters in the order of the instance's own `parT`. */
        fun params(): List<EffectParam> {
            val part = chunk.childList("parT") ?: return emptyList()
            return paramsFrom(part, false)
        }

        /** The parameters described by `part` (a `LIST parT`) with the values of this instance. */
        fun paramsFrom(part: AepContainer.Chunk, borrowed: Boolean): List<EffectParam> {
            // The stored values by match name: the first entry of a name (a group of that
            // name means no stored value). Indexed once, not scanned per parameter.
            val values = HashMap<String, AepTypedView.Property?>()
            val group = chunk.childList("tdgp")?.let { AepTypedView.PropGroup.fromChunk(it) }
            if (group != null) {
                for (entry in group.entries()) {
                    val stored = (entry.node as? AepTypedView.PropNode.Property)?.property
                    if (!values.containsKey(entry.matchName)) values[entry.matchName] = stored
                }
            }
            val out = ArrayList<EffectParam>()
            var pending: String? = null
            for (c in part.children()) {
                when {
                    c.hasId("tdmn") -> pending = c.bytes()?.let { bytesToText(untilNul(it)) }
                    c.hasId("pard") -> {
                        val pard = c.bytes() ?: continue
                        val match = pending ?: ""
                        pending = null
                        out.add(
                            EffectParam(
                                index = out.size,
                                matchName = match,
                                kind = ParamKind.fromU32(beU32(pard, 12) ?: Long.MAX_VALUE),
                                name = cstr(pard, 16, 32) ?: "",
                                items = null,
                                borrowedDefinition = borrowed,
                                pard = pard,
                                stored = values[match]
                            )
                        )
                    }
                    c.hasId("pdnm") -> {
                        val last = out.lastOrNull() ?: continue
                        val text = c.children().firstOrNull { it.hasId("Utf8") }
                            ?.bytes()?.let { bytesToText(it) } ?: continue
                        if (text.isEmpty()) continue
                        val items = text.split('|')
                        out[out.lastIndex] = last.copy(items = items)
                    }
                }
            }
            return out
        }

        /** The arbitrary data stored for the parameter with this match name: the `aRbp`
         * of the `LIST aRbs` that follows the parameter's value record. */
        fun arbitraryData(matchName: String): ByteArray? {
            val values = chunk.childList("tdgp") ?: return null
            val kids = values.children()
            val at = kids.indexOfFirst {
                it.hasId("tdmn") && it.bytes()?.let { b -> nameIs(b, matchName) } == true
            }
            if (at < 0) return null
            val rest = kids.subList((at + 1).coerceAtMost(kids.size), kids.size)
            val it = rest.take(3).iterator()
            if (!it.hasNext() || !it.next().isList("tdbs")) return null
            while (it.hasNext()) {
                val list = it.next()
                if (list.isList("aRbs")) return list.child("aRbp")?.bytes()
            }
            return null
        }

        private fun nameIs(body: ByteArray, name: String): Boolean {
            val end = body.indexOf(0.toByte()).takeIf { it >= 0 } ?: body.size
            return body.copyOfRange(0, end).contentEquals(name.toByteArray(Charsets.US_ASCII))
        }
    }

    /** One parameter of an effect. */
    class EffectParam(
        /** The position in `parT` (0 is the implicit input layer). */
        val index: Int,
        /** The `tdmn` match name (`<effect match name>-NNNN`). */
        val matchName: String,
        val kind: ParamKind,
        /** The parameter name (`Blurriness`, `Slider`, ...). */
        val name: String,
        /** The popup items (`pdnm`, split at `|`). */
        val items: List<String>?,
        /** True when the definition is borrowed (project copy or another instance). */
        val borrowedDefinition: Boolean,
        /** The raw 148-byte `pard` record. */
        val pard: ByteArray,
        /** The value record stored with the instance, when there is one. */
        val stored: AepTypedView.Property?
    ) {
        fun copy(items: List<String>? = this.items): EffectParam =
            EffectParam(index, matchName, kind, name, items, borrowedDefinition, pard, stored)

        /** True when the parameter's value record has keyframes. */
        fun isAnimated(): Boolean = stored?.isAnimated() == true

        /** The value in the value record: float and fixed sliders, angles, checkboxes,
         * points, colours, popups and layer controls. A record counts when its `tdsb`
         * is 1 and it has no keyframes; a layer control reads the `tdpi` chunk. */
        fun storedValue(): ParamValue? {
            val p = stored ?: return null
            if (kind != ParamKind.LAYER && p.flags() != 1L) return null
            return recordedValue()
        }

        /** The value in the value record when the record is written (`tdsb` 1) or in the
         * default state (`tdsb` 3). `None` with keyframes, without a record or for a
         * record in any other state. */
        fun recordedValue(): ParamValue? {
            val p = stored ?: return null
            if (kind == ParamKind.LAYER) {
                val tdpi = p.chunk.child("tdpi")?.bytes() ?: return null
                return ParamValue.Layer(beU32(tdpi, 0) ?: return null)
            }
            if (p.flags() !in listOf(1L, 3L) || p.isAnimated()) return null
            val v = p.staticValue() ?: return null
            return when (kind) {
                ParamKind.FLOAT_SLIDER, ParamKind.FIXED_SLIDER, ParamKind.ANGLE, ParamKind.INT_SLIDER ->
                    v.firstOrNull()?.let { ParamValue.Number(it) }
                ParamKind.CHECKBOX -> v.firstOrNull()?.let { ParamValue.Bool(it != 0.0) }
                ParamKind.POINT -> {
                    val x = v.getOrNull(0) ?: return null
                    val y = v.getOrNull(1) ?: return null
                    ParamValue.Point(x, y)
                }
                ParamKind.POPUP -> {
                    val index = v.firstOrNull() ?: return null
                    if (index < 0.0 || index > 4294967295.0) return null
                    ParamValue.Index(index.toLong())
                }
                ParamKind.COLOR -> {
                    if (v.size < 4) return null
                    ParamValue.Argb(v[0] / 255.0, v[1] / 255.0, v[2] / 255.0, v[3] / 255.0)
                }
                // A 3D Point Control stores three doubles under kind 8 (OTHER); any
                // other component count stays unreadable rather than guessed.
                ParamKind.OTHER -> {
                    if (v.size != 3) return null
                    ParamValue.Vec3(v[0], v[1], v[2])
                }
                else -> null
            }
        }

        /** The default stored in `pard` at byte 56. A popup whose definition was
         * borrowed has no reliable default here (the slot holds the copied
         * instance's selection): `None`. */
        fun pardDefault(): ParamValue? {
            if (kind == ParamKind.POPUP && borrowedDefinition) return null
            return when (kind) {
                ParamKind.FLOAT_SLIDER -> beF64(pard, 56)?.let { ParamValue.Number(it) }
                ParamKind.FIXED_SLIDER, ParamKind.ANGLE ->
                    beI32(pard, 56)?.let { ParamValue.Number(it / 65536.0) }
                ParamKind.INT_SLIDER -> beI32(pard, 56)?.let { ParamValue.Number(it.toDouble()) }
                ParamKind.CHECKBOX -> beU32(pard, 56)?.let { ParamValue.Bool(it != 0L) }
                ParamKind.POPUP -> beU32(pard, 56)?.let { ParamValue.Index(it) }
                ParamKind.POINT -> {
                    val x = beI32(pard, 56)?.let { it / 65536.0 } ?: return null
                    val y = beI32(pard, 60)?.let { it / 65536.0 } ?: return null
                    // A fresh instance stores fractions of the layer (0.5, 0.5); a re-save
                    // stores percent (50, 50). A component beyond 1 tells percent.
                    val percent = kotlin.math.abs(x) > 1.0 + 1e-9 || kotlin.math.abs(y) > 1.0 + 1e-9
                    if (percent) ParamValue.Point(x / 100.0, y / 100.0) else ParamValue.Point(x, y)
                }
                ParamKind.COLOR -> {
                    if (pard.size < 60) return null
                    ParamValue.Argb(
                        (pard[56].toInt() and 0xFF) / 255.0,
                        (pard[57].toInt() and 0xFF) / 255.0,
                        (pard[58].toInt() and 0xFF) / 255.0,
                        (pard[59].toInt() and 0xFF) / 255.0
                    )
                }
                else -> null
            }
        }

        /** The effective value: the value in the record when there is one, else the
         * `pard` default; a checkbox without a record is off. */
        fun value(): ParamValue? =
            recordedValue() ?: pardDefault() ?: when {
                kind == ParamKind.CHECKBOX && stored == null -> ParamValue.Bool(false)
                else -> null
            }

        /** The largest valid value of a slider, from `pard`. */
        fun validMax(): Double? = when (kind) {
            ParamKind.FIXED_SLIDER -> beI32(pard, 128)?.let { it / 65536.0 }
            ParamKind.FLOAT_SLIDER -> beF32(pard, 108)?.toDouble()
            ParamKind.INT_SLIDER -> beI32(pard, 128)?.toDouble()
            else -> null
        }

        /** The smallest valid value of a float slider, the `f32` at byte 104 of `pard`. */
        fun validMin(): Double? =
            if (kind == ParamKind.FLOAT_SLIDER) beF32(pard, 104)?.toDouble() else null

        /** For a popup: the number of items (`u16` at byte 60 of `pard`). */
        fun popupItemCount(): Int? {
            if (kind != ParamKind.POPUP) return null
            return beU16(pard, 60)
        }

        /** For a Layer Control: what the stored layer id means for the layer that owns
         * the effect. `None` for other types and for the implicit first parameter. */
        fun layerRef(ownerLayerId: Long): LayerRef? {
            if (kind != ParamKind.LAYER || index == 0) return null
            return when (val v = storedValue()) {
                is ParamValue.Layer -> when {
                    v.id == 0L -> LayerRef.Nothing
                    v.id == ownerLayerId -> LayerRef.This
                    else -> LayerRef.Layer(v.id)
                }
                else -> null
            }
        }
    }

    /** The effects on the layer, in stack order (`ADBE Effect Parade`). */
    fun effectsOf(layer: AepTypedView.Layer): List<Effect> {
        val root = layer.properties() ?: return emptyList()
        val parade = (root.get("ADBE Effect Parade") as? AepTypedView.PropNode.Group)?.group
            ?: return emptyList()
        return parade.entries().mapNotNull { entry ->
            val chunk = (entry.node as? AepTypedView.PropNode.Other)?.chunk ?: return@mapNotNull null
            if (chunk.isList("sspc")) Effect(entry.matchName, chunk) else null
        }
    }

    /** The borrowable effect definitions by match name. */
    class EffectDefinitions(
        /** The project-level copies (`EfdG`). */
        private val byName: Map<String, AepContainer.Chunk>,
        /** For a type without a project-level copy: the own `parT` of the first instance. */
        private val instances: Map<String, AepContainer.Chunk>
    ) {
        /** Where the parameter definitions of `effect` come from. */
        fun source(effect: Effect): DefinitionSource = when {
            effect.hasOwnDefinitions() -> DefinitionSource.INSTANCE
            byName.containsKey(effect.matchName) -> DefinitionSource.PROJECT
            instances.containsKey(effect.matchName) -> DefinitionSource.OTHER_INSTANCE
            else -> DefinitionSource.MISSING
        }

        /** The parameters of `effect`: from its own `parT`, or, when that is empty,
         * from the project-level definition of its match name, else from another
         * instance of it that has its own. Values always come from the instance's
         * own records. */
        fun params(effect: Effect): List<EffectParam> {
            if (effect.hasOwnDefinitions()) return effect.params()
            val part = byName[effect.matchName] ?: instances[effect.matchName] ?: return emptyList()
            return effect.paramsFrom(part, true)
        }

        companion object {
            /** The project-level effect definitions (`LIST EfdG`): one entry per effect
             * type, the match name and the `LIST parT` of the copied instance. */
            fun of(project: AepTypedView.Project): EffectDefinitions {
                val byName = LinkedHashMap<String, AepContainer.Chunk>()
                val defs = project.effectDefs
                if (defs != null) {
                    for (df in defs.children().filter { it.isList("EfDf") }) {
                        var name: String? = null
                        for (c in df.children()) {
                            if (c.hasId("tdmn")) {
                                name = c.bytes()?.let { bytesToText(untilNul(it)) }
                            } else if (c.isList("sspc") && name != null) {
                                val part = c.childList("parT")
                                    ?.takeIf { p -> p.children().any { it.hasId("pard") } }
                                if (part != null && !byName.containsKey(name)) {
                                    byName[name] = part
                                }
                                name = null
                            }
                        }
                    }
                }
                val instances = LinkedHashMap<String, AepContainer.Chunk>()
                for (item in project.allItems()) {
                    val comp = item.comp() ?: continue
                    for (layer in comp.layers()) {
                        for (fx in effectsOf(layer)) {
                            if (byName.containsKey(fx.matchName) || instances.containsKey(fx.matchName)) continue
                            val part = fx.chunk.childList("parT")
                                ?.takeIf { p -> p.children().any { it.hasId("pard") } }
                                ?: continue
                            instances[fx.matchName] = part
                        }
                    }
                }
                return EffectDefinitions(byName, instances)
            }
        }
    }
}
