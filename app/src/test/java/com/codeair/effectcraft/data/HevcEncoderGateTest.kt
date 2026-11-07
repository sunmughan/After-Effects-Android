package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevcEncoderGateTest {
    private val flexible = HevcEncoderGate.COLOR_FORMAT_YUV420_FLEXIBLE

    private fun candidate(
        name: String,
        isEncoder: Boolean = true,
        types: List<String> = listOf("video/hevc"),
        colorFormats: List<Int> = listOf(flexible),
        hardwareAccelerated: Boolean? = true
    ) = HevcEncoderGate.EncoderCandidate(name, isEncoder, types, colorFormats, hardwareAccelerated)

    @Test
    fun picksHardwareHevcEncoderWithFlexibleInput() {
        val result = HevcEncoderGate.pick(
            listOf(candidate("OMX.qcom.video.encoder.hevc"))
        )
        assertEquals("OMX.qcom.video.encoder.hevc", result.codecName)
    }

    @Test
    fun skipsSoftwareOnlyHevcEncoder() {
        val result = HevcEncoderGate.pick(
            listOf(
                candidate("OMX.google.hevc.encoder", hardwareAccelerated = false),
                candidate("c2.android.hevc.encoder", hardwareAccelerated = false)
            )
        )
        assertNull(result.codecName)
    }

    @Test
    fun nameHeuristicDecidesBelowApi29() {
        val hardware = HevcEncoderGate.pick(
            listOf(candidate("OMX.qcom.video.encoder.hevc", hardwareAccelerated = null))
        )
        assertEquals("OMX.qcom.video.encoder.hevc", hardware.codecName)
        val software = HevcEncoderGate.pick(
            listOf(candidate("OMX.google.hevc.encoder", hardwareAccelerated = null))
        )
        assertNull(software.codecName)
    }

    @Test
    fun skipsHevcEncoderWithoutFlexibleInput() {
        val result = HevcEncoderGate.pick(
            listOf(candidate("OMX.qcom.video.encoder.hevc", colorFormats = listOf(19)))
        )
        assertNull(result.codecName)
    }

    @Test
    fun ignoresDecodersAndOtherCodecs() {
        val result = HevcEncoderGate.pick(
            listOf(
                candidate("OMX.qcom.video.decoder.hevc", isEncoder = false),
                candidate("OMX.qcom.video.encoder.avc", types = listOf("video/avc"))
            )
        )
        assertNull(result.codecName)
    }

    @Test
    fun returnsNullWhenNoHevcEncoderExists() {
        assertNull(HevcEncoderGate.pick(emptyList()).codecName)
    }

    @Test
    fun platformFlagWinsOverNameHeuristic() {
        // A vendor-named encoder the platform reports as software is not hardware.
        val result = HevcEncoderGate.pick(
            listOf(candidate("OMX.qcom.video.encoder.hevc", hardwareAccelerated = false))
        )
        assertNull(result.codecName)
    }
}
