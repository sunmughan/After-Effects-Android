package com.codeair.effectcraft.data.aep

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [toLongExactCompat]: the exact `BigInteger` -> `Long` conversion the
 * `.aep` header code uses instead of `BigInteger.longValueExact()`, which
 * does not exist in Android's core library (CI run 42568725647 failed the
 * emulator smoke tests with NoSuchMethodError for it). Unit tests run on
 * the JDK, where `longValueExact()` exists, so they cannot catch the
 * Android gap — this test pins the replacement's behaviour instead:
 * exact in range, `ArithmeticException` out of range, and the `Rational`
 * paths that call it still reduce correctly.
 */
class AepHeaderTest {

    @Test
    fun toLongExactCompatReturnsInRangeValues() {
        assertEquals(0L, BigInteger.ZERO.toLongExactCompat())
        assertEquals(42L, BigInteger.valueOf(42L).toLongExactCompat())
        assertEquals(-42L, BigInteger.valueOf(-42L).toLongExactCompat())
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).toLongExactCompat())
        assertEquals(Long.MIN_VALUE, BigInteger.valueOf(Long.MIN_VALUE).toLongExactCompat())
    }

    @Test
    fun toLongExactCompatThrowsOutOfRange() {
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toLongExactCompat()
        }
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE).toLongExactCompat()
        }
    }

    @Test
    fun rationalReducedUsesExactConversion() {
        val reduced = AepHeader.Rational(6, 4).reduced()
        assertEquals(3L, reduced?.num)
        assertEquals(2L, reduced?.den)
        val negative = AepHeader.Rational(-6, 4).reduced()
        assertEquals(-3L, negative?.num)
        assertEquals(2L, negative?.den)
    }

    // -- track mattes (ldta byte 107 mode, byte 160 matte layer id) ----------

    /** A minimal valid `ldta` (160 bytes, the size the real file uses). */
    private fun ldta(matteMode: Int = 0, matteId: Long? = null): ByteArray {
        val bytes = ByteArray(if (matteId == null) 160 else 164)
        fun putU32(at: Int, v: Long) {
            for (i in 0 until 4) bytes[at + i] = (v ushr (24 - 8 * i)).toByte()
        }
        fun putI32(at: Int, v: Int) = putU32(at, v.toLong() and 0xFFFFFFFFL)
        putU32(0, 7) // layer id
        bytes[5] = 2 // Best quality
        putI32(8, 100); putU32(108, 100) // stretch 100/100
        putI32(12, 0); putU32(16, 25) // start 0
        putI32(20, 0); putU32(24, 25) // in 0
        putI32(28, 150); putU32(32, 25) // out 6s at 25fps
        putU32(40, 0) // no source item
        bytes[99] = 2 // blend Normal
        putU32(128, 0) // AV layer
        putU32(132, 0) // no parent
        putU32(136, 0) // not a light
        bytes[107] = matteMode.toByte()
        if (matteId != null) putU32(160, matteId)
        return bytes
    }

    @Test
    fun matteModeReadsByte107() {
        assertEquals(0, AepHeader.LayerHeader.parse(ldta(0))!!.matteModeCode)
        assertEquals(1, AepHeader.LayerHeader.parse(ldta(1))!!.matteModeCode)
        assertEquals(2, AepHeader.LayerHeader.parse(ldta(2))!!.matteModeCode)
        assertEquals(3, AepHeader.LayerHeader.parse(ldta(3))!!.matteModeCode)
        assertEquals(4, AepHeader.LayerHeader.parse(ldta(4))!!.matteModeCode)
        assertEquals(7, AepHeader.LayerHeader.parse(ldta(7))!!.matteModeCode)
    }

    @Test
    fun matteLayerIdReadsByte160OnlyWhenPresent() {
        // A 160-byte header (the real file's size) holds no id: the older
        // "layer above" form.
        assertEquals(null, AepHeader.LayerHeader.parse(ldta(1))!!.matteLayerId)
        assertEquals(29L, AepHeader.LayerHeader.parse(ldta(1, 29L))!!.matteLayerId)
        // An id of 0 is no id.
        assertEquals(null, AepHeader.LayerHeader.parse(ldta(1, 0L))!!.matteLayerId)
    }
}
