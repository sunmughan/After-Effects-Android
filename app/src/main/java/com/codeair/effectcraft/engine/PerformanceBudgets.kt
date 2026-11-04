package com.codeair.effectcraft.engine

import kotlin.math.ceil

/**
 * Proposed M6-L3 performance budgets and the large-project fixture
 * definition, in one place so tests and docs cannot drift apart.
 *
 * These thresholds are PROPOSED BY THE AGENT, not user-approved: the
 * numeric budgets were an open user question when L3 was written, so
 * the user may correct any value here and the tests must be updated
 * to match. See docs/performance-budgets.md for the full proposal,
 * the fixture definition, and the scope limits.
 *
 * Scope: emulator CI only. Nothing here proves physical-device
 * behavior, and nothing here proves thermal behavior: the CI
 * emulator has no device thermal sensor to read, so the sustained
 * render test is a throttling proxy (second-half pacing vs
 * first-half pacing), never a thermal pass.
 */
object PerformanceBudgets {
    /** Native media-pool contract, shared with MediaPoolStats. */
    const val MEDIA_POOL_BUDGET_BYTES: Long = MediaPoolStats.ANDROID_BUDGET_BYTES

    /** Proposed peak Dalvik heap in use after loading the large fixture and rendering it. */
    const val PEAK_HEAP_BYTES: Long = 256L * 1024 * 1024

    /** Proposed preview frame budget at the 30 fps composition rate. */
    const val FRAME_BUDGET_MS: Double = 1000.0 / 30.0

    /** Proposed worst-case tail: no more than two frame budgets at p95. */
    const val P95_FRAME_TIME_MS_MAX: Double = 2.0 * FRAME_BUDGET_MS

    /** Proposed allowed share of frames slower than one frame budget. */
    const val MAX_JANK_RATE: Double = 0.20

    /** Proposed sustained-render proxy: second-half mean at most twice the first-half mean. */
    const val SUSTAINED_MEAN_GROWTH_MAX: Double = 2.0

    /** Large-project fixture: one 320x180 composition, 30 fps. */
    const val LARGE_LAYER_COUNT: Int = 48
    const val LARGE_KEYFRAMES_PER_LAYER: Int = 5
    const val LARGE_KEYFRAME_TOTAL: Int = LARGE_LAYER_COUNT * LARGE_KEYFRAMES_PER_LAYER
    /** Observed keys per layer add the frame-0 seed the bridge creates with the first key. */
    const val LARGE_OBSERVED_KEYFRAME_TOTAL: Int = LARGE_LAYER_COUNT * (LARGE_KEYFRAMES_PER_LAYER + 1)
    const val LARGE_MEDIA_COUNT: Int = 0
    const val LARGE_PACING_FRAME_COUNT: Int = 30
    const val LARGE_PREVIEW_MAX_SIDE: Int = 160

    /** Opacity keyframe frames added for each fixture layer; the bridge seeds frame 0 as well. */
    val LARGE_KEYFRAME_FRAMES: List<Long> = listOf(15L, 30L, 45L, 60L, 75L)
}

data class FramePacingReport(
    val frameCount: Int,
    val meanMs: Double,
    val p95Ms: Double,
    val jankRate: Double,
    val withinBudget: Boolean,
) {
    fun reportLine(): String =
        "frames=$frameCount meanMs=$meanMs p95Ms=$p95Ms jankRate=$jankRate " +
            "budgetMs=${PerformanceBudgets.FRAME_BUDGET_MS} withinBudget=$withinBudget"
}

/** Pure frame-pacing evaluation: mean, p95, and jank share against the proposed budgets. */
fun evaluateFramePacing(frameTimesMs: List<Double>): FramePacingReport {
    require(frameTimesMs.isNotEmpty()) { "Frame pacing needs at least one sample" }
    val sorted = frameTimesMs.sorted()
    val mean = frameTimesMs.sum() / frameTimesMs.size
    val p95Index = (ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
    val p95 = sorted[p95Index]
    val jank = frameTimesMs.count { it > PerformanceBudgets.FRAME_BUDGET_MS }.toDouble() / frameTimesMs.size
    val within = mean <= PerformanceBudgets.FRAME_BUDGET_MS &&
        p95 <= PerformanceBudgets.P95_FRAME_TIME_MS_MAX &&
        jank <= PerformanceBudgets.MAX_JANK_RATE
    return FramePacingReport(
        frameCount = frameTimesMs.size,
        meanMs = mean,
        p95Ms = p95,
        jankRate = jank,
        withinBudget = within,
    )
}
