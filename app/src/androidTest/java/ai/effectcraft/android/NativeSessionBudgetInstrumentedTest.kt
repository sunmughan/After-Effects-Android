package ai.effectcraft.android

import ai.effectcraft.android.engine.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator proof that the render-budget gate is wired into the session, not
 * just into pure budget math: an over-budget preview size can neither open
 * a session nor resize a live one, so render() can never be handed an
 * over-budget configuration. Uses the real canonical fixture from
 * [NativeSessionInstrumentedTest]; no new fixture is invented here.
 */
class NativeSessionBudgetInstrumentedTest {

    @Test
    fun overBudgetPreviewSizeCannotOpenASession() {
        assertNull(
            NativeSession.open(NativeSessionInstrumentedTest.FIXTURE_JSON, 4096)
        )
        assertNull(
            NativeSession.open(NativeSessionInstrumentedTest.FIXTURE_JSON, 2897)
        )
    }

    @Test
    fun overBudgetResizeIsRefusedAndKeepsTheInBudgetSize() {
        val session = NativeSession.open(NativeSessionInstrumentedTest.FIXTURE_JSON, 512)
        assertNotNull(session)
        try {
            assertFalse(session!!.resize(4096))
            assertEquals(512, session.currentMaxSide())
            assertTrue(session.resize(80))
            assertEquals(80, session.currentMaxSide())
        } finally {
            session?.close()
        }
    }
}
