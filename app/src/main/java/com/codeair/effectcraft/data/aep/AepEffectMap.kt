package com.codeair.effectcraft.data.aep

/**
 * The effect table: an After Effects effect (its match name) and its parameters (by
 * their index in `parT`) map to an EffectCraft effect, a parameter id and a unit
 * conversion.
 *
 * Port of `container/effect_map.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Plain data; the evidence for each row
 * is in the official repo's `docs/aep-format.md` 3.7 and the case numbers in the
 * comments.
 */
object AepEffectMap {

    /** How a stored parameter value becomes an EffectCraft value. */
    sealed class Conv {
        /** A colour (ARGB 0..255 or the reader's ARGB fractions) to opaque RGBA 0..1. */
        object Color : Conv()

        /** A point (fraction of the layer size) to layer pixels. */
        object Point : Conv()

        /** A slider shown as a percent here: the stored value is a fraction of [validMax]. */
        class Percent(val validMax: Double) : Conv()

        /** A slider whose stored number is used as it is ([validMax] checks the scale). */
        class Plain(val validMax: Double) : Conv()

        /** A slider whose stored number times [factor] is the value here ([validMax] checks the scale). */
        class Factor(val validMax: Double, val factor: Double) : Conv()

        /** An angle in degrees. */
        object Angle : Conv()

        /** A 3-component vector in stored units (3D Point Control: three doubles). */
        object Vec3 : Conv()

        /**
         * A popup, mapped by item name; [default] is After Effects' default (1-based)
         * for a fresh effect, used when the file's default slot cannot be trusted.
         * [items] are After Effects' items as the corpus stores them (`pdnm`): used when
         * a file has no `pdnm` for the popup and its item count (`pard` 60) matches.
         */
        class Popup(val default: Long, val items: List<String>) : Conv()

        /** A checkbox (stored 1.0 when on). */
        object Checkbox : Conv()

        /**
         * A layer (the `tdpi` id of a layer of the same composition, 0 = None).
         * [own] says what the owning layer itself becomes: itself (Layer Control) or
         * EffectCraft's unset layer (effects whose unset layer means "this layer").
         */
        class LayerRef(val own: Boolean) : Conv()

        /** A parameter that holds no value to import (Levels (Individual Controls)' histogram). */
        object Skip : Conv()
    }

    /** One mapped parameter. */
    data class ParamMap(
        /** The index in `parT` (0 is the input layer). */
        val index: Int,
        /** The parameter type the file must declare. */
        val kind: AepEffects.ParamKind,
        /** The EffectCraft parameter id. */
        val target: String,
        val conv: Conv,
        /**
         * The range EffectCraft's effect works in when it is narrower than the range After
         * Effects accepts (the effect limits the value there): a stored value or key outside
         * it is imported as it is and reported. Null: no narrower range.
         */
        val limit: Pair<Double, Double>? = null
    )

    /** Values of an effect that are not ordinary parameters. */
    enum class Extra {
        NONE,
        /** Hue/Saturation's master values in the Channel Range data. */
        HUE_SATURATION,
        /** Levels' values in the Histogram data. */
        LEVELS,
        /** Curves' five curves. */
        CURVES,
        /** Mesh Warp's Distortion Mesh. */
        MESH_WARP,
        /** The Cryptomatte plug-in: the layer and selection in its Settings data. */
        CRYPTOMATTE
    }

    /** One mapped effect. */
    data class EffectMap(
        val matchName: String,
        /** The EffectCraft effect id. */
        val effect: String,
        val params: List<ParamMap>,
        val extra: Extra = Extra.NONE
    )

    private fun pm(index: Int, kind: AepEffects.ParamKind, target: String, conv: Conv) =
        ParamMap(index, kind, target, conv)

    private fun pmLimited(
        index: Int,
        kind: AepEffects.ParamKind,
        target: String,
        conv: Conv,
        lo: Double,
        hi: Double
    ) = ParamMap(index, kind, target, conv, lo to hi)

    private fun fx(matchName: String, effect: String, params: List<ParamMap>) =
        EffectMap(matchName, effect, params)

    // After Effects' popup items as the corpus stores them (`pdnm`).
    val RAMP_SHAPE = listOf("Linear Ramp", "Radial Ramp")
    val EXPOSURE_CHANNELS = listOf("Master", "Individual Channels")
    val INVERT_CHANNEL = listOf(
        "RGB", "Red", "Green", "Blue", "(-", "HLS", "Hue", "Lightness", "Saturation", "(-",
        "YIQ", "Luminance", "In Phase Chrominance", "Quadrature Chrominance", "(-", "Alpha"
    )
    val BLUR_DIMENSIONS = listOf("Horizontal and Vertical", "Horizontal", "Vertical")
    val TIMEWARP_METHOD = listOf("Whole Frames", "Frame Mix", "Pixel Motion")
    val TIMEWARP_ADJUST = listOf("Speed", "Source Frame")
    val GLOW_BASED_ON = listOf("Alpha Channel", "Color Channels")
    val GLOW_COMPOSITE = listOf("On Top", "Behind", "None")
    val GLOW_OPERATION = listOf(
        "None", "Normal", "Add", "Multiply", "Dissolve", "Screen", "Overlay", "Soft Light",
        "Hard Light", "Darken", "Lighten", "Difference", "Hue", "Saturation", "Color",
        "Luminosity", "Color Dodge", "Color Burn", "Exclusion", "Stencil Alpha", "Stencil Luma",
        "Silhouette Alpha", "Silhouette Luma", "Luminescent Premultiply", "Alpha Add"
    )
    val GLOW_COLORS = listOf("Original Colors", "A & B Colors", "Arbitrary Map")
    val GLOW_LOOPING = listOf("Sawtooth A>B", "Sawtooth B>A", "Triangle A>B>A", "Triangle B>A>B")
    val HUE_SAT_CHANNEL = listOf("Master", "Reds", "Yellows", "Greens", "Cyans", "Blues", "Magentas")
    val LEVELS_CHANNEL = listOf("RGB", "Red", "Green", "Blue", "Alpha")
    val LEVELS_CLIP = listOf("On", "Off", "Off for 32 bpc Color")
    val CRYPTOMATTE_OUTPUT = listOf("Colors", "Matted Colors", "Matted RGBA", "Matte Only")
    val RADIAL_TYPE = listOf("Spin", "Zoom")
    val RADIAL_AA = listOf("Low", "High")
    val CC_RADIAL_TYPE = listOf("Straight Zoom", "Fading Zoom", "Centered Zoom", "(-", "Rotate", "Scratch", "Rotate Fading")
    val LUMA_KEY_TYPE = listOf("Key Out Brighter", "Key Out Darker", "Key Out Similar", "Key Out Dissimilar")
    val CHOKER_VIEW = listOf("Final Output", "Matte")
    val SAMPLING = listOf("Bilinear", "Bicubic")
    val TURB_DISPLACEMENT = listOf(
        "Turbulent", "Bulge", "Twist", "(-", "Turbulent Smoother", "Bulge Smoother",
        "Twist Smoother", "(-", "Vertical Displacement", "Horizontal Displacement", "Cross Displacement"
    )
    val TURB_PINNING = listOf(
        "None", "(-", "Pin All", "Pin Horizontal", "Pin Vertical", "Pin Left", "Pin Right",
        "Pin Top", "Pin Bottom", "(-", "Pin All Locked", "Pin Horizontal Locked",
        "Pin Vertical Locked", "Pin Left Locked", "Pin Right Locked", "Pin Top Locked", "Pin Bottom Locked"
    )
    val FRACTAL_TYPE = listOf(
        "Basic", "(-", "Turbulent Smooth", "Turbulent Basic", "Turbulent Sharp", "(-",
        "Dynamic", "Dynamic Progressive", "Dynamic Twist", "(-", "Max", "Smeary", "Swirly",
        "Rocky", "Cloudy", "Terrain", "Subscale", "Small Bumps", "Strings", "Threads"
    )
    val NOISE_TYPE = listOf("Block", "Linear", "Soft Linear", "Spline")
    val OVERFLOW = listOf("Clip", "Soft Clamp", "Wrap Back", "Allow HDR Results")
    val FRACTAL_BLEND = listOf(
        "None", "Normal", "(-", "Add", "Multiply", "Screen", "Overlay", "Soft Light",
        "Hard Light", "(-", "Color Dodge", "Color Burn", "(-", "Darken", "Lighten",
        "Difference", "Exclusion", "(-", "Hue", "Saturation", "Luminosity"
    )
    val FOUR_COLOR_BLEND = listOf(
        "None", "Normal", "(-", "Add", "Multiply", "Screen", "Overlay", "Soft Light",
        "Hard Light", "(-", "Color Dodge", "Color Burn", "(-", "Darken", "Lighten",
        "Difference", "Exclusion", "(-", "Hue", "Saturation", "Color", "Luminosity"
    )
    val GRID_BLEND = listOf(
        "None", "Normal", "Stencil Alpha", "(-", "Add", "Multiply", "Screen", "Overlay",
        "Soft Light", "Hard Light", "(-", "Color Dodge", "Color Burn", "(-", "Darken",
        "Lighten", "Difference", "Exclusion", "(-", "Hue", "Saturation", "Color", "Luminosity"
    )
    val FAST_ZOOM = listOf("Standard", "Brightest", "Darkest")
    val GRID_SIZE_FROM = listOf("Corner Point", "Width Slider", "Width & Height Sliders")
    val KEY_VIEW = listOf("Final Output", "Source Only", "Matte Only")
    val MATCH_COLORS = listOf("Using RGB", "Using Hue", "Using Chroma")
    val KEY_OPERATION = listOf("Key Colors", "Keep Colors")
    val WIPE = listOf("Clockwise", "Counterclockwise", "Both")
    val BEND_PRESTART = listOf("None", "Static", "Bend", "Mirror")
    val CHANGE_VIEW = listOf("Corrected Layer", "Color Correction Mask")
    val EXTRACT_CHANNEL = listOf("Luminance", "Red", "Green", "Blue", "Alpha")
    val NOISE_HLS = listOf("Uniform", "Squared", "Grain")
    val SCATTER_GRAIN = listOf("Both", "Horizontal", "Vertical")
    val SWEEP_SHAPE = listOf("Linear", "Smooth", "Sharp")
    val SWEEP_RECEPTION = listOf("Add", "Composite", "Cutout")
    val DISPLACE_USE = listOf("Red", "Green", "Blue", "Alpha", "Luminance", "Hue", "Lightness", "Saturation", "Full", "Half", "Off")
    val DISPLACE_BEHAVIOR = listOf("Center Map", "Stretch Map to Fit", "Tile Map")
    val MATTE_USE = listOf("Red Channel", "Green Channel", "Blue Channel", "Alpha Channel", "Luminance", "Hue", "Lightness", "Saturation", "Full", "Off")
    val SHUTTER_CONTROL = listOf("Manual", "Automatic")
    val ECHO_OPERATOR = listOf("Add", "Maximum", "Minimum", "Screen", "Composite In Back", "Composite In Front", "Blend")
    val STABILIZE = listOf("Brightness", "Levels", "Curves")
    val OPTICS_ORIENTATION = listOf("Horizontal", "Vertical", "Diagonal")
    val OPTICS_RESIZE = listOf("Off", "Max 2X", "Max 4X", "Unlimited")
    val IRIS_SHAPE = listOf(
        "Triangle", "Square", "Pentagon", "Hexagon", "Heptagon", "Octagon", "Nonagon", "Decagon"
    )
    val LENS_CHANNEL = listOf("Luminance", "Red", "Green", "Blue", "Alpha")
    val LENS_PLACEMENT = listOf("Center Map", "Stretch Map to Fit")
    val WAVE_TYPES = listOf(
        "Sine", "Square", "Triangle", "Sawtooth", "Circle", "Semicircle", "Uncircle", "Noise",
        "Smooth Noise"
    )
    val WAVE_PINNING = listOf(
        "None", "All Edges", "Center", "(-", "Left Edge", "Top Edge", "Right Edge", "Bottom Edge",
        "(-", "Horizontal Edges", "Vertical Edges"
    )

    /** Exposure's three sliders, by their valid maximum in the file. */
    val EXPOSURE = Conv.Plain(100.0)
    val OFFSET = Conv.Plain(2.0)
    val GAMMA = Conv.Plain(10.0)

    /** The valid maximum of Glow's A & B Midpoint (16.16 of 0.99). */
    const val GLOW_MIDPOINT_MAX = 0.9900054931640625

    /** The effects the importer converts. */
    val EFFECTS: List<EffectMap> = listOf(
        // Tint: Map Black To, Map White To, Amount to Tint; 4 = Swap Colors (a button).
        fx(
            "ADBE Tint", "ec.color.tint", listOf(
                pm(1, AepEffects.ParamKind.COLOR, "black", Conv.Color),
                pm(2, AepEffects.ParamKind.COLOR, "white", Conv.Color),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Percent(100.0))
            )
        ),
        // Gradient Ramp: 8 = Swap Colors (a button).
        fx(
            "ADBE Ramp", "ec.generate.gradientramp", listOf(
                pm(1, AepEffects.ParamKind.POINT, "start", Conv.Point),
                pm(2, AepEffects.ParamKind.COLOR, "startColor", Conv.Color),
                pm(3, AepEffects.ParamKind.POINT, "end", Conv.Point),
                pm(4, AepEffects.ParamKind.COLOR, "endColor", Conv.Color),
                pm(5, AepEffects.ParamKind.POPUP, "shape", Conv.Popup(1, RAMP_SHAPE)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "scatter", Conv.Plain(512.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "blend", Conv.Percent(1.0))
            )
        ),
        // Exposure: 1 Channels; Master 2-6, Red 7-11, Green 12-16, Blue 17-21, each
        // [group start, Exposure, Offset, Gamma Correction, group end]; 22 Bypass Linear Light
        // Conversion. Valid maxima: Exposure 100 (stops), Offset 2, Gamma Correction 10.
        fx(
            "ADBE Exposure2", "ec.color.exposure", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "channels", Conv.Popup(1, EXPOSURE_CHANNELS)),
                pm(3, AepEffects.ParamKind.FLOAT_SLIDER, "master/exposure", EXPOSURE),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "master/offset", OFFSET),
                pm(5, AepEffects.ParamKind.FLOAT_SLIDER, "master/gamma", GAMMA),
                pm(8, AepEffects.ParamKind.FLOAT_SLIDER, "red/redExposure", EXPOSURE),
                pm(9, AepEffects.ParamKind.FLOAT_SLIDER, "red/redOffset", OFFSET),
                pm(10, AepEffects.ParamKind.FLOAT_SLIDER, "red/redGamma", GAMMA),
                pm(13, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenExposure", EXPOSURE),
                pm(14, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenOffset", OFFSET),
                pm(15, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenGamma", GAMMA),
                pm(18, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueExposure", EXPOSURE),
                pm(19, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueOffset", OFFSET),
                pm(20, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueGamma", GAMMA),
                pm(22, AepEffects.ParamKind.CHECKBOX, "bypassLinearLight", Conv.Checkbox)
            )
        ),
        // Invert: Channel (16 items with separators), Blend With Original (percent).
        fx(
            "ADBE Invert", "ec.channel.invert", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "channel", Conv.Popup(1, INVERT_CHANNEL)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "blend", Conv.Plain(100.0))
            )
        ),
        // Fast Box Blur.
        fx(
            "ADBE Box Blur2", "ec.blur.fastbox", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "radius", Conv.Plain(30000.0)),
                pm(2, AepEffects.ParamKind.INT_SLIDER, "iterations", Conv.Plain(50.0)),
                pm(3, AepEffects.ParamKind.POPUP, "dimensions", Conv.Popup(1, BLUR_DIMENSIONS)),
                pm(4, AepEffects.ParamKind.CHECKBOX, "repeatEdge", Conv.Checkbox)
            )
        ),
        // Gaussian Blur.
        fx(
            "ADBE Gaussian Blur 2", "ec.blur.gaussian", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "blurriness", Conv.Plain(30000.0)),
                pm(2, AepEffects.ParamKind.POPUP, "dimensions", Conv.Popup(1, BLUR_DIMENSIONS)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "repeatEdge", Conv.Checkbox)
            )
        ),
        // Drop Shadow.
        fx(
            "ADBE Drop Shadow", "ec.perspective.dropshadow", listOf(
                pm(1, AepEffects.ParamKind.COLOR, "color", Conv.Color),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Percent(255.0)),
                pm(3, AepEffects.ParamKind.ANGLE, "direction", Conv.Angle),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "distance", Conv.Plain(4000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "softness", Conv.Plain(30000.0)),
                pm(6, AepEffects.ParamKind.CHECKBOX, "shadowOnly", Conv.Checkbox)
            )
        ),
        // Timewarp: Speed and Source Frame are plain numbers (valid max 5 000); the
        // tuning, motion blur, matte and crop parameters are not mapped (reported when stored).
        fx(
            "ADBE Timewarp", "ec.time.timewarp", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "method", Conv.Popup(3, TIMEWARP_METHOD)),
                pm(2, AepEffects.ParamKind.POPUP, "adjustTimeBy", Conv.Popup(1, TIMEWARP_ADJUST)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "speed", Conv.Plain(5000.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "sourceFrame", Conv.Plain(5000.0))
            )
        ),
        // Glow.
        fx(
            "ADBE Glo2", "ec.stylize.glow", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "based", Conv.Popup(2, GLOW_BASED_ON)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "threshold", Conv.Percent(255.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "radius", Conv.Plain(1000.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "intensity", Conv.Plain(255.0)),
                pm(5, AepEffects.ParamKind.POPUP, "operation", Conv.Popup(2, GLOW_COMPOSITE)),
                pm(6, AepEffects.ParamKind.POPUP, "glowOperation", Conv.Popup(3, GLOW_OPERATION)),
                pm(7, AepEffects.ParamKind.POPUP, "colors", Conv.Popup(1, GLOW_COLORS)),
                pm(8, AepEffects.ParamKind.POPUP, "colorLooping", Conv.Popup(3, GLOW_LOOPING)),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "colorLoops", Conv.Plain(127.0)),
                pm(10, AepEffects.ParamKind.ANGLE, "colorPhase", Conv.Angle),
                pm(11, AepEffects.ParamKind.FIXED_SLIDER, "abMidpoint", Conv.Factor(GLOW_MIDPOINT_MAX, 100.0)),
                pm(12, AepEffects.ParamKind.COLOR, "colorA", Conv.Color),
                pm(13, AepEffects.ParamKind.COLOR, "colorB", Conv.Color),
                pm(14, AepEffects.ParamKind.POPUP, "glowDimensions", Conv.Popup(1, BLUR_DIMENSIONS))
            )
        ),
        // Fill: 1 Fill Mask (a path reference, reported when set).
        fx(
            "ADBE Fill", "ec.generate.fill", listOf(
                pm(2, AepEffects.ParamKind.CHECKBOX, "allMasks", Conv.Checkbox),
                pm(3, AepEffects.ParamKind.COLOR, "color", Conv.Color),
                pm(4, AepEffects.ParamKind.CHECKBOX, "invert", Conv.Checkbox),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "horizontalFeather", Conv.Plain(999.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "verticalFeather", Conv.Plain(999.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Percent(1.0))
            )
        ),
        // Hue/Saturation: the master values are arbitrary data (parameter 2).
        EffectMap(
            "ADBE HUE SATURATION", "ec.color.huesaturation", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "channelControl", Conv.Popup(1, HUE_SAT_CHANNEL)),
                pm(6, AepEffects.ParamKind.CHECKBOX, "colorize", Conv.Checkbox),
                pm(7, AepEffects.ParamKind.ANGLE, "colorizeHue", Conv.Angle),
                pm(8, AepEffects.ParamKind.FIXED_SLIDER, "colorizeSaturation", Conv.Plain(100.0)),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "colorizeLightness", Conv.Plain(100.0))
            ), Extra.HUE_SATURATION
        ),
        // Levels: the values are arbitrary data (parameter 2).
        EffectMap(
            "ADBE Easy Levels2", "ec.color.levels", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "channel", Conv.Popup(1, LEVELS_CHANNEL)),
                pm(8, AepEffects.ParamKind.POPUP, "clipToOutputBlack", Conv.Popup(3, LEVELS_CLIP)),
                pm(9, AepEffects.ParamKind.POPUP, "clipToOutputWhite", Conv.Popup(3, LEVELS_CLIP))
            ), Extra.LEVELS
        ),
        // Curves: 1 Channel only picks the curve shown (UI), 2 the curves themselves
        // (arbitrary data, always stored).
        EffectMap(
            "ADBE CurvesCustom", "ec.color.curves",
            listOf(pm(1, AepEffects.ParamKind.POPUP, "", Conv.Skip)), Extra.CURVES
        ),
        // Mesh Warp: Rows, Columns, Quality are integer sliders (records only when not at
        // the default), 4 the Distortion Mesh (arbitrary data, always stored).
        EffectMap(
            "ADBE MESH WARP", "ec.distort.meshwarp", listOf(
                pm(1, AepEffects.ParamKind.INT_SLIDER, "rows", Conv.Plain(31.0)),
                pm(2, AepEffects.ParamKind.INT_SLIDER, "columns", Conv.Plain(31.0)),
                pm(3, AepEffects.ParamKind.INT_SLIDER, "quality", Conv.Plain(10.0))
            ), Extra.MESH_WARP
        ),
        // The Cryptomatte plug-in: 1 Settings data, 2 Output, 3 Selection Mode, imported
        // by the Cryptomatte step (`Extra.CRYPTOMATTE`).
        EffectMap("Cryptomatte", "ec.3d.cryptomatte", emptyList(), Extra.CRYPTOMATTE),
        // Directional Blur: Direction in degrees, Blur Length in pixels (valid max 1 000).
        fx(
            "ADBE Motion Blur", "ec.blur.directional", listOf(
                pm(1, AepEffects.ParamKind.ANGLE, "direction", Conv.Angle),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "length", Conv.Plain(1000.0))
            )
        ),
        // Radial Blur: 1 is a hidden entry without data; 6 Random Seed has no EffectCraft
        // parameter (reported when stored).
        fx(
            "ADBE Radial Blur", "ec.blur.radial", listOf(
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(1000.0)),
                pm(3, AepEffects.ParamKind.POINT, "center", Conv.Point),
                pm(4, AepEffects.ParamKind.POPUP, "type", Conv.Popup(1, RADIAL_TYPE)),
                pm(5, AepEffects.ParamKind.POPUP, "antialiasing", Conv.Popup(1, RADIAL_AA))
            )
        ),
        // CC Radial Blur.
        fx(
            "CC Radial Blur", "ec.blur.ccradial", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "type", Conv.Popup(6, CC_RADIAL_TYPE)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(250.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "quality", Conv.Plain(100.0)),
                pm(4, AepEffects.ParamKind.POINT, "center", Conv.Point)
            )
        ),
        // Venetian Blinds.
        fx(
            "ADBE Venetian Blinds", "ec.transition.venetian", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "completion", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.ANGLE, "direction", Conv.Angle),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "width", Conv.Plain(32000.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "feather", Conv.Plain(32000.0))
            )
        ),
        // Channel Blur: 5 Edge Behavior is the Repeat Edge Pixels checkbox (on by default).
        fx(
            "ADBE Channel Blur", "ec.blur.channel", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "redBlurriness", Conv.Plain(32767.0)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "greenBlurriness", Conv.Plain(32767.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "blueBlurriness", Conv.Plain(32767.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "alphaBlurriness", Conv.Plain(32767.0)),
                pm(5, AepEffects.ParamKind.CHECKBOX, "repeatEdge", Conv.Checkbox),
                pm(6, AepEffects.ParamKind.POPUP, "dimensions", Conv.Popup(1, BLUR_DIMENSIONS))
            )
        ),
        // Black & White: six float sliders (valid -200..300), Tint, Tint Color.
        fx(
            "ADBE Black&White", "ec.color.blackwhite", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "reds", Conv.Plain(300.0)),
                pm(2, AepEffects.ParamKind.FLOAT_SLIDER, "yellows", Conv.Plain(300.0)),
                pm(3, AepEffects.ParamKind.FLOAT_SLIDER, "greens", Conv.Plain(300.0)),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "cyans", Conv.Plain(300.0)),
                pm(5, AepEffects.ParamKind.FLOAT_SLIDER, "blues", Conv.Plain(300.0)),
                pm(6, AepEffects.ParamKind.FLOAT_SLIDER, "magentas", Conv.Plain(300.0)),
                pm(7, AepEffects.ParamKind.CHECKBOX, "tint", Conv.Checkbox),
                pm(8, AepEffects.ParamKind.COLOR, "tintColor", Conv.Color)
            )
        ),
        // Tritone.
        fx(
            "ADBE Tritone", "ec.color.tritone", listOf(
                pm(1, AepEffects.ParamKind.COLOR, "highlights", Conv.Color),
                pm(2, AepEffects.ParamKind.COLOR, "midtones", Conv.Color),
                pm(3, AepEffects.ParamKind.COLOR, "shadows", Conv.Color),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "blend", Conv.Plain(100.0))
            )
        ),
        // Luma Key: threshold and tolerance 0..255, Edge Thin an integer to 5.
        fx(
            "ADBE Luma Key", "ec.key.luma", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "keyType", Conv.Popup(2, LUMA_KEY_TYPE)),
                pm(2, AepEffects.ParamKind.INT_SLIDER, "threshold", Conv.Plain(255.0)),
                pm(3, AepEffects.ParamKind.INT_SLIDER, "tolerance", Conv.Plain(255.0)),
                pm(4, AepEffects.ParamKind.INT_SLIDER, "edgeThin", Conv.Plain(5.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "edgeFeather", Conv.Plain(100.0))
            )
        ),
        // Simple Choker.
        fx(
            "ADBE Simple Choker", "ec.matte.simplechoker", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "view", Conv.Popup(1, CHOKER_VIEW)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "chokeMatte", Conv.Plain(100.0))
            )
        ),
        // Matte Choker: the gray level softnesses are fractions (valid max 1, default 0.1
        // and 1), percentages here.
        fx(
            "ADBE Matte Choker", "ec.matte.mattechoker", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "geometricSoftness1", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.INT_SLIDER, "choke1", Conv.Plain(127.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "grayLevelSoftness1", Conv.Percent(1.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "geometricSoftness2", Conv.Plain(100.0)),
                pm(5, AepEffects.ParamKind.INT_SLIDER, "choke2", Conv.Plain(127.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "grayLevelSoftness2", Conv.Percent(1.0)),
                pm(7, AepEffects.ParamKind.INT_SLIDER, "iterations", Conv.Plain(100.0))
            )
        ),
        // Transform.
        fx(
            "ADBE Geometry2", "ec.distort.transform", listOf(
                pm(1, AepEffects.ParamKind.POINT, "anchor", Conv.Point),
                pm(2, AepEffects.ParamKind.POINT, "position", Conv.Point),
                pm(3, AepEffects.ParamKind.CHECKBOX, "uniform", Conv.Checkbox),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "scaleHeight", Conv.Plain(30000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "scaleWidth", Conv.Plain(30000.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "skew", Conv.Plain(70.0)),
                pm(7, AepEffects.ParamKind.ANGLE, "skewAxis", Conv.Angle),
                pm(8, AepEffects.ParamKind.ANGLE, "rotation", Conv.Angle),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Plain(100.0)),
                pm(10, AepEffects.ParamKind.CHECKBOX, "useCompositionShutterAngle", Conv.Checkbox),
                pm(11, AepEffects.ParamKind.FIXED_SLIDER, "shutterAngle", Conv.Plain(360.0)),
                pm(12, AepEffects.ParamKind.POPUP, "sampling", Conv.Popup(1, SAMPLING))
            )
        ),
        // Brightness & Contrast.
        fx(
            "ADBE Brightness & Contrast 2", "ec.color.brightnesscontrast", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "brightness", Conv.Plain(150.0)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "contrast", Conv.Plain(100.0)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "useLegacy", Conv.Checkbox)
            )
        ),
        // Turbulent Displace; 14 Antialiasing for Best Quality has no counterpart.
        fx(
            "ADBE Turbulent Displace", "ec.distort.turbulentdisplace", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "displacement", Conv.Popup(1, TURB_DISPLACEMENT)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(10000.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "size", Conv.Plain(1000.0)),
                pm(4, AepEffects.ParamKind.POINT, "offset", Conv.Point),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "complexity", Conv.Plain(10.0)),
                pm(6, AepEffects.ParamKind.ANGLE, "evolution", Conv.Angle),
                pm(8, AepEffects.ParamKind.CHECKBOX, "evolutionOptions/cycleEvolution", Conv.Checkbox),
                pm(9, AepEffects.ParamKind.INT_SLIDER, "evolutionOptions/cycle", Conv.Plain(88.0)),
                pm(10, AepEffects.ParamKind.INT_SLIDER, "evolutionOptions/randomSeed", Conv.Plain(100000.0)),
                pm(12, AepEffects.ParamKind.POPUP, "pinning", Conv.Popup(3, TURB_PINNING)),
                pm(13, AepEffects.ParamKind.CHECKBOX, "resizeLayer", Conv.Checkbox)
            )
        ),
        // Fractal Noise.
        fx(
            "ADBE Fractal Noise", "ec.noise.fractal", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "fractalType", Conv.Popup(1, FRACTAL_TYPE)),
                pm(2, AepEffects.ParamKind.POPUP, "noiseType", Conv.Popup(3, NOISE_TYPE)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "invert", Conv.Checkbox),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "contrast", Conv.Plain(10000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "brightness", Conv.Plain(10000.0)),
                pm(6, AepEffects.ParamKind.POPUP, "overflow", Conv.Popup(4, OVERFLOW)),
                pm(8, AepEffects.ParamKind.ANGLE, "transform/rotation", Conv.Angle),
                pm(9, AepEffects.ParamKind.CHECKBOX, "transform/uniformScaling", Conv.Checkbox),
                pm(10, AepEffects.ParamKind.FIXED_SLIDER, "transform/scale", Conv.Plain(10000.0)),
                pm(11, AepEffects.ParamKind.FIXED_SLIDER, "transform/scaleWidth", Conv.Plain(10000.0)),
                pm(12, AepEffects.ParamKind.FIXED_SLIDER, "transform/scaleHeight", Conv.Plain(10000.0)),
                pm(13, AepEffects.ParamKind.POINT, "transform/offset", Conv.Point),
                pm(14, AepEffects.ParamKind.CHECKBOX, "transform/perspectiveOffset", Conv.Checkbox),
                pm(16, AepEffects.ParamKind.FIXED_SLIDER, "complexity", Conv.Plain(20.0)),
                pm(18, AepEffects.ParamKind.FIXED_SLIDER, "subSettings/subInfluence", Conv.Plain(10000.0)),
                pm(19, AepEffects.ParamKind.FIXED_SLIDER, "subSettings/subScaling", Conv.Plain(10000.0)),
                pm(20, AepEffects.ParamKind.ANGLE, "subSettings/subRotation", Conv.Angle),
                pm(21, AepEffects.ParamKind.POINT, "subSettings/subOffset", Conv.Point),
                pm(22, AepEffects.ParamKind.CHECKBOX, "subSettings/centerSubscale", Conv.Checkbox),
                pm(24, AepEffects.ParamKind.ANGLE, "evolution", Conv.Angle),
                pm(26, AepEffects.ParamKind.CHECKBOX, "evolutionOptions/cycleEvolution", Conv.Checkbox),
                pm(27, AepEffects.ParamKind.INT_SLIDER, "evolutionOptions/cycle", Conv.Plain(88.0)),
                pm(28, AepEffects.ParamKind.INT_SLIDER, "evolutionOptions/seed", Conv.Plain(100000.0)),
                pm(30, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Plain(100.0)),
                pm(31, AepEffects.ParamKind.POPUP, "blendingMode", Conv.Popup(2, FRACTAL_BLEND))
            )
        ),
        // Beam: Length, Time and Softness are 0..1 fractions, percentages here.
        fx(
            "ADBE Laser", "ec.generate.beam", listOf(
                pm(1, AepEffects.ParamKind.POINT, "startPoint", Conv.Point),
                pm(2, AepEffects.ParamKind.POINT, "endPoint", Conv.Point),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "length", Conv.Percent(1.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "time", Conv.Percent(1.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "startThickness", Conv.Plain(4000.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "endThickness", Conv.Plain(4000.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "softness", Conv.Percent(1.0)),
                pm(8, AepEffects.ParamKind.COLOR, "insideColor", Conv.Color),
                pm(9, AepEffects.ParamKind.COLOR, "outsideColor", Conv.Color),
                pm(10, AepEffects.ParamKind.CHECKBOX, "perspective3d", Conv.Checkbox),
                pm(11, AepEffects.ParamKind.CHECKBOX, "compositeOnOriginal", Conv.Checkbox)
            )
        ),
        // CC Radial Fast Blur.
        fx(
            "CC Radial Fast Blur", "ec.blur.ccradialfast", listOf(
                pm(1, AepEffects.ParamKind.POINT, "center", Conv.Point),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(100.0)),
                pm(3, AepEffects.ParamKind.POPUP, "zoom", Conv.Popup(1, FAST_ZOOM))
            )
        ),
        // Noise.
        fx(
            "ADBE Noise2", "ec.noise.noise", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.CHECKBOX, "color", Conv.Checkbox),
                pm(3, AepEffects.ParamKind.CHECKBOX, "clip", Conv.Checkbox)
            )
        ),
        // Levels (Individual Controls): 1 Channel only picks the histogram shown, 2 the
        // histogram itself (no value).
        fx(
            "ADBE Pro Levels2", "ec.color.levelsic", listOf(
                pm(2, AepEffects.ParamKind.ARBITRARY_DATA, "", Conv.Skip),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "rgb/rgbInBlack", Conv.Plain(10000.0)),
                pm(5, AepEffects.ParamKind.FLOAT_SLIDER, "rgb/rgbInWhite", Conv.Plain(10000.0)),
                pm(6, AepEffects.ParamKind.FLOAT_SLIDER, "rgb/rgbGamma", Conv.Plain(5.0)),
                pm(7, AepEffects.ParamKind.FLOAT_SLIDER, "rgb/rgbOutBlack", Conv.Plain(10000.0)),
                pm(8, AepEffects.ParamKind.FLOAT_SLIDER, "rgb/rgbOutWhite", Conv.Plain(10000.0)),
                pm(11, AepEffects.ParamKind.FLOAT_SLIDER, "red/redInBlack", Conv.Plain(10000.0)),
                pm(12, AepEffects.ParamKind.FLOAT_SLIDER, "red/redInWhite", Conv.Plain(10000.0)),
                pm(13, AepEffects.ParamKind.FLOAT_SLIDER, "red/redGamma", Conv.Plain(5.0)),
                pm(14, AepEffects.ParamKind.FLOAT_SLIDER, "red/redOutBlack", Conv.Plain(10000.0)),
                pm(15, AepEffects.ParamKind.FLOAT_SLIDER, "red/redOutWhite", Conv.Plain(10000.0)),
                pm(18, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenInBlack", Conv.Plain(10000.0)),
                pm(19, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenInWhite", Conv.Plain(10000.0)),
                pm(20, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenGamma", Conv.Plain(5.0)),
                pm(21, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenOutBlack", Conv.Plain(10000.0)),
                pm(22, AepEffects.ParamKind.FLOAT_SLIDER, "green/greenOutWhite", Conv.Plain(10000.0)),
                pm(25, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueInBlack", Conv.Plain(10000.0)),
                pm(26, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueInWhite", Conv.Plain(10000.0)),
                pm(27, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueGamma", Conv.Plain(5.0)),
                pm(28, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueOutBlack", Conv.Plain(10000.0)),
                pm(29, AepEffects.ParamKind.FLOAT_SLIDER, "blue/blueOutWhite", Conv.Plain(10000.0)),
                pm(32, AepEffects.ParamKind.FLOAT_SLIDER, "alpha/alphaInBlack", Conv.Plain(10000.0)),
                pm(33, AepEffects.ParamKind.FLOAT_SLIDER, "alpha/alphaInWhite", Conv.Plain(10000.0)),
                pm(34, AepEffects.ParamKind.FLOAT_SLIDER, "alpha/alphaGamma", Conv.Plain(5.0)),
                pm(35, AepEffects.ParamKind.FLOAT_SLIDER, "alpha/alphaOutBlack", Conv.Plain(10000.0)),
                pm(36, AepEffects.ParamKind.FLOAT_SLIDER, "alpha/alphaOutWhite", Conv.Plain(10000.0)),
                pm(38, AepEffects.ParamKind.POPUP, "clipToOutputBlack", Conv.Popup(3, LEVELS_CLIP)),
                pm(39, AepEffects.ParamKind.POPUP, "clipToOutputWhite", Conv.Popup(3, LEVELS_CLIP))
            )
        ),
        // Corner Pin.
        fx(
            "ADBE Corner Pin", "ec.distort.cornerpin", listOf(
                pm(1, AepEffects.ParamKind.POINT, "ul", Conv.Point),
                pm(2, AepEffects.ParamKind.POINT, "ur", Conv.Point),
                pm(3, AepEffects.ParamKind.POINT, "ll", Conv.Point),
                pm(4, AepEffects.ParamKind.POINT, "lr", Conv.Point)
            )
        ),
        // Grid.
        fx(
            "ADBE Grid", "ec.generate.grid", listOf(
                pm(1, AepEffects.ParamKind.POINT, "anchor", Conv.Point),
                pm(2, AepEffects.ParamKind.POPUP, "sizeFrom", Conv.Popup(1, GRID_SIZE_FROM)),
                pm(3, AepEffects.ParamKind.POINT, "corner", Conv.Point),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "width", Conv.Plain(4000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "height", Conv.Plain(4000.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "border", Conv.Plain(2000.0)),
                pm(8, AepEffects.ParamKind.FIXED_SLIDER, "feather/featherWidth", Conv.Plain(400.0)),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "feather/featherHeight", Conv.Plain(400.0)),
                pm(11, AepEffects.ParamKind.CHECKBOX, "invertGrid", Conv.Checkbox),
                pm(12, AepEffects.ParamKind.COLOR, "color", Conv.Color),
                pm(13, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Plain(100.0)),
                pm(14, AepEffects.ParamKind.POPUP, "blendingMode", Conv.Popup(1, GRID_BLEND))
            )
        ),
        // Sharpen.
        fx(
            "ADBE Sharpen", "ec.blur.sharpen", listOf(
                pm(1, AepEffects.ParamKind.INT_SLIDER, "amount", Conv.Plain(4000.0))
            )
        ),
        // Linear Color Key; 1 Preview has no data.
        fx(
            "ADBE Linear Color Key2", "ec.key.linearcolor", listOf(
                pm(2, AepEffects.ParamKind.POPUP, "view", Conv.Popup(1, KEY_VIEW)),
                pm(3, AepEffects.ParamKind.COLOR, "keyColor", Conv.Color),
                pm(4, AepEffects.ParamKind.POPUP, "matchColors", Conv.Popup(1, MATCH_COLORS)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "tolerance", Conv.Plain(100.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "softness", Conv.Plain(100.0)),
                pm(7, AepEffects.ParamKind.POPUP, "keyOperation", Conv.Popup(1, KEY_OPERATION))
            )
        ),
        // Radial Wipe.
        fx(
            "ADBE Radial Wipe", "ec.transition.radialwipe", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "completion", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.ANGLE, "startAngle", Conv.Angle),
                pm(3, AepEffects.ParamKind.POINT, "center", Conv.Point),
                pm(4, AepEffects.ParamKind.POPUP, "wipe", Conv.Popup(1, WIPE)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "feather", Conv.Plain(32000.0))
            )
        ),
        // Remove Color Matting.
        fx(
            "ADBE Remove Color Matting", "ec.channel.removecolormatting", listOf(
                pm(1, AepEffects.ParamKind.COLOR, "backgroundColor", Conv.Color),
                pm(2, AepEffects.ParamKind.CHECKBOX, "clipHdr", Conv.Checkbox)
            )
        ),
        // CC Bend It: Bend is a slider (valid max 250) in After Effects; 5 Distort has no counterpart.
        fx(
            "CC Bend It", "ec.distort.ccbendit", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "bend", Conv.Plain(250.0)),
                pm(2, AepEffects.ParamKind.POINT, "start", Conv.Point),
                pm(3, AepEffects.ParamKind.POINT, "end", Conv.Point),
                pm(4, AepEffects.ParamKind.POPUP, "renderPrestart", Conv.Popup(3, BEND_PRESTART))
            )
        ),
        // CC Power Pin: Perspective is a 0..1 fraction.
        fx(
            "CC Power Pin", "ec.distort.ccpowerpin", listOf(
                pm(1, AepEffects.ParamKind.POINT, "topLeft", Conv.Point),
                pm(2, AepEffects.ParamKind.POINT, "topRight", Conv.Point),
                pm(3, AepEffects.ParamKind.POINT, "bottomLeft", Conv.Point),
                pm(4, AepEffects.ParamKind.POINT, "bottomRight", Conv.Point),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "perspective", Conv.Percent(1.0)),
                pm(6, AepEffects.ParamKind.CHECKBOX, "unstretch", Conv.Checkbox),
                pm(8, AepEffects.ParamKind.FIXED_SLIDER, "expandTop", Conv.Plain(200.0)),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "expandLeft", Conv.Plain(200.0)),
                pm(10, AepEffects.ParamKind.FIXED_SLIDER, "expandRight", Conv.Plain(200.0)),
                pm(11, AepEffects.ParamKind.FIXED_SLIDER, "expandBottom", Conv.Plain(200.0))
            )
        ),
        // 4-Color Gradient.
        fx(
            "ADBE 4ColorGradient", "ec.generate.fourcolor", listOf(
                pm(2, AepEffects.ParamKind.POINT, "positionsColors/point1", Conv.Point),
                pm(3, AepEffects.ParamKind.COLOR, "positionsColors/color1", Conv.Color),
                pm(4, AepEffects.ParamKind.POINT, "positionsColors/point2", Conv.Point),
                pm(5, AepEffects.ParamKind.COLOR, "positionsColors/color2", Conv.Color),
                pm(6, AepEffects.ParamKind.POINT, "positionsColors/point3", Conv.Point),
                pm(7, AepEffects.ParamKind.COLOR, "positionsColors/color3", Conv.Color),
                pm(8, AepEffects.ParamKind.POINT, "positionsColors/point4", Conv.Point),
                pm(9, AepEffects.ParamKind.COLOR, "positionsColors/color4", Conv.Color),
                pm(11, AepEffects.ParamKind.FIXED_SLIDER, "blend", Conv.Plain(10000.0)),
                pm(12, AepEffects.ParamKind.FIXED_SLIDER, "jitter", Conv.Plain(500.0)),
                pm(13, AepEffects.ParamKind.FIXED_SLIDER, "opacity", Conv.Plain(100.0)),
                pm(14, AepEffects.ParamKind.POPUP, "blendingMode", Conv.Popup(1, FOUR_COLOR_BLEND))
            )
        ),
        // Change Color.
        fx(
            "ADBE Change Color", "ec.color.changecolor", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "view", Conv.Popup(1, CHANGE_VIEW)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "hueTransform", Conv.Plain(1800.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "lightnessTransform", Conv.Plain(100.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "saturationTransform", Conv.Plain(100.0)),
                pm(5, AepEffects.ParamKind.COLOR, "colorToChange", Conv.Color),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "matchingTolerance", Conv.Plain(100.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "matchingSoftness", Conv.Plain(100.0)),
                pm(8, AepEffects.ParamKind.POPUP, "matchColors", Conv.Popup(1, MATCH_COLORS)),
                pm(9, AepEffects.ParamKind.CHECKBOX, "invertColorCorrectionMask", Conv.Checkbox)
            )
        ),
        // Extract; 1 Histogram has no data.
        fx(
            "ADBE Extract", "ec.key.extract", listOf(
                pm(2, AepEffects.ParamKind.POPUP, "channel", Conv.Popup(1, EXTRACT_CHANNEL)),
                pm(3, AepEffects.ParamKind.INT_SLIDER, "blackPoint", Conv.Plain(255.0)),
                pm(4, AepEffects.ParamKind.INT_SLIDER, "whitePoint", Conv.Plain(255.0)),
                pm(5, AepEffects.ParamKind.INT_SLIDER, "blackSoftness", Conv.Plain(255.0)),
                pm(6, AepEffects.ParamKind.INT_SLIDER, "whiteSoftness", Conv.Plain(255.0)),
                pm(7, AepEffects.ParamKind.CHECKBOX, "invert", Conv.Checkbox)
            )
        ),
        // Noise HLS.
        fx(
            "ADBE Noise HLS2", "ec.noise.noisehls", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "noise", Conv.Popup(1, NOISE_HLS)),
                pm(2, AepEffects.ParamKind.FLOAT_SLIDER, "hue", Conv.Plain(1000.0)),
                pm(3, AepEffects.ParamKind.FLOAT_SLIDER, "lightness", Conv.Plain(1000.0)),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "saturation", Conv.Plain(1000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "grainSize", Conv.Plain(10.0)),
                pm(6, AepEffects.ParamKind.ANGLE, "noisePhase", Conv.Angle)
            )
        ),
        // Scatter.
        fx(
            "ADBE Scatter", "ec.stylize.scatter", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(1000.0)),
                pm(2, AepEffects.ParamKind.POPUP, "grain", Conv.Popup(1, SCATTER_GRAIN)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "randomizeEveryFrame", Conv.Checkbox)
            )
        ),
        // CC Light Sweep.
        fx(
            "CC Light Sweep", "ec.generate.cclightsweep", listOf(
                pm(1, AepEffects.ParamKind.POINT, "center", Conv.Point),
                pm(2, AepEffects.ParamKind.ANGLE, "direction", Conv.Angle),
                pm(3, AepEffects.ParamKind.POPUP, "shape", Conv.Popup(3, SWEEP_SHAPE)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "width", Conv.Plain(4000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "sweepIntensity", Conv.Plain(500.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "edgeIntensity", Conv.Plain(500.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "edgeThickness", Conv.Plain(20.0)),
                pm(8, AepEffects.ParamKind.COLOR, "lightColor", Conv.Color),
                pm(9, AepEffects.ParamKind.POPUP, "lightReceptionMode", Conv.Popup(1, SWEEP_RECEPTION))
            )
        ),
        // Compound Blur: an unset Blur Layer is the layer itself in both programs.
        fx(
            "ADBE Compound Blur", "ec.blur.compound", listOf(
                pm(1, AepEffects.ParamKind.LAYER, "blurLayer", Conv.LayerRef(false)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "maximumBlur", Conv.Plain(4000.0)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "stretchMapToFit", Conv.Checkbox),
                pm(4, AepEffects.ParamKind.CHECKBOX, "invertBlur", Conv.Checkbox)
            )
        ),
        // Displacement Map.
        fx(
            "ADBE Displacement Map", "ec.distort.displacementmap", listOf(
                pm(1, AepEffects.ParamKind.LAYER, "displacementMapLayer", Conv.LayerRef(false)),
                pm(2, AepEffects.ParamKind.POPUP, "useForHorizontal", Conv.Popup(1, DISPLACE_USE)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "maxHorizontal", Conv.Plain(32000.0)),
                pm(4, AepEffects.ParamKind.POPUP, "useForVertical", Conv.Popup(2, DISPLACE_USE)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "maxVertical", Conv.Plain(32000.0)),
                pm(6, AepEffects.ParamKind.POPUP, "displacementMapBehavior", Conv.Popup(1, DISPLACE_BEHAVIOR)),
                pm(7, AepEffects.ParamKind.CHECKBOX, "wrapPixelsAround", Conv.Checkbox),
                pm(8, AepEffects.ParamKind.CHECKBOX, "expandOutput", Conv.Checkbox)
            )
        ),
        // Set Matte: an unset Take Matte From Layer is the layer itself.
        fx(
            "ADBE Set Matte3", "ec.channel.setmatte", listOf(
                pm(1, AepEffects.ParamKind.LAYER, "takeMatteFromLayer", Conv.LayerRef(false)),
                pm(2, AepEffects.ParamKind.POPUP, "takeMatteFrom", Conv.Popup(4, MATTE_USE)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "invertMatte", Conv.Checkbox),
                pm(4, AepEffects.ParamKind.CHECKBOX, "stretchMatteToFit", Conv.Checkbox),
                pm(5, AepEffects.ParamKind.CHECKBOX, "compositeMatteWithOriginal", Conv.Checkbox),
                pm(6, AepEffects.ParamKind.CHECKBOX, "premultiplyMatteLayer", Conv.Checkbox)
            )
        ),
        // Gaussian Blur (Legacy).
        fx(
            "ADBE Gaussian Blur", "ec.obsolete.gaussianlegacy", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "blurriness", Conv.Plain(30000.0)),
                pm(2, AepEffects.ParamKind.POPUP, "blurDimensions", Conv.Popup(1, BLUR_DIMENSIONS))
            )
        ),
        // Pixel Motion Blur.
        fx(
            "ADBE OFMotionBlur", "ec.time.pixelmotionblur", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "shutterControl", Conv.Popup(1, SHUTTER_CONTROL)),
                pm(2, AepEffects.ParamKind.FLOAT_SLIDER, "shutterAngle", Conv.Plain(5000.0)),
                pm(3, AepEffects.ParamKind.INT_SLIDER, "shutterSamples", Conv.Plain(256.0)),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "vectorDetail", Conv.Plain(100.0))
            )
        ),
        // HDR Highlight Compression: Amount a float slider in percent (valid 0..100, default 100).
        fx(
            "ADBE HDR ToneMap", "ec.utility.hdrcompression", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "amount", Conv.Plain(100.0))
            )
        ),
        // Echo: Echo Time in seconds (valid -30 000..30 000), Number Of Echoes an integer
        // (valid 0..30 000), Starting Intensity 0..1, Decay (valid 0..100, slider 0..1), Echo
        // Operator. EffectCraft's Echo uses at most 255 echoes and a Decay up to 1.
        fx(
            "ADBE Echo", "ec.time.echo", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "echoTime", Conv.Plain(30000.0)),
                pmLimited(2, AepEffects.ParamKind.INT_SLIDER, "numberOfEchoes", Conv.Plain(30000.0), 0.0, 255.0),
                pm(3, AepEffects.ParamKind.FLOAT_SLIDER, "startingIntensity", Conv.Plain(1.0)),
                pmLimited(4, AepEffects.ParamKind.FLOAT_SLIDER, "decay", Conv.Plain(100.0), 0.0, 1.0),
                pm(5, AepEffects.ParamKind.POPUP, "echoOperator", Conv.Popup(1, ECHO_OPERATOR))
            )
        ),
        // Linear Wipe: Transition Completion and Feather fixed sliders (valid 100 and
        // 32 000), Wipe Angle in degrees.
        fx(
            "ADBE Linear Wipe", "ec.transition.linearwipe", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "completion", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.ANGLE, "angle", Conv.Angle),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "feather", Conv.Plain(32000.0))
            )
        ),
        // Offset: Blend With Original is a 0..1 fraction, a percentage here.
        fx(
            "ADBE Offset", "ec.distort.offset", listOf(
                pm(1, AepEffects.ParamKind.POINT, "shift", Conv.Point),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "blend", Conv.Percent(1.0))
            )
        ),
        // Color Stabilizer: Stabilize (fresh default Levels), the three sample points,
        // Sample Size a fixed slider (valid 50, default 3). The reference frame (Set Frame)
        // is not a parameter in the file and is not imported.
        fx(
            "ADBE Deflicker", "ec.color.colorstabilizer", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "stabilize", Conv.Popup(2, STABILIZE)),
                pm(2, AepEffects.ParamKind.POINT, "blackPoint", Conv.Point),
                pm(3, AepEffects.ParamKind.POINT, "midPoint", Conv.Point),
                pm(4, AepEffects.ParamKind.POINT, "whitePoint", Conv.Point),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "sampleSize", Conv.Plain(50.0))
            )
        ),
        // Layer Control: the default is the owning layer, None is unset.
        fx(
            "ADBE Layer Control", "ec.control.layer", listOf(
                pm(1, AepEffects.ParamKind.LAYER, "layer", Conv.LayerRef(true))
            )
        ),
        fx(
            "ADBE Slider Control", "ec.control.slider", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "slider", Conv.Plain(1_000_000.0))
            )
        ),
        fx(
            "ADBE Angle Control", "ec.control.angle", listOf(
                pm(1, AepEffects.ParamKind.ANGLE, "angle", Conv.Angle)
            )
        ),
        fx(
            "ADBE Checkbox Control", "ec.control.checkbox", listOf(
                pm(1, AepEffects.ParamKind.CHECKBOX, "checkbox", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Color Control", "ec.control.color", listOf(
                pm(1, AepEffects.ParamKind.COLOR, "color", Conv.Color)
            )
        ),
        fx(
            "ADBE Point Control", "ec.control.point", listOf(
                pm(1, AepEffects.ParamKind.POINT, "point", Conv.Point)
            )
        ),
        // The nine effects the real file (Digital_Slides_CC_2021_61_5ie6.aep) uses that the
        // table did not map: 328 instances. Each row is verified against the pinned
        // `effectcraft-effects` spec (param ids, ranges, defaults) and the file's own
        // `parT` layouts; the official importer maps none of these.
        fx(
            "ADBE Tile", "ec.stylize.motiontile", listOf(
                pm(1, AepEffects.ParamKind.POINT, "tileCenter", Conv.Point),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "tileWidth", Conv.Plain(100.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "tileHeight", Conv.Plain(100.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "outputWidth", Conv.Plain(30000.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "outputHeight", Conv.Plain(30000.0)),
                pm(6, AepEffects.ParamKind.CHECKBOX, "mirrorEdges", Conv.Checkbox),
                pm(7, AepEffects.ParamKind.ANGLE, "phase", Conv.Angle),
                pm(8, AepEffects.ParamKind.CHECKBOX, "horizontalPhaseShift", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Fast Blur", "ec.blur.fastbox", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "radius", Conv.Plain(30000.0)),
                pm(2, AepEffects.ParamKind.POPUP, "dimensions", Conv.Popup(1, BLUR_DIMENSIONS)),
                pm(3, AepEffects.ParamKind.CHECKBOX, "repeatEdge", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Posterize Time", "ec.time.posterizetime", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "frameRate", Conv.Plain(999.0))
            )
        ),
        fx(
            "ADBE Point3D Control", "ec.control.point3d", listOf(
                pm(1, AepEffects.ParamKind.OTHER, "point", Conv.Vec3)
            )
        ),
        fx(
            "ADBE Noise", "ec.noise.noise", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "amount", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.CHECKBOX, "color", Conv.Checkbox),
                pm(3, AepEffects.ParamKind.CHECKBOX, "clip", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Optics Compensation", "ec.distort.opticscompensation", listOf(
                pmLimited(1, AepEffects.ParamKind.FIXED_SLIDER, "fieldOfView", Conv.Plain(180.0), 0.0, 179.0),
                pm(2, AepEffects.ParamKind.CHECKBOX, "reverseLensDistortion", Conv.Checkbox),
                pm(3, AepEffects.ParamKind.POPUP, "fovOrientation", Conv.Popup(1, OPTICS_ORIENTATION)),
                pm(4, AepEffects.ParamKind.POINT, "viewCenter", Conv.Point),
                pm(5, AepEffects.ParamKind.CHECKBOX, "optimalPixels", Conv.Checkbox),
                pm(6, AepEffects.ParamKind.POPUP, "resize", Conv.Popup(1, OPTICS_RESIZE))
            )
        ),
        fx(
            "ADBE Color Balance 2", "ec.color.colorbalance", listOf(
                pm(1, AepEffects.ParamKind.FIXED_SLIDER, "shadowRed", Conv.Plain(100.0)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "shadowGreen", Conv.Plain(100.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "shadowBlue", Conv.Plain(100.0)),
                pm(4, AepEffects.ParamKind.FIXED_SLIDER, "midRed", Conv.Plain(100.0)),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "midGreen", Conv.Plain(100.0)),
                pm(6, AepEffects.ParamKind.FIXED_SLIDER, "midBlue", Conv.Plain(100.0)),
                pm(7, AepEffects.ParamKind.FIXED_SLIDER, "hiRed", Conv.Plain(100.0)),
                pm(8, AepEffects.ParamKind.FIXED_SLIDER, "hiGreen", Conv.Plain(100.0)),
                pm(9, AepEffects.ParamKind.FIXED_SLIDER, "hiBlue", Conv.Plain(100.0)),
                pm(10, AepEffects.ParamKind.CHECKBOX, "preserveLuminosity", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Camera Lens Blur", "ec.blur.cameralens", listOf(
                pm(1, AepEffects.ParamKind.FLOAT_SLIDER, "blurRadius", Conv.Plain(500.0)),
                pm(3, AepEffects.ParamKind.POPUP, "irisProperties/irisShape", Conv.Popup(4, IRIS_SHAPE)),
                pm(4, AepEffects.ParamKind.FLOAT_SLIDER, "irisProperties/irisRoundness", Conv.Plain(100.0)),
                pm(5, AepEffects.ParamKind.FLOAT_SLIDER, "irisProperties/irisAspectRatio", Conv.Plain(100.0)),
                pm(6, AepEffects.ParamKind.ANGLE, "irisProperties/irisRotation", Conv.Angle),
                pm(7, AepEffects.ParamKind.FLOAT_SLIDER, "irisProperties/diffractionFringe", Conv.Plain(500.0)),
                pm(10, AepEffects.ParamKind.LAYER, "blurMap/blurMapLayer", Conv.LayerRef(true)),
                pm(11, AepEffects.ParamKind.POPUP, "blurMap/channel", Conv.Popup(1, LENS_CHANNEL)),
                pm(12, AepEffects.ParamKind.POPUP, "blurMap/placement", Conv.Popup(1, LENS_PLACEMENT)),
                pm(13, AepEffects.ParamKind.FLOAT_SLIDER, "blurMap/blurFocalDistance", Conv.Factor(1.0, 255.0)),
                pm(14, AepEffects.ParamKind.CHECKBOX, "blurMap/invertBlurMap", Conv.Checkbox),
                pm(17, AepEffects.ParamKind.FLOAT_SLIDER, "highlight/specularBrightness", Conv.Plain(100.0)),
                pm(18, AepEffects.ParamKind.FLOAT_SLIDER, "highlight/specularThreshold", Conv.Factor(1.0, 255.0)),
                pm(19, AepEffects.ParamKind.FLOAT_SLIDER, "highlight/highlightSaturation", Conv.Plain(100.0)),
                pm(21, AepEffects.ParamKind.CHECKBOX, "repeatEdge", Conv.Checkbox),
                pm(22, AepEffects.ParamKind.CHECKBOX, "useLinear", Conv.Checkbox)
            )
        ),
        fx(
            "ADBE Wave Warp", "ec.distort.wavewarp", listOf(
                pm(1, AepEffects.ParamKind.POPUP, "waveType", Conv.Popup(1, WAVE_TYPES)),
                pm(2, AepEffects.ParamKind.FIXED_SLIDER, "height", Conv.Plain(32000.0)),
                pm(3, AepEffects.ParamKind.FIXED_SLIDER, "width", Conv.Plain(32000.0)),
                pm(4, AepEffects.ParamKind.ANGLE, "direction", Conv.Angle),
                pm(5, AepEffects.ParamKind.FIXED_SLIDER, "speed", Conv.Plain(100.0)),
                pm(6, AepEffects.ParamKind.POPUP, "pinning", Conv.Popup(1, WAVE_PINNING)),
                pm(7, AepEffects.ParamKind.ANGLE, "phase", Conv.Angle)
            )
        )
    )

    /** The table row of an After Effects match name. */
    fun byMatchName(matchName: String): EffectMap? = EFFECTS.firstOrNull { it.matchName == matchName }

    /** The table row of an EffectCraft effect id. */
    fun byEffect(effect: String): EffectMap? = EFFECTS.firstOrNull { it.effect == effect }

    /** The key a popup item is matched by between the two programs: trimmed, lower case, and
     * Set Matte's "Red Channel" ... "Alpha Channel" are EffectCraft's "Red" ... "Alpha"; a
     * trailing " Map" or " Map to Fit" is dropped too, so Camera Lens Blur's "Center Map" is
     * EffectCraft's "Center" while Displacement Map's identical strings on both sides still
     * match (both sides are normalised the same way). */
    fun popupKey(item: String): String {
        var s = item.trim().lowercase()
        if (s.endsWith(" channel")) s = s.removeSuffix(" channel")
        if (s.endsWith(" map to fit")) s = s.removeSuffix(" map to fit")
        if (s.endsWith(" map")) s = s.removeSuffix(" map")
        return s
    }

    /** The EffectCraft parameters Hue/Saturation's master values go to, in the order of the
     * data (hue, saturation, lightness). */
    val HUE_SATURATION_MASTER = listOf("hue", "saturation", "lightness")

    /** The EffectCraft parameters Levels' RGB channel values go to, in the order of the
     * data (input black, input white, gamma, output black, output white). */
    val LEVELS_RGB = listOf("inBlack", "inWhite", "gamma", "outBlack", "outWhite")
}
