package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Camera Options of a camera layer, mirroring the official importer's
 * camera.rs. Stored values win; the defaults follow the official order:
 * zoom from the auto Position's -z, focus distance from the zoom, aperture
 * from the 50 mm preset. Blur Level at its default (100) is skipped.
 *
 * The import returns option values by canonical prop id; the patch step
 * writes them into the converter's cameraOptions group.
 */
object AepCameraImport {

    /** After Effects' 50 mm camera aperture in a 1920 px wide composition. */
    const val APERTURE_1920 = 25.3093363329584

    /** The 50 mm preset zoom of a new camera in a composition this wide. */
    fun defaultZoom(compWidth: Double): Double = compWidth * 50.0 / 36.0

    /** The camera Position record: its stored value and whether it is the auto state. */
    data class CameraPosition(val stored: List<Double>, val autoState: Boolean)

    /** One imported option: its canonical value, keys and expression. */
    data class CameraOption(
        val value: JSONObject,
        val keys: JSONArray? = null,
        val expr: Pair<String, Boolean>? = null
    )

    /** The Position of a camera layer (the Transform group's ADBE Position). */
    fun cameraPosition(transformGroup: AepTypedView.PropGroup?): CameraPosition? {
        val node = transformGroup?.get("ADBE Position") as? AepTypedView.PropNode.Property
            ?: return null
        val property = node.property
        val stored = property.staticValue() ?: return null
        if (stored.size != 3) return null
        return CameraPosition(stored, isAutoState(property))
    }

    /** The auto state: `tdb4` bytes 76 and 77 are 1 and the property has no expression. */
    fun isAutoState(property: AepTypedView.Property): Boolean {
        val flags = property.expressionFlags() ?: return false
        return flags.byte76 == 1 && flags.byte77 == 1 && property.expression() == null
    }

    /** True when the property holds a value the file wrote: animated, flags 1, or an expression. */
    private fun isStored(property: AepTypedView.Property): Boolean =
        property.isAnimated() || property.flags() == 1L || property.expression() != null

    /** The camera options the file maps, by match name: (canonical id, kind). */
    private val OPTIONS: Map<String, Pair<String, AepEffectKeys.Kind>> = mapOf(
        "ADBE Camera Zoom" to ("zoom" to AepEffectKeys.Kind.Number(1.0)),
        "ADBE Camera Depth of Field" to ("dof" to AepEffectKeys.Kind.Bool),
        "ADBE Camera Focus Distance" to ("focusDistance" to AepEffectKeys.Kind.Number(1.0)),
        "ADBE Camera Aperture" to ("aperture" to AepEffectKeys.Kind.Number(1.0))
    )

    /**
     * Imports the options of a camera layer. The returned map holds only the
     * options the file specifies plus the official fallbacks (zoom, focus
     * distance, aperture); anything absent stays at the converter default.
     */
    fun importCameraOptions(
        optionsGroup: AepTypedView.PropGroup?,
        position: CameraPosition?,
        what: String,
        compWidth: Double,
        timescale: Long,
        col: AepImportReport.Collector
    ): Map<String, CameraOption> {
        val out = LinkedHashMap<String, CameraOption>()
        if (optionsGroup != null) {
            for (entry in optionsGroup.entries()) {
                val property = (entry.node as? AepTypedView.PropNode.Property)?.property
                    ?: continue
                val mapped = OPTIONS[entry.matchName]
                if (mapped == null) {
                    // Blur Level at its default is skipped by the official importer;
                    // any other stored value the table does not know is a loss.
                    if (entry.matchName == "ADBE Camera Blur Level" &&
                        property.staticValue() == listOf(100.0)
                    ) continue
                    if (isStored(property)) {
                        col.lose(
                            AepImportReport.LossCategory.TransformProperties,
                            "$what: camera option ${entry.matchName} is not imported"
                        )
                    }
                    continue
                }
                val (id, kind) = mapped
                importOption(property, id, kind, what, timescale, col)?.let { out[id] = it }
            }
        }
        // The official fallback order: zoom from the auto Position, focus
        // distance from the zoom, aperture from the 50 mm preset.
        val zoom = out["zoom"]?.value?.optDouble("v")
            ?: if (position?.autoState == true && position.stored.size == 3) -position.stored[2]
            else null
        if (zoom != null && "zoom" !in out) {
            out["zoom"] = CameraOption(AepEffectKeys.Kind.Number(1.0).value(listOf(zoom))!!)
        }
        val focus = out["focusDistance"]?.value?.optDouble("v") ?: zoom
        if (focus != null && "focusDistance" !in out) {
            out["focusDistance"] = CameraOption(
                AepEffectKeys.Kind.Number(1.0).value(listOf(focus))!!
            )
        }
        if ("aperture" !in out) {
            out["aperture"] = CameraOption(
                AepEffectKeys.Kind.Number(1.0).value(
                    listOf(APERTURE_1920 * compWidth / 1920.0)
                )!!
            )
        }
        return out
    }

    private fun importOption(
        property: AepTypedView.Property,
        id: String,
        kind: AepEffectKeys.Kind,
        what: String,
        timescale: Long,
        col: AepImportReport.Collector
    ): CameraOption? {
        if (!isStored(property)) return null
        val exprText = property.expression()
        val exprEnabled = property.expressionEnabled()
        val expr = if (exprText != null && exprEnabled != null) exprText to exprEnabled else null
        if (property.isAnimated()) {
            if (kind is AepEffectKeys.Kind.Bool) {
                // Keys on a checkbox are not sampled by the corpus.
                col.lose(
                    AepImportReport.LossCategory.TransformProperties,
                    "$what: the keyframes of camera option $id are not imported (not decoded), default used"
                )
                return null
            }
            val imported = AepEffectKeys.importKeys(property, kind, what, id, timescale, col)
            if (imported == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: camera option $id has no readable keyframe, default used"
                )
                return null
            }
            return CameraOption(imported.firstValue, imported.keys, expr)
        }
        val value = property.staticValue() ?: run {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: camera option $id holds an unreadable value, default used"
            )
            return null
        }
        val converted = kind.value(value) ?: run {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: camera option $id holds a value that is not valid ($value), default used"
            )
            return null
        }
        return CameraOption(converted, null, expr)
    }
}
