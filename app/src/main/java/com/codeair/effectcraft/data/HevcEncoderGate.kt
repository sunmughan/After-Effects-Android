package com.codeair.effectcraft.data

/**
 * Pure gate for the H.265/HEVC opt-in (user decision 2026-11-06: HEVC is an
 * explicit opt-in only, offered after a runtime probe confirms a hardware
 * HEVC encoder exists; H.264 in MP4 stays the only default and there is no
 * AV1 encode).
 *
 * This file has no Android imports so the gate is JVM-testable: the Android
 * probe in [HevcEncoderProbe] only maps MediaCodecList entries to
 * [EncoderCandidate] and calls [pick]. A software HEVC encoder (for
 * example the emulator's OMX.google HEVC encoder) never opens the opt-in,
 * because software HEVC encode is slow and the pick is about hardware.
 */
object HevcEncoderGate {
    const val MIME = "video/hevc"

    /** MediaCodec flexible YUV420 input, the same input the H.264 path requires. */
    const val COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888

    /** One encoder as seen by MediaCodecList, reduced to the facts the gate needs. */
    data class EncoderCandidate(
        val name: String,
        val isEncoder: Boolean,
        val supportedTypes: List<String>,
        val colorFormats: List<Int>,
        /** Null when the platform cannot answer (API < 29); the name heuristic decides then. */
        val hardwareAccelerated: Boolean?
    )

    data class Result(val codecName: String?)

    /** The first hardware HEVC encoder accepting flexible YUV420 input, else null. */
    fun pick(candidates: List<EncoderCandidate>): Result {
        val name = candidates.firstOrNull { candidate ->
            candidate.isEncoder &&
                candidate.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                candidate.colorFormats.contains(COLOR_FORMAT_YUV420_FLEXIBLE) &&
                isHardware(candidate)
        }?.name
        return Result(name)
    }

    /**
     * Hardware test: the platform flag on API 29+, otherwise the codec-name
     * heuristic. OMX.google.* and c2.android.* encoders are the platform
     * software encoders; vendor names (OMX.qcom, OMX.Exynos, OMX.MTK,
     * c2.qti, ...) are hardware. The heuristic is documented as a
     * heuristic: on API 29+ the flag always wins.
     */
    fun isHardware(candidate: EncoderCandidate): Boolean {
        candidate.hardwareAccelerated?.let { return it }
        val lower = candidate.name.lowercase()
        return !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
    }
}
