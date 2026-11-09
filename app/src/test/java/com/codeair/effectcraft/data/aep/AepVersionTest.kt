package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Version word decoding (port of the official `version` tests). */
class AepVersionTest {

    @Test
    fun sampleWordsDecode() {
        assertEquals("26.2.1x2", AepVersion.AeVersion.fromWord(0x0f11_0e02L)?.text())
        assertEquals("25.6.0x97", AepVersion.AeVersion.fromWord(0x0f0b_0661L)?.text())
        assertEquals("24.6.0x65", AepVersion.AeVersion.fromWord(0x0f03_0641L)?.text())
    }

    @Test
    fun emptyWordsAreNull() {
        assertNull(AepVersion.AeVersion.fromWord(0L))
        assertNull(AepVersion.AeVersion.fromWord(0xFFFFFFFFL))
        assertNull(AepVersion.AeVersion.fromWord(0x0c00_0000L))
    }

    @Test
    fun syntheticAepIs26() {
        val bytes = AepContainerTest.resourceBytes("/aep/synthetic.aep")
        val file = AepContainer.parse(bytes)
        assertEquals("26.2.1x2", file.fileVersion()?.text())
        assertEquals("26.2.1x2", file.savedByVersion()?.text())
        assertEquals(97, file.headRevision())
        assertEquals(true, file.fileVersion()?.major == 26)
    }
}
