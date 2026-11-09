package com.codeair.effectcraft.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [timelineCardHeight]: the timeline must fit on a short landscape
 * phone. Portrait keeps the old rule (~42%, 260–340dp); landscape takes
 * ~30% with a 140–220dp band, so header + toolbar + timeline + audio
 * fit inside ~360dp and the preview is never squeezed to nothing.
 */
class TimelineHeightTest {

    @Test
    fun portraitKeepsOldRule() {
        assertEquals(336.dp, timelineCardHeight(800.dp, isLandscape = false))
        assertEquals(340.dp, timelineCardHeight(1000.dp, isLandscape = false))
        assertEquals(260.dp, timelineCardHeight(500.dp, isLandscape = false))
    }

    @Test
    fun landscapeUsesLowerBand() {
        // 360dp tall: 30% is 108dp, floored to 140dp.
        assertEquals(140.dp, timelineCardHeight(360.dp, isLandscape = true))
        // 600dp tall: 30% is 180dp, inside the band.
        assertEquals(180.dp, timelineCardHeight(600.dp, isLandscape = true))
        // 1000dp tall: 30% is 300dp, capped to 220dp.
        assertEquals(220.dp, timelineCardHeight(1000.dp, isLandscape = true))
    }

    @Test
    fun landscapeFitsShortScreen() {
        // The whole landscape column on a 360dp-tall phone: header ~56dp
        // + toolbar ~48dp + timeline + compact audio header 48dp must
        // leave room for the preview.
        val timeline = timelineCardHeight(360.dp, isLandscape = true)
        val chrome = 56.dp + 48.dp + 48.dp
        assertEquals(true, timeline + chrome < 360.dp)
    }
}
