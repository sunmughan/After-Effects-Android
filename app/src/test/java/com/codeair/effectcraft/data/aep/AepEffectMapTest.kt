package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effect table (AepEffectMap): shape and the rows the importer relies on most. */
class AepEffectMapTest {

    @Test
    fun tableHasSixtyThreeEffects() {
        assertEquals(63, AepEffectMap.EFFECTS.size)
        assertEquals(AepEffectMap.EFFECTS.size, AepEffectMap.EFFECTS.map { it.matchName }.toSet().size)
        assertEquals(AepEffectMap.EFFECTS.size, AepEffectMap.EFFECTS.map { it.effect }.toSet().size)
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
