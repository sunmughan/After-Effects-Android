package ai.effectcraft.android.engine

import ai.effectcraft.android.data.RenderQueuePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforced render budgets: frame-pacing and memory budgets fail visibly via
 * [BudgetViolationException], never by silently degrading a frame. The pixel
 * budget mirrors the decode/queue budgets already in the codebase; the
 * cross-check below pins them to the same value so they cannot drift apart.
 */
class RenderBudgetTest {

    @Test
    fun pixelBudgetMatchesDecodeAndQueueBudgets() {
        assertEquals(RenderedFramePng.MAX_PIXELS, RenderBudget.MAX_PIXELS_PER_FRAME)
        assertEquals(RenderQueuePlan.MAX_PIXELS_PER_FRAME, RenderBudget.MAX_PIXELS_PER_FRAME)
    }

    @Test
    fun previewSideBudgetAcceptsFullScaleAndLargestInBudgetSide() {
        // 0 means full scale: the rendered frame itself is bounded downstream
        // by the decode pixel budget, so 0 is not an unbounded preview.
        RenderBudget.checkPreviewMaxSide(0)
        RenderBudget.checkPreviewMaxSide(512)
        RenderBudget.checkPreviewMaxSide(RenderBudget.maxPreviewSide())
        assertEquals(2896, RenderBudget.maxPreviewSide())
    }

    @Test
    fun previewSideBudgetFailsVisiblyAboveThePixelBudget() {
        val failure = runCatching { RenderBudget.checkPreviewMaxSide(2897) }.exceptionOrNull()
        assertTrue(failure is BudgetViolationException)
        assertTrue(failure!!.message!!.contains("8,388,608"))
        assertTrue(runCatching { RenderBudget.checkPreviewMaxSide(4096) }.isFailure)
        assertTrue(runCatching { RenderBudget.checkPreviewMaxSide(-1) }.isFailure)
    }

    @Test
    fun frameDimensionBudgetRejectsOverBudgetFramesWithTheBudgetNamed() {
        RenderBudget.checkFrameDimensions(3840, 2160)
        val failure = runCatching { RenderBudget.checkFrameDimensions(4096, 4096) }.exceptionOrNull()
        assertTrue(failure is BudgetViolationException)
        assertTrue(failure!!.message!!.contains("8,388,608"))
    }

    @Test
    fun estimatedRgbaBytesUseLongMathWithoutIntOverflow() {
        assertEquals(8_294_400L, RenderBudget.estimatedRgbaBytes(1920, 1080))
        assertEquals(33_177_600L, RenderBudget.estimatedRgbaBytes(3840, 2160))
        assertEquals(40_000_000_000L, RenderBudget.estimatedRgbaBytes(100_000, 100_000))
        assertTrue(runCatching { RenderBudget.estimatedRgbaBytes(0, 1080) }.isFailure)
        assertTrue(runCatching { RenderBudget.estimatedRgbaBytes(-1, 1080) }.isFailure)
    }

    @Test
    fun paceTrackerTripsVisiblyAfterSustainedLateFramesAndResetsOnTime() {
        val tracker = RenderBudget.FramePaceTracker(frameIntervalMillis = 33L)
        assertEquals(RenderBudget.PaceVerdict.OnTime, tracker.observeFrame(10L))
        assertEquals(RenderBudget.PaceVerdict.Late(1), tracker.observeFrame(40L))
        assertEquals(RenderBudget.PaceVerdict.Late(2), tracker.observeFrame(100L))
        val tripped = tracker.observeFrame(90L)
        assertTrue(tripped is RenderBudget.PaceVerdict.Violation)
        assertEquals(3, (tripped as RenderBudget.PaceVerdict.Violation).consecutiveLate)
        // One on-time frame clears the streak: a transient hitch must not
        // permanently trip the budget, and sustained lateness must re-trip.
        assertEquals(RenderBudget.PaceVerdict.OnTime, tracker.observeFrame(33L))
        assertEquals(0, tracker.consecutiveLate())
        assertTrue(runCatching { tracker.observeFrame(-1L) }.isFailure)
    }

    @Test
    fun paceTrackerRejectsInvalidConfigurationInsteadOfSilentlyCoercing() {
        assertTrue(runCatching { RenderBudget.FramePaceTracker(frameIntervalMillis = 0L) }.isFailure)
        assertTrue(runCatching { RenderBudget.FramePaceTracker(frameIntervalMillis = 33L, maxConsecutiveLate = 0) }.isFailure)
    }
}
