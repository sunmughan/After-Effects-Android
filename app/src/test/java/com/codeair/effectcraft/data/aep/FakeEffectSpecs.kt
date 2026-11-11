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
    private fun bool(v: Boolean) = JSONObject().put("t", "Bool").put("v", v)
    private fun vec2(x: Double, y: Double) =
        JSONObject().put("t", "Vec2").put("v", JSONArray().put(x).put(y))
    private fun vec3(x: Double, y: Double, z: Double) =
        JSONObject().put("t", "Vec3").put("v", JSONArray().put(x).put(y).put(z))
    private fun layerProp(match: String): JSONObject =
        prop(match, JSONObject().put("t", "Layer").put("v", JSONObject.NULL), JSONObject().put("kind", "Layer"))

    private fun subgroup(match: String, name: String, children: List<JSONObject>): JSONObject =
        JSONObject()
            .put("node", "Group")
            .put("uid", 0)
            .put("match", match)
            .put("name", name)
            .put("kind", JSONObject().put("kind", "Plain"))
            .put("children", JSONArray(children))
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
            "ec.control.slider" -> group(
                "ec.control.slider", instanceName, listOf(
                    prop("slider", scalar(0.0))
                )
            )
            // Skeletons for the nine effects the real file uses (Digital_Slides_CC_2021_61_5ie6.aep):
            // defaults and popup options from the pinned `effectcraft-effects` spec.
            "ec.stylize.motiontile" -> group(
                "ec.stylize.motiontile", instanceName, listOf(
                    prop("tileCenter", vec2(0.5, 0.5), JSONObject().put("kind", "Point")),
                    prop("tileWidth", scalar(100.0)),
                    prop("tileHeight", scalar(100.0)),
                    prop("outputWidth", scalar(100.0)),
                    prop("outputHeight", scalar(100.0)),
                    prop("mirrorEdges", bool(false), JSONObject().put("kind", "Checkbox")),
                    prop("phase", scalar(0.0)),
                    prop("horizontalPhaseShift", bool(false), JSONObject().put("kind", "Checkbox"))
                )
            )
            "ec.blur.fastbox" -> group(
                "ec.blur.fastbox", instanceName, listOf(
                    prop("radius", scalar(0.0)),
                    prop("iterations", scalar(3.0)),
                    popupProp(
                        "dimensions",
                        listOf("Horizontal and Vertical", "Horizontal", "Vertical"), 0
                    ),
                    prop("repeatEdge", bool(false), JSONObject().put("kind", "Checkbox"))
                )
            )
            "ec.time.posterizetime" -> group(
                "ec.time.posterizetime", instanceName, listOf(
                    prop("frameRate", scalar(12.0))
                )
            )
            "ec.control.point3d" -> group(
                "ec.control.point3d", instanceName, listOf(
                    prop("point", vec3(0.0, 0.0, 0.0), JSONObject().put("kind", "Point3"))
                )
            )
            "ec.noise.noise" -> group(
                "ec.noise.noise", instanceName, listOf(
                    prop("amount", scalar(0.0)),
                    prop("color", bool(true), JSONObject().put("kind", "Checkbox")),
                    prop("clip", bool(true), JSONObject().put("kind", "Checkbox"))
                )
            )
            "ec.distort.opticscompensation" -> group(
                "ec.distort.opticscompensation", instanceName, listOf(
                    prop("fieldOfView", scalar(0.0)),
                    prop("reverseLensDistortion", bool(false), JSONObject().put("kind", "Checkbox")),
                    popupProp(
                        "fovOrientation", listOf("Horizontal", "Vertical", "Diagonal"), 0
                    ),
                    prop("viewCenter", vec2(0.5, 0.5), JSONObject().put("kind", "Point")),
                    prop("optimalPixels", bool(false), JSONObject().put("kind", "Checkbox")),
                    popupProp("resize", listOf("Off", "Max 2X", "Max 4X", "Unlimited"), 0)
                )
            )
            "ec.color.colorbalance" -> group(
                "ec.color.colorbalance", instanceName, listOf(
                    prop("shadowRed", scalar(0.0)),
                    prop("shadowGreen", scalar(0.0)),
                    prop("shadowBlue", scalar(0.0)),
                    prop("midRed", scalar(0.0)),
                    prop("midGreen", scalar(0.0)),
                    prop("midBlue", scalar(0.0)),
                    prop("hiRed", scalar(0.0)),
                    prop("hiGreen", scalar(0.0)),
                    prop("hiBlue", scalar(0.0)),
                    prop("preserveLuminosity", bool(false), JSONObject().put("kind", "Checkbox"))
                )
            )
            "ec.blur.cameralens" -> group(
                "ec.blur.cameralens", instanceName, listOf(
                    prop("blurRadius", scalar(10.0)),
                    subgroup(
                        "irisProperties", "Iris Properties", listOf(
                            popupProp(
                                "irisShape",
                                listOf(
                                    "Triangle", "Square", "Pentagon", "Hexagon", "Heptagon",
                                    "Octagon", "Nonagon", "Decagon", "Circle"
                                ), 3
                            ),
                            prop("irisRoundness", scalar(0.0)),
                            prop("irisAspectRatio", scalar(1.0)),
                            prop("irisRotation", scalar(0.0)),
                            prop("diffractionFringe", scalar(0.0))
                        )
                    ),
                    subgroup(
                        "blurMap", "Blur Map", listOf(
                            layerProp("blurMapLayer"),
                            prop(
                                "blurMapLayerSource",
                                JSONObject().put("t", "Enum").put("v", 0)
                            ),
                            popupProp("channel", listOf("Luminance", "Alpha", "Color"), 0),
                            popupProp("placement", listOf("Center", "Stretch"), 1),
                            prop("blurFocalDistance", scalar(0.0)),
                            prop("invertBlurMap", bool(false), JSONObject().put("kind", "Checkbox"))
                        )
                    ),
                    subgroup(
                        "highlight", "Highlight", listOf(
                            prop("specularBrightness", scalar(0.0)),
                            prop("specularThreshold", scalar(255.0)),
                            prop("highlightSaturation", scalar(100.0))
                        )
                    ),
                    prop("repeatEdge", bool(false), JSONObject().put("kind", "Checkbox")),
                    prop("useLinear", bool(false), JSONObject().put("kind", "Checkbox"))
                )
            )
            "ec.distort.wavewarp" -> group(
                "ec.distort.wavewarp", instanceName, listOf(
                    popupProp(
                        "waveType",
                        listOf(
                            "Sine", "Square", "Triangle", "Sawtooth", "Circle", "Semicircle",
                            "Uncircle", "Noise", "Smooth Noise"
                        ), 0
                    ),
                    prop("height", scalar(10.0)),
                    prop("width", scalar(40.0)),
                    prop("direction", scalar(90.0)),
                    prop("speed", scalar(1.0)),
                    popupProp(
                        "pinning",
                        listOf(
                            "None", "All Edges", "Center", "Left Edge", "Top Edge", "Right Edge",
                            "Bottom Edge", "Horizontal Edges", "Vertical Edges"
                        ), 0
                    ),
                    prop("phase", scalar(0.0))
                )
            )
            else -> null
        }
}
