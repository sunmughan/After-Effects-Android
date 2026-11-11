package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effect parameter values and keyframes (AepEffectKeys): the Vec3 kind. */
class AepEffectKeysTest {

    @Test
    fun vec3ValueBuildsCanonicalVec3() {
        val json = AepEffectKeys.Kind.Vec3.value(listOf(0.359, 0.7, 0.0))!!
        assertEquals("Vec3", json.getString("t"))
        val v = json.getJSONArray("v")
        assertEquals(0.359, v.getDouble(0), 1e-12)
        assertEquals(0.7, v.getDouble(1), 1e-12)
        assertEquals(0.0, v.getDouble(2), 0.0)
    }

    @Test
    fun vec3ValueRejectsBadComponents() {
        assertNull(AepEffectKeys.Kind.Vec3.value(listOf(0.359, 0.7)))
        assertNull(AepEffectKeys.Kind.Vec3.value(listOf(0.359, 0.7, Double.NaN)))
        assertNull(AepEffectKeys.Kind.Vec3.value(listOf(0.359, 0.7, 1e10)))
    }

    @Test
    fun vec3ConvertsOnlyFromVec3() {
        val converted = AepEffectKeys.convertValue(
            AepEffects.ParamValue.Vec3(0.359, 0.7, 0.0), AepEffectKeys.Kind.Vec3
        )!!
        assertEquals("Vec3", converted.getString("t"))
        assertEquals(0.359, converted.getJSONArray("v").getDouble(0), 1e-12)
        assertNull(
            AepEffectKeys.convertValue(
                AepEffects.ParamValue.Number(1.0), AepEffectKeys.Kind.Vec3
            )
        )
        assertNull(
            AepEffectKeys.convertValue(
                AepEffects.ParamValue.Vec3(0.0, 0.0, 0.0), AepEffectKeys.Kind.Number(1.0)
            )
        )
    }

    @Test
    fun vec3TangentAndEase() {
        assertEquals(listOf(1.0, 2.0, 3.0), AepEffectKeys.Kind.Vec3.tangent(listOf(1.0, 2.0, 3.0)))
        assertEquals(1.0, AepEffectKeys.Kind.Vec3.factor(), 0.0)
        assertTrue(AepEffectKeys.Kind.Vec3.easeDecoded())
        assertEquals("Vec3", AepEffectKeys.vec3(1.0, 2.0, 3.0)!!.getString("t"))
    }
}
