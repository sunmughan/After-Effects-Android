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
}
