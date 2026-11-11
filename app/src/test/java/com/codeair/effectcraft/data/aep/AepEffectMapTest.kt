package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effect table (AepEffectMap): shape and the rows the importer relies on most. */
class AepEffectMapTest {

    @Test
    fun tableHasSeventyTwoEffects() {
        assertEquals(72, AepEffectMap.EFFECTS.size)
        assertEquals(AepEffectMap.EFFECTS.size, AepEffectMap.EFFECTS.map { it.matchName }.toSet().size)
        // Match names stay unique: that is the lookup key. Effect ids may repeat where
        // two After Effects effects are one EffectCraft effect with different param
        // layouts (Fast Blur has no Iterations; Box Blur does) or identical ones
        // (Noise and Noise 2): exactly these two are shared, nothing else.
        val shared = AepEffectMap.EFFECTS.groupBy { it.effect }.filter { it.value.size > 1 }
        assertEquals(setOf("ec.blur.fastbox", "ec.noise.noise"), shared.keys)
        assertEquals(
            listOf("ADBE Box Blur2", "ADBE Fast Blur"),
            shared.getValue("ec.blur.fastbox").map { it.matchName }.sorted()
        )
        assertEquals(
            listOf("ADBE Noise", "ADBE Noise2"),
            shared.getValue("ec.noise.noise").map { it.matchName }.sorted()
        )
    }

    /** The nine effects the real file uses that the table did not map: every row
     * against the pinned `effectcraft-effects` spec (param ids, kinds, targets). */
    @Test
    fun realFileEffects() {
        val tile = AepEffectMap.byMatchName("ADBE Tile")!!
        assertEquals("ec.stylize.motiontile", tile.effect)
        assertEquals(8, tile.params.size)
        assertEquals("tileCenter", tile.params.first { it.index == 1 }.target)
        assertEquals(AepEffects.ParamKind.POINT, tile.params.first { it.index == 1 }.kind)
        assertEquals(AepEffectMap.Conv.Point, tile.params.first { it.index == 1 }.conv)
        assertEquals("outputHeight", tile.params.first { it.index == 5 }.target)
        assertEquals(30000.0, (tile.params.first { it.index == 5 }.conv as AepEffectMap.Conv.Plain).validMax, 0.0)
        assertEquals("mirrorEdges", tile.params.first { it.index == 6 }.target)

        val fast = AepEffectMap.byMatchName("ADBE Fast Blur")!!
        assertEquals("ec.blur.fastbox", fast.effect)
        assertEquals("radius", fast.params.first { it.index == 1 }.target)
        assertEquals(30000.0, (fast.params.first { it.index == 1 }.conv as AepEffectMap.Conv.Plain).validMax, 0.0)
        assertEquals("dimensions", fast.params.first { it.index == 2 }.target)
        assertEquals("repeatEdge", fast.params.first { it.index == 3 }.target)

        val posterize = AepEffectMap.byMatchName("ADBE Posterize Time")!!
        assertEquals("ec.time.posterizetime", posterize.effect)
        assertEquals(1, posterize.params.size)
        assertEquals("frameRate", posterize.params[0].target)
        assertEquals(999.0, (posterize.params[0].conv as AepEffectMap.Conv.Plain).validMax, 0.0)

        val point3d = AepEffectMap.byMatchName("ADBE Point3D Control")!!
        assertEquals("ec.control.point3d", point3d.effect)
        assertEquals(1, point3d.params.size)
        assertEquals("point", point3d.params[0].target)
        assertEquals(AepEffects.ParamKind.OTHER, point3d.params[0].kind)
        assertEquals(AepEffectMap.Conv.Vec3, point3d.params[0].conv)

        val noise = AepEffectMap.byMatchName("ADBE Noise")!!
        assertEquals("ec.noise.noise", noise.effect)
        assertEquals(3, noise.params.size)
        assertEquals("amount", noise.params.first { it.index == 1 }.target)
        assertEquals(100.0, (noise.params.first { it.index == 1 }.conv as AepEffectMap.Conv.Plain).validMax, 0.0)

        val optics = AepEffectMap.byMatchName("ADBE Optics Compensation")!!
        assertEquals("ec.distort.opticscompensation", optics.effect)
        assertEquals(6, optics.params.size)
        assertEquals("fieldOfView", optics.params.first { it.index == 1 }.target)
        assertEquals(180.0, (optics.params.first { it.index == 1 }.conv as AepEffectMap.Conv.Plain).validMax, 0.0)
        assertEquals(0.0 to 179.0, optics.params.first { it.index == 1 }.limit)
        assertEquals("viewCenter", optics.params.first { it.index == 4 }.target)
        assertEquals(AepEffectMap.Conv.Point, optics.params.first { it.index == 4 }.conv)

        val balance = AepEffectMap.byMatchName("ADBE Color Balance 2")!!
        assertEquals("ec.color.colorbalance", balance.effect)
        assertEquals(10, balance.params.size)
        assertEquals("shadowRed", balance.params.first { it.index == 1 }.target)
        assertEquals("hiBlue", balance.params.first { it.index == 9 }.target)
        assertEquals("preserveLuminosity", balance.params.first { it.index == 10 }.target)

        val lens = AepEffectMap.byMatchName("ADBE Camera Lens Blur")!!
        assertEquals("ec.blur.cameralens", lens.effect)
        assertEquals("blurRadius", lens.params.first { it.index == 1 }.target)
        assertEquals("irisProperties/irisShape", lens.params.first { it.index == 3 }.target)
        assertEquals("blurMap/blurMapLayer", lens.params.first { it.index == 10 }.target)
        assertEquals(true, (lens.params.first { it.index == 10 }.conv as AepEffectMap.Conv.LayerRef).own)
        assertEquals("blurMap/channel", lens.params.first { it.index == 11 }.target)
        assertEquals("blurMap/placement", lens.params.first { it.index == 12 }.target)
        val focal = lens.params.first { it.index == 13 }.conv as AepEffectMap.Conv.Factor
        assertEquals(1.0, focal.validMax, 0.0)
        assertEquals(255.0, focal.factor, 0.0)
        val threshold = lens.params.first { it.index == 18 }.conv as AepEffectMap.Conv.Factor
        assertEquals(1.0, threshold.validMax, 0.0)
        assertEquals(255.0, threshold.factor, 0.0)

        val warp = AepEffectMap.byMatchName("ADBE Wave Warp")!!
        assertEquals("ec.distort.wavewarp", warp.effect)
        assertEquals(7, warp.params.size)
        assertEquals("waveType", warp.params.first { it.index == 1 }.target)
        assertEquals("height", warp.params.first { it.index == 2 }.target)
        assertEquals("pinning", warp.params.first { it.index == 6 }.target)
        assertEquals("phase", warp.params.first { it.index == 7 }.target)
    }

    @Test
    fun popupKeyStripsMapSuffixes() {
        assertEquals("center", AepEffectMap.popupKey("Center Map"))
        assertEquals("stretch", AepEffectMap.popupKey("Stretch Map to Fit"))
        assertEquals("tile", AepEffectMap.popupKey("Tile Map"))
        assertEquals("arbitrary", AepEffectMap.popupKey("Arbitrary Map"))
    }

    @Test
    fun controlEffects() {
        val color = AepEffectMap.byMatchName("ADBE Color Control")!!
        assertEquals("ec.control.color", color.effect)
        assertEquals(1, color.params.size)
        assertEquals(1, color.params[0].index)
        assertEquals(AepEffects.ParamKind.COLOR, color.params[0].kind)
        assertEquals("color", color.params[0].target)
        assertEquals(AepEffectMap.Conv.Color, color.params[0].conv)
        assertEquals("ec.control.slider", AepEffectMap.byMatchName("ADBE Slider Control")!!.effect)
        assertEquals("ec.control.angle", AepEffectMap.byMatchName("ADBE Angle Control")!!.effect)
        assertEquals("ec.control.checkbox", AepEffectMap.byMatchName("ADBE Checkbox Control")!!.effect)
        assertEquals("ec.control.point", AepEffectMap.byMatchName("ADBE Point Control")!!.effect)
        assertEquals("ec.control.layer", AepEffectMap.byMatchName("ADBE Layer Control")!!.effect)
        val layer = AepEffectMap.byMatchName("ADBE Layer Control")!!.params[0]
        assertEquals(true, (layer.conv as AepEffectMap.Conv.LayerRef).own)
        assertEquals(false, (AepEffectMap.byMatchName("ADBE Compound Blur")!!.params[0].conv as AepEffectMap.Conv.LayerRef).own)
    }

    @Test
    fun levelsAndGlowRows() {
        val levels = AepEffectMap.byMatchName("ADBE Easy Levels2")!!
        assertEquals("ec.color.levels", levels.effect)
        assertEquals(AepEffectMap.Extra.LEVELS, levels.extra)
        assertEquals(listOf("inBlack", "inWhite", "gamma", "outBlack", "outWhite"), AepEffectMap.LEVELS_RGB)
        val glow = AepEffectMap.byMatchName("ADBE Glo2")!!
        assertEquals(14, glow.params.size)
        val midpoint = glow.params.first { it.target == "abMidpoint" }
        assertEquals(11, midpoint.index)
        val factor = midpoint.conv as AepEffectMap.Conv.Factor
        assertEquals(AepEffectMap.GLOW_MIDPOINT_MAX, factor.validMax, 0.0)
        assertEquals(100.0, factor.factor, 0.0)
        val based = glow.params.first { it.target == "based" }
        val popup = based.conv as AepEffectMap.Conv.Popup
        assertEquals(2L, popup.default)
        assertEquals(listOf("Alpha Channel", "Color Channels"), popup.items)
    }

    @Test
    fun curvesAndCryptomatteAreExtraOnly() {
        val curves = AepEffectMap.byMatchName("ADBE CurvesCustom")!!
        assertEquals(AepEffectMap.Extra.CURVES, curves.extra)
        assertEquals(AepEffectMap.Conv.Skip, curves.params[0].conv)
        val crypto = AepEffectMap.byMatchName("Cryptomatte")!!
        assertEquals(AepEffectMap.Extra.CRYPTOMATTE, crypto.extra)
        assertTrue(crypto.params.isEmpty())
    }

    @Test
    fun echoLimits() {
        val echo = AepEffectMap.byMatchName("ADBE Echo")!!
        val echoes = echo.params.first { it.target == "numberOfEchoes" }
        assertEquals(0.0 to 255.0, echoes.limit)
        val decay = echo.params.first { it.target == "decay" }
        assertEquals(0.0 to 1.0, decay.limit)
    }

    @Test
    fun popupKeyStripsChannelSuffix() {
        assertEquals("red", AepEffectMap.popupKey("Red Channel"))
        assertEquals("alpha", AepEffectMap.popupKey("  Alpha Channel "))
        assertEquals("whole frames", AepEffectMap.popupKey("Whole Frames"))
    }

    @Test
    fun unknownMatchNameIsNull() {
        assertNull(AepEffectMap.byMatchName("ADBE Unknown Effect"))
        assertNull(AepEffectMap.byEffect("ec.unknown.effect"))
    }
}
