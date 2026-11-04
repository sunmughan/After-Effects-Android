package com.codeair.effectcraft

import android.os.Debug
import android.util.Log
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import com.codeair.effectcraft.engine.PerformanceBudgets
import com.codeair.effectcraft.engine.evaluateFramePacing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6-L3 benchmarks for the large-project fixture defined in
 * docs/performance-budgets.md and engine/PerformanceBudgets.kt.
 *
 * The thresholds enforced here are the AGENT PROPOSAL, not
 * user-approved values. The fixture is built reproducibly through
 * the native bridge (the same path the editor uses): 48 text layers
 * on the canonical empty composition, short text per layer, and 5
 * added opacity keyframes per layer (288 observed keys including
 * the frame-0 seed per layer). It carries no media, so it benchmarks
 * layer/keyframe scale and preview rendering, not media decode.
 *
 * Emulator CI only: frame times and heap numbers are emulator
 * measurements. The sustained-render test is a throttling proxy
 * (second-half pacing vs first-half pacing); it is NOT a thermal
 * pass, and no thermal claim is made here. Physical-device memory,
 * pacing, and thermal measurements remain open.
 *
 * Frames are distinct per render call because the session frame
 * cache is keyed on project plus composition plus frame plus size:
 * re-rendering one frame would measure a cache hit, not rendering.
 */
class LargeProjectBenchmarkInstrumentedTest {

    private fun applyEdit(envelope: String?): String {
        val response = JSONObject(envelope ?: throw AssertionError("Native bridge returned null"))
        assertTrue(
            "Canonical edit must succeed, got error: ${response.optString("error")}",
            response.optBoolean("valid"),
        )
        return response.getString("project")
    }

    private fun buildLargeProject(): String {
        assertTrue(NativeProjectBridge.isAvailable())
        var project = NativeSessionInstrumentedTest.FIXTURE_JSON
        for (index in 1..PerformanceBudgets.LARGE_LAYER_COUNT) {
            project = applyEdit(
                NativeProjectBridge.addTextLayerEcproj(
                    project,
                    NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                    "Layer $index",
                ),
            )
        }
        val metadata = NativeProjectBridge.inspectEcproj(project)
            ?: throw AssertionError("Native inspect returned null for the large fixture")
        val layers = CanonicalProjectInfo.parse(metadata).compositions.single().layers
        assertEquals(PerformanceBudgets.LARGE_LAYER_COUNT, layers.size)
        for (layer in layers) {
            project = applyEdit(
                NativeProjectBridge.setTextLayerTextEcproj(
                    project,
                    NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                    layer.id,
                    layer.name,
                ),
            )
            for ((keyIndex, frame) in PerformanceBudgets.LARGE_KEYFRAME_FRAMES.withIndex()) {
                val value = if (keyIndex % 2 == 0) 100.0 else 50.0
                project = applyEdit(
                    NativeProjectBridge.setLayerOpacityKeyframeEcproj(
                        project,
                        NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                        layer.id,
                        frame,
                        value,
                    ),
                )
            }
        }
        return project
    }

    private fun heapUsedBytes(): Long {
        Runtime.getRuntime().gc()
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun renderTimed(session: NativeSession, frame: Long): Double {
        val start = System.nanoTime()
        val rendered = session.render(NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID, frame)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertNotNull("Large fixture must render frame $frame", rendered)
        return elapsedMs
    }

    @Test
    fun largeProjectFixtureDefinitionMatchesCounts() {
        val project = buildLargeProject()
        assertTrue("Large fixture project must be non-empty", project.isNotEmpty())
        val metadata = NativeProjectBridge.inspectEcproj(project)
            ?: throw AssertionError("Native inspect returned null for the large fixture")
        val composition = CanonicalProjectInfo.parse(metadata).compositions.single()
        assertEquals(PerformanceBudgets.LARGE_LAYER_COUNT, composition.layers.size)
        val observedKeys = composition.layers.sumOf { it.opacityKeys.size }
        assertEquals(PerformanceBudgets.LARGE_OBSERVED_KEYFRAME_TOTAL, observedKeys)
        Log.i(
            "EffectCraftBenchmark",
            "M6-L3 fixture: layers=${composition.layers.size} observedKeys=$observedKeys " +
                "projectBytes=${project.toByteArray(Charsets.UTF_8).size} mediaCount=0",
        )
    }

    @Test
    fun largeProjectLoadStaysWithinMemoryBudgets() {
        val buildStart = System.nanoTime()
        val project = buildLargeProject()
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0
        val session = NativeSession.open(project, PerformanceBudgets.LARGE_PREVIEW_MAX_SIDE)
        assertNotNull("Session must open for the large fixture", session)
        session!!
        try {
            val frame = session.render(NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID, 0)
            assertNotNull("Session must render the large fixture", frame)
            val stats = session.mediaPoolStats()
            assertNotNull("Media pool stats must be reported", stats)
            stats!!
            assertEquals(PerformanceBudgets.MEDIA_POOL_BUDGET_BYTES, stats.budgetBytes)
            assertTrue("Media pool bytes must stay within the 256 MiB budget", stats.withinBudget)
            val heap = heapUsedBytes()
            val pssBytes = Debug.getPss() * 1024L
            Log.i(
                "EffectCraftBenchmark",
                "M6-L3 memory: buildMs=$buildMs mediaBytes=${stats.bytes} " +
                    "mediaBudget=${stats.budgetBytes} heapBytes=$heap " +
                    "heapBudget=${PerformanceBudgets.PEAK_HEAP_BYTES} pssBytes=$pssBytes",
            )
            assertTrue(
                "Heap in use after large-fixture load and render must stay within the proposed budget",
                heap <= PerformanceBudgets.PEAK_HEAP_BYTES,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun largeProjectPreviewFramePacingMeetsProposedBudget() {
        val project = buildLargeProject()
        val session = NativeSession.open(project, PerformanceBudgets.LARGE_PREVIEW_MAX_SIDE)
        assertNotNull("Session must open for the large fixture", session)
        session!!
        try {
            val times = (0 until PerformanceBudgets.LARGE_PACING_FRAME_COUNT).map { frame ->
                renderTimed(session, frame.toLong())
            }
            val report = evaluateFramePacing(times)
            Log.i("EffectCraftBenchmark", "M6-L3 pacing: ${report.reportLine()}")
            assertTrue(
                "Mean frame time must meet the proposed budget: ${report.reportLine()}",
                report.meanMs <= PerformanceBudgets.FRAME_BUDGET_MS,
            )
            assertTrue(
                "p95 frame time must meet the proposed budget: ${report.reportLine()}",
                report.p95Ms <= PerformanceBudgets.P95_FRAME_TIME_MS_MAX,
            )
            assertTrue(
                "Jank rate must meet the proposed budget: ${report.reportLine()}",
                report.jankRate <= PerformanceBudgets.MAX_JANK_RATE,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun largeProjectSustainedRenderHasNoThermalProxyRegression() {
        val project = buildLargeProject()
        val session = NativeSession.open(project, PerformanceBudgets.LARGE_PREVIEW_MAX_SIDE)
        assertNotNull("Session must open for the large fixture", session)
        session!!
        try {
            val firstHalf = (0 until PerformanceBudgets.LARGE_PACING_FRAME_COUNT).map { frame ->
                renderTimed(session, frame.toLong())
            }
            val secondHalf = (PerformanceBudgets.LARGE_PACING_FRAME_COUNT until
                2 * PerformanceBudgets.LARGE_PACING_FRAME_COUNT).map { frame ->
                renderTimed(session, frame.toLong())
            }
            val firstMean = firstHalf.sum() / firstHalf.size
            val secondMean = secondHalf.sum() / secondHalf.size
            Log.i(
                "EffectCraftBenchmark",
                "M6-L3 sustained proxy (NOT a thermal pass): firstMeanMs=$firstMean " +
                    "secondMeanMs=$secondMean growthMax=${PerformanceBudgets.SUSTAINED_MEAN_GROWTH_MAX}",
            )
            assertTrue(
                "Second-half mean must not exceed the sustained proxy factor over the first half",
                secondMean <= PerformanceBudgets.SUSTAINED_MEAN_GROWTH_MAX * firstMean,
            )
        } finally {
            session.close()
        }
    }
}
