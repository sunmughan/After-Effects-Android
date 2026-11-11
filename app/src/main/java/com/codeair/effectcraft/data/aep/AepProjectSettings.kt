package com.codeair.effectcraft.data.aep

/**
 * Project settings (colour depth, working space, working gamma, linear-light
 * switches, time display, frame count start), mirroring the official
 * importer's settings.rs: every setting the file stores replaces the current
 * value, a setting the file does not hold (or holds in an unknown form)
 * keeps the current value and is reported.
 *
 * The working space comes from the `Utf8` right after `PwCs`: `{}` means no
 * colour management, otherwise the profile's name (`colorProfileName`). A
 * profile stored as data without a name is not decoded in this build (the
 * official importer reads the ICC description); it is reported and the
 * current working space is kept.
 */
object AepProjectSettings {

    /** The settings the file specifies; null keeps the converter default. */
    data class Patch(
        /** "Bpc8", "Bpc16" or "Bpc32". */
        val bitDepth: String? = null,
        /** "Timecode", "Frames", "Feet35" or "Feet16". */
        val timeDisplay: String? = null,
        val frameStart: Long? = null,
        val workingGamma: Double? = null,
        val linearize: Boolean = false,
        val blendLinear: Boolean = false,
        /** "Srgb" or "Rec709"; null keeps the converter default (no working space). */
        val workingSpace: String? = null,
        /** Set to the working space when it is not sRGB. */
        val outputSpace: String? = null
    )

    /** The working spaces whose After Effects name is sampled and that EffectCraft has. */
    fun colorSpace(name: String): String? = when (name) {
        "sRGB IEC61966-2.1" -> "Srgb"
        // The second name is the one After Effects shows for the profile EffectCraft's
        // exporter writes (its description tag) once it re-saves such a project.
        "Rec.709 Gamma 2.4", "Rec.709 Gamma 2.4 (EffectCraft)" -> "Rec709"
        else -> null
    }

    fun read(file: AepContainer.AepFile, col: AepImportReport.Collector): Patch {
        val nhed = file.chunk("nhed")?.bytes()
        val byte = { i: Int -> nhed?.getOrNull(i)?.toInt()?.and(0xFF) }
        val bitDepth = when (byte(15)) {
            0 -> "Bpc8"
            1 -> "Bpc16"
            2 -> "Bpc32"
            null -> null
            else -> {
                // The stored code (`nhed` byte 15) is 0, 1 or 2; another one is not guessed at.
                col.lose(
                    AepImportReport.LossCategory.ProjectSettings,
                    "colour depth code ${byte(15)} (not known; the current depth is kept)"
                )
                null
            }
        }
        val (mode, feetFlag, fpf) = Triple(byte(8), byte(11), byte(13))
        val timeDisplay: String? = when {
            mode == 0 -> "Timecode"
            mode == 1 && feetFlag == 0 -> "Frames"
            mode == 1 && feetFlag == 1 && fpf == 16 -> "Feet35"
            mode == 1 && feetFlag == 1 && fpf == 40 -> "Feet16"
            mode == 1 && feetFlag == 1 && fpf != null -> {
                col.lose(
                    AepImportReport.LossCategory.ProjectSettings,
                    "feet + frames with $fpf frames per foot (only 16 and 40 are known; the current time display is kept)"
                )
                null
            }
            mode == null && feetFlag == null && fpf == null -> null
            mode != null && nhed != null && nhed.size > 13 -> {
                // `nhed` bytes 8, 11 and 13; a header too short to hold them is not a setting.
                col.lose(
                    AepImportReport.LossCategory.ProjectSettings,
                    "time display code $mode/$feetFlag (not known; the current time display is kept)"
                )
                null
            }
            else -> null
        }
        val frameStart = when (byte(14)) {
            2 -> 0L
            1 -> 1L
            null -> null
            else -> {
                col.lose(
                    AepImportReport.LossCategory.ProjectSettings,
                    "frame count start code ${byte(14)} (not known; the current start is kept)"
                )
                null
            }
        }
        val workingGamma24 = file.chunk("dwga")?.bytes()
            ?.takeIf { it.size >= 4 }
            ?.let { it.copyOfRange(0, 4).any { b -> b != 0.toByte() } }
        val flag = { id: String ->
            file.chunk(id)?.bytes()?.firstOrNull() == 1.toByte()
        }
        var workingSpace: String? = null
        var outputSpace: String? = null
        val pwcsAt = file.chunks.indexOfFirst { it.`is`("PwCs") }
        if (pwcsAt >= 0) {
            val json = file.chunks.getOrNull(pwcsAt + 1)
                ?.takeIf { it.`is`("Utf8") }?.bytes()
            if (json != null) {
                val trimmed = json.filter { it != 0.toByte() && !it.toInt().toChar().isWhitespace() }.toByteArray()
                if (trimmed.contentEquals("{}".toByteArray(Charsets.UTF_8))) {
                    // No colour management; the default stays.
                } else {
                    val name = AepFootage.jsonString(json, "colorProfileName")
                    if (name != null) {
                        val space = colorSpace(name)
                        if (space != null) {
                            workingSpace = space
                            // After Effects renders in the working space, EffectCraft
                            // converts to `output_space` (sRGB when unset). sRGB stays
                            // unset, which is the same.
                            if (space != "Srgb") outputSpace = space
                        } else {
                            col.lose(
                                AepImportReport.LossCategory.ProjectSettings,
                                "working space '$name' (no EffectCraft counterpart; the current one is kept)"
                            )
                        }
                    } else if (AepFootage.jsonString(json, "colorProfileData") != null) {
                        col.lose(
                            AepImportReport.LossCategory.ProjectSettings,
                            "working space is stored as profile data without a name (not decoded; the current one is kept)"
                        )
                    }
                }
            }
        }
        return Patch(
            bitDepth = bitDepth,
            timeDisplay = timeDisplay,
            frameStart = frameStart,
            workingGamma = workingGamma24?.let { if (it) 2.4 else 2.2 },
            linearize = flag("lnrp"),
            blendLinear = flag("lnrb"),
            workingSpace = workingSpace,
            outputSpace = outputSpace
        )
    }
}
