package com.codeair.effectcraft.data.aep

import org.json.JSONArray
import org.json.JSONObject

/** Fake effect-group skeletons for tests, standing in for the native effect registry
 * (which needs the .so). Group uids are numbered from 1, as the native engine does. */
internal class FakeEffectSpecs : AepEffectImport.EffectSpecProvider {

    private fun prop(match: String, value: JSONObject, ui: JSONObject? = null): JSONObject {
        val obj = JSONObject()
            .put("node", "Prop")
            .put("uid", 0)
            .put("match", match)
            .put("name", match)
            .put("value", value)
            .put("keys", JSONArray())
            .put("ui", ui ?: JSONObject().put("kind", "Number"))
            .put("spatial", false)
            .put("hold_only", false)
        return obj
    }

    private fun popupProp(match: String, options: List<String>, default: Int): JSONObject {
        val ui = JSONObject().put("kind", "Popup")
            .put("options", JSONArray(options))
        return prop(match, JSONObject().put("t", "Enum").put("v", default), ui)
    }

    private fun group(match: String, name: String, children: List<JSONObject>): JSONObject {
        val group = JSONObject()
            .put("node", "Group")
            .put("uid", 0)
            .put("match", match)
            .put("name", name)
            .put("kind", JSONObject().put("kind", "Effect").put("effect", match))
            .put("enabled", true)
            .put("children", JSONArray(children))
        var next = 1
        fun number(node: JSONObject) {
            node.put("uid", next++)
            val kids = node.optJSONArray("children") ?: return
            for (i in 0 until kids.length()) number(kids.getJSONObject(i))
        }
        number(group)
        return group
    }

    private fun scalar(v: Double) = JSONObject().put("t", "Scalar").put("v", v)
    private fun color(r: Double, g: Double, b: Double) =
        JSONObject().put("t", "Color").put("v", JSONArray().put(r).put(g).put(b).put(1.0))

    override fun instantiate(effectId: String, instanceName: String, layerWidth: Double, layerHeight: Double): JSONObject? =
        when (effectId) {
            "ec.color.levels" -> group(
                "ec.color.levels", instanceName, listOf(
                    popupProp("channel", listOf("RGB", "Red", "Green", "Blue", "Alpha"), 0),
                    prop("inBlack", scalar(0.0)),
                    prop("inWhite", scalar(1.0)),
                    prop("gamma", scalar(1.0)),
                    prop("outBlack", scalar(0.0)),
                    prop("outWhite", scalar(1.0)),
                    popupProp("clipToOutputBlack", listOf("On", "Off", "Off for 32 bpc Color"), 2),
                    popupProp("clipToOutputWhite", listOf("On", "Off", "Off for 32 bpc Color"), 2)
                )
            )
            "ec.stylize.glow" -> group(
                "ec.stylize.glow", instanceName, listOf(
                    popupProp("based", listOf("Alpha Channel", "Color Channels"), 1),
                    prop("threshold", scalar(60.0)),
                    prop("radius", scalar(0.0)),
                    prop("intensity", scalar(0.0)),
                    popupProp("operation", listOf("On Top", "Behind", "None"), 0),
                    popupProp(
                        "glowOperation",
                        listOf(
                            "None", "Normal", "Add", "Multiply", "Dissolve", "Screen", "Overlay",
                            "Soft Light", "Hard Light", "Darken", "Lighten", "Difference", "Hue",
                            "Saturation", "Color", "Luminosity", "Color Dodge", "Color Burn",
                            "Exclusion", "Stencil Alpha", "Stencil Luma", "Silhouette Alpha",
                            "Silhouette Luma", "Luminescent Premultiply", "Alpha Add"
                        ), 2
                    ),
                    popupProp("colors", listOf("Original Colors", "A & B Colors", "Arbitrary Map"), 0),
                    popupProp(
                        "colorLooping",
                        listOf("Sawtooth A>B", "Sawtooth B>A", "Triangle A>B>A", "Triangle B>A>B"), 2
                    ),
                    prop("colorLoops", scalar(0.0)),
                    prop("colorPhase", scalar(0.0)),
                    prop("abMidpoint", scalar(50.0)),
                    prop("colorA", color(1.0, 1.0, 1.0)),
                    prop("colorB", color(0.0, 0.0, 0.0)),
                    popupProp(
                        "glowDimensions",
                        listOf("Horizontal and Vertical", "Horizontal", "Vertical"), 0
                    )
                )
            )
            "ec.control.color" -> group(
                "ec.control.color", instanceName, listOf(
                    prop("color", color(1.0, 0.0, 0.0), JSONObject().put("kind", "Color"))
                )
            )
            else -> null
        }
}
