package com.codeair.effectcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native media-pool cache accounting, parsed where it enters Kotlin.
 * The budget is the Android budget (256 MiB per session pool), not the
 * upstream 1 GiB default: a pool reporting more than its budget, or a
 * budget other than the Android value, is a regression.
 */
class MediaPoolStatsTest {

    @Test fun parsesNativeStatsJson() {
        val stats = MediaPoolStats.parse(
            """{"frames":3,"bytes":16384,"budget":268435456,"hits":9,"misses":2,"opened":1}"""
        )
        assertEquals(3, stats!!.frames)
        assertEquals(16384L, stats.bytes)
        assertEquals(268435456L, stats.budgetBytes)
        assertEquals(9L, stats.hits)
        assertEquals(2L, stats.misses)
        assertTrue(stats.withinBudget)
    }

    @Test fun overBudgetStatsAreReportedNotHidden() {
        val stats = MediaPoolStats.parse(
            """{"frames":1,"bytes":268435457,"budget":268435456,"hits":0,"misses":1,"opened":1}"""
        )
        assertFalse(stats!!.withinBudget)
        assertTrue(stats.displayText().contains("OVER BUDGET"))
    }

    @Test fun androidBudgetConstantIs256MiB() {
        assertEquals(256L * 1024 * 1024, MediaPoolStats.ANDROID_BUDGET_BYTES)
    }

    @Test fun malformedStatsParseToNull() {
        assertNull(MediaPoolStats.parse(""))
        assertNull(MediaPoolStats.parse("{}"))
        assertNull(MediaPoolStats.parse("not json"))
    }

    @Test fun rejectsNegativeCountersAndBudgetDriftAtNativeBoundary() {
        val budget = MediaPoolStats.ANDROID_BUDGET_BYTES
        assertNull(MediaPoolStats.parse("""{"frames":-1,"bytes":0,"budget":${budget},"hits":0,"misses":0}"""))
        assertNull(MediaPoolStats.parse("""{"frames":0,"bytes":-1,"budget":${budget},"hits":0,"misses":0}"""))
        assertNull(MediaPoolStats.parse("""{"frames":0,"bytes":0,"budget":${budget * 4},"hits":0,"misses":0}"""))
        assertNull(MediaPoolStats.parse("""{"frames":0,"bytes":0,"budget":${budget},"hits":-1,"misses":0}"""))
        assertNull(MediaPoolStats.parse("""{"frames":0,"bytes":0,"budget":${budget},"hits":0,"misses":-1}"""))
    }

    @Test fun displayTextNamesFramesBytesAndBudget() {
        val stats = MediaPoolStats.parse(
            """{"frames":2,"bytes":1024,"budget":268435456,"hits":1,"misses":1,"opened":1}"""
        )
        assertEquals("Media cache · 2 frames · 1024 bytes / 268435456 budget", stats!!.displayText())
    }
}
