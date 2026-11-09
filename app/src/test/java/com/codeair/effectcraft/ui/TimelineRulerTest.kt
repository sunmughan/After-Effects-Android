package com.codeair.effectcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timeline ruler scales to the real project duration: label steps
 * widen as compositions get longer so labels never collide, and the
 * end label stays a readable mm:ss at any length.
 */
class TimelineRulerTest {

    @Test
    fun labelStepIsOneSecondOnShortCompositions() {
        assertEquals(1, rulerLabelStepSeconds(5f))
        assertEquals(1, rulerLabelStepSeconds(30f))
    }

    @Test
    fun labelStepIsFiveSecondsUpToFiveMinutes() {
        assertEquals(5, rulerLabelStepSeconds(31f))
        assertEquals(5, rulerLabelStepSeconds(300f))
    }

    @Test
    fun labelStepIsFifteenSecondsUpToThirtyMinutes() {
        assertEquals(15, rulerLabelStepSeconds(301f))
        assertEquals(15, rulerLabelStepSeconds(1800f))
    }

    @Test
    fun labelStepIsOneMinuteBeyondThirtyMinutes() {
        assertEquals(60, rulerLabelStepSeconds(1801f))
        assertEquals(60, rulerLabelStepSeconds(10800f))
    }

    @Test
    fun endLabelReadsMinutesAndSeconds() {
        assertEquals("0:05", timelineEndLabel(150, 30))
        assertEquals("2:00", timelineEndLabel(3600, 30))
        assertEquals("10:00", timelineEndLabel(18000, 30))
        assertEquals("60:00", timelineEndLabel(108000, 30))
    }
}
