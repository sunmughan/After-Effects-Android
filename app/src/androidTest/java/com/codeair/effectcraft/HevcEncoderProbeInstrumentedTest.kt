package com.codeair.effectcraft

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codeair.effectcraft.data.HevcEncoderGate
import com.codeair.effectcraft.data.HevcEncoderProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HevcEncoderProbeInstrumentedTest {
    @Test
    fun gateFlexibleFormatMatchesPlatformConstant() {
        assertEquals(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            HevcEncoderGate.COLOR_FORMAT_YUV420_FLEXIBLE
        )
    }

    @Test
    fun probeResultMatchesIndependentHardwareHevcScan() {
        val result = HevcEncoderProbe.probe()
        val hardwareHevc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                runCatching {
                    codec.getCapabilitiesForType("video/hevc").colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }.getOrDefault(false) &&
                HevcEncoderGate.isHardware(
                    HevcEncoderGate.EncoderCandidate(
                        name = codec.name,
                        isEncoder = codec.isEncoder,
                        supportedTypes = codec.supportedTypes.toList(),
                        colorFormats = emptyList(),
                        hardwareAccelerated = if (android.os.Build.VERSION.SDK_INT >= 29) {
                            runCatching { codec.isHardwareAccelerated }.getOrNull()
                        } else null
                    )
                )
        }?.name
        assertEquals(hardwareHevc, result.codecName)
        if (result.codecName != null) {
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == result.codecName }
            assertTrue("Probed HEVC encoder must exist in MediaCodecList", codec != null)
            assertTrue("Probed HEVC codec must be an encoder", codec!!.isEncoder)
        } else {
            assertNull("No hardware HEVC encoder on this device", hardwareHevc)
        }
    }
}
