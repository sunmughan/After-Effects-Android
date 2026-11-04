package ai.effectcraft.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend status must be honestly visible: the exact string the UI shows
 * is derived from the exact values the native side reported, never from a
 * shape-only assertion. GPU names its adapter; CPU names its reason; a
 * lost device is labelled as lost.
 */
class BackendStatusTest {

    @Test fun gpuDisplayNamesAdapter() {
        val status = NativeSession.BackendStatus("gpu", "Adreno 830", null, false)
        assertEquals("GPU · Adreno 830", status.displayText())
        assertTrue(status.reportLine().contains("backend=gpu"))
        assertTrue(status.reportLine().contains("adapter=Adreno 830"))
    }

    @Test fun cpuDisplayNamesFallbackReason() {
        val status = NativeSession.BackendStatus("cpu", null, "No Vulkan device found", false)
        assertEquals("CPU · No Vulkan device found", status.displayText())
        assertTrue(status.reportLine().contains("backend=cpu"))
        assertTrue(status.reportLine().contains("reason=No Vulkan device found"))
    }

    @Test fun lostDeviceIsLabelledLostInDisplayAndReport() {
        val status = NativeSession.BackendStatus("cpu", null, "Simulated device loss (diagnostic hook)", true)
        assertEquals("CPU · Simulated device loss (diagnostic hook) · device lost", status.displayText())
        assertTrue(status.reportLine().contains("lost=true"))
    }

    @Test fun missingAdapterOrReasonFallsBackToPlainBackendLabel() {
        assertEquals("GPU", NativeSession.BackendStatus("gpu", null, null, false).displayText())
        assertEquals("CPU", NativeSession.BackendStatus("cpu", null, null, false).displayText())
    }
}
