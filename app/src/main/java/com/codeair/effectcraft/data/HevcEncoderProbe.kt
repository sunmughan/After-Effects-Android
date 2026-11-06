package com.codeair.effectcraft.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Live runtime probe for a hardware H.265/HEVC encoder. Thin Android
 * mapping only: MediaCodecList entries become [HevcEncoderGate.EncoderCandidate]
 * and the decision lives in [HevcEncoderGate.pick]. Never throws: any
 * failure to list codecs means no HEVC encoder is offered.
 *
 * The platform `isHardwareAccelerated` flag exists only from API 29; below
 * that the gate's name heuristic decides. The gate matches flexible YUV420
 * against its own literal of the platform constant; the instrumented probe
 * test asserts that literal equals
 * `MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible`, so it can
 * never drift silently.
 */
object HevcEncoderProbe {
    /** Live probe against the device's codec list. */
    fun probe(): HevcEncoderGate.Result = runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        HevcEncoderGate.pick(infos.map { info ->
            HevcEncoderGate.EncoderCandidate(
                name = info.name,
                isEncoder = info.isEncoder,
                supportedTypes = info.supportedTypes.toList(),
                colorFormats = runCatching {
                    info.getCapabilitiesForType(HevcEncoderGate.MIME).colorFormats.toList()
                }.getOrDefault(emptyList()),
                hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { info.isHardwareAccelerated }.getOrNull()
                } else null
            )
        })
    }.getOrDefault(HevcEncoderGate.Result(null))
}
