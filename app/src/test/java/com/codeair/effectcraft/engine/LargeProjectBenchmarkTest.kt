package com.codeair.effectcraft.engine

import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.ProjectEditorActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Large-project benchmarks for the M6 budgets. These assert deterministic
 * budget arithmetic at scale (many layers, many frames, large frames) and
 * that budget evaluation itself stays cheap; they do not claim on-device
 * render timings, which still need physical-device proof.
 */
class LargeProjectBenchmarkTest {

    private fun largeProject(): ProjectDocument = ProjectDocument(
        id = "large-benchmark",
        name = "Large Benchmark",
        width = 3840,
        height = 2160,
        frameRate = 120,
        durationFrames = 3600,
        layers = (1..500).map { index ->
            LayerDocument(
                id = "layer-$index",
                name = "Layer $index",
                kind = "Shape",
                opacity = 1f,
                tint = 0xFF9278F6
            )
        }
    )

    @Test
    fun largeProjectValidatesAndFitsThePerFramePixelBudget() {
        val project = largeProject()
        assertTrue(project.validationErrors().isEmpty())
        assertEquals(500, project.layers.size)
        RenderBudget.checkFrameDimensions(project.width, project.height)
        assertEquals(33_177_600L, RenderBudget.estimatedRgbaBytes(project.width, project.height))
    }

    @Test
    fun largeProjectPreviewTimeIsDeterministicAcrossFrameRates() {
        val project = largeProject()
        val interval = ProjectEditorActions.frameIntervalMillis(project.frameRate)
        assertEquals(8L, interval)
        assertEquals(28_800L, interval * project.durationFrames)
        assertEquals(33L, ProjectEditorActions.frameIntervalMillis(30))
        // Rounded 33 ms interval x 3600 frames = 118,800 ms scheduled, not
        // the ideal 120,000 ms: the rounding is the implemented behavior.
        assertEquals(118_800L, ProjectEditorActions.frameIntervalMillis(30) * 3600L)
    }

    @Test
    fun overBudgetLargePreviewFailsVisiblyInsteadOfAllocating() {
        val failure = runCatching { RenderBudget.checkPreviewMaxSide(4096) }.exceptionOrNull()
        assertTrue(failure is BudgetViolationException)
        assertTrue(failure!!.message!!.contains("Preview"))
    }

    @Test
    fun budgetEvaluationAtScaleCompletesWithinAGenerousBound() {
        val project = largeProject()
        val started = System.nanoTime()
        repeat(10_000) { frame ->
            RenderBudget.checkFrameDimensions(project.width, project.height)
            RenderBudget.estimatedRgbaBytes(project.width, project.height)
            val tracker = RenderBudget.FramePaceTracker(frameIntervalMillis = 8L)
            tracker.observeFrame(if (frame % 4 == 0) 40L else 8L)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        // Budget evaluation is arithmetic, not rendering: 10,000 evaluations
        // must stay far below any frame budget. The 10 s bound only guards
        // against pathological blowup; it is not a render-performance claim.
        assertTrue("Budget evaluation took ${elapsedMillis}ms", elapsedMillis < 10_000L)
    }

    @Test
    fun sustainedLatenessAtScaleTripsThePacingBudget() {
        val tracker = RenderBudget.FramePaceTracker(frameIntervalMillis = 8L)
        var verdict: RenderBudget.PaceVerdict = RenderBudget.PaceVerdict.OnTime
        repeat(3) { verdict = tracker.observeFrame(40L) }
        assertTrue(verdict is RenderBudget.PaceVerdict.Violation)
    }
}
