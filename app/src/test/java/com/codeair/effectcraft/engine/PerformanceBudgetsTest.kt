package com.codeair.effectcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proposed M6-L3 budgets and pacing math. The numeric values here
 * are the agent proposal documented in docs/performance-budgets.md,
 * not user-approved thresholds: if the user corrects a value, this
 * test and the doc must change together.
 */
class PerformanceBudgetsTest {

    @Test fun proposedBudgetsMatchTheDocumentedProposal() {
        assertEquals(256L * 1024 * 1024, PerformanceBudgets.MEDIA_POOL_BUDGET_BYTES)
        assertEquals(256L * 1024 * 1024, PerformanceBudgets.PEAK_HEAP_BYTES)
        assertEquals(1000.0 / 30.0, PerformanceBudgets.FRAME_BUDGET_MS, 0.001)
        assertEquals(2.0 * 1000.0 / 30.0, PerformanceBudgets.P95_FRAME_TIME_MS_MAX, 0.001)
        assertEquals(0.20, PerformanceBudgets.MAX_JANK_RATE, 0.001)
        assertEquals(2.0, PerformanceBudgets.SUSTAINED_MEAN_GROWTH_MAX, 0.001)
    }

    @Test fun largeFixtureDefinitionIsFixed() {
        assertEquals(48, PerformanceBudgets.LARGE_LAYER_COUNT)
        assertEquals(5, PerformanceBudgets.LARGE_KEYFRAMES_PER_LAYER)
        assertEquals(240, PerformanceBudgets.LARGE_KEYFRAME_TOTAL)
        assertEquals(288, PerformanceBudgets.LARGE_OBSERVED_KEYFRAME_TOTAL)
        assertEquals(0, PerformanceBudgets.LARGE_MEDIA_COUNT)
        assertEquals(30, PerformanceBudgets.LARGE_PACING_FRAME_COUNT)
        assertEquals(160, PerformanceBudgets.LARGE_PREVIEW_MAX_SIDE)
        assertEquals(listOf(15L, 30L, 45L, 60L, 75L), PerformanceBudgets.LARGE_KEYFRAME_FRAMES)
    }

    @Test fun pacingPassesWhenAllFramesMeetBudget() {
        val report = evaluateFramePacing(List(30) { 10.0 })
        assertEquals(30, report.frameCount)
        assertEquals(10.0, report.meanMs, 0.001)
        assertEquals(10.0, report.p95Ms, 0.001)
        assertEquals(0.0, report.jankRate, 0.001)
        assertTrue(report.withinBudget)
        assertTrue(report.reportLine().contains("withinBudget=true"))
    }

    @Test fun pacingFailsOnSlowMean() {
        val report = evaluateFramePacing(List(30) { 40.0 })
        assertFalse(report.withinBudget)
    }

    @Test fun pacingFailsOnExcessJankEvenWithPassingMean() {
        val slow = List(10) { 100.0 }
        val fast = List(20) { 1.0 }
        val report = evaluateFramePacing(slow + fast)
        assertTrue(report.meanMs <= PerformanceBudgets.FRAME_BUDGET_MS)
        assertEquals(10.0 / 30.0, report.jankRate, 0.001)
        assertFalse(report.withinBudget)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pacingRejectsNegativeFrameTime() {
        evaluateFramePacing(listOf(10.0, -0.1, 11.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pacingRejectsNaNFrameTime() {
        evaluateFramePacing(listOf(10.0, Double.NaN, 11.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pacingRejectsInfiniteFrameTime() {
        evaluateFramePacing(listOf(10.0, Double.POSITIVE_INFINITY, 11.0))
    }

    @Test fun pacingP95UsesThe95thPercentileSample() {
        val samples = List(19) { 5.0 } + listOf(200.0)
        val report = evaluateFramePacing(samples)
        assertEquals(200.0, report.p95Ms, 0.001)
        assertFalse(report.withinBudget)
    }
}
