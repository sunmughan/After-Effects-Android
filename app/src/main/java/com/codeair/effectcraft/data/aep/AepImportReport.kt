package com.codeair.effectcraft.data.aep

/**
 * The import report: what was imported (counts) and everything that was not, per category.
 *
 * Port of `import/report.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). The report is plain data, so it
 * round-trips through JSON: the import dialog shows it and the user decides
 * whether the losses are acceptable. Nothing the importer skips is left out
 * of it.
 */
object AepImportReport {

    /** Most example lines kept per loss category (the count is always exact). */
    const val MAX_DETAILS = 20

    /** Why a piece of the file did not arrive in the project, or arrived changed. */
    enum class LossCategory(val label: String) {
        /** Effects on layers that are not imported (by match name). */
        Effects("effects"),

        /** Mask data that is not imported. */
        Masks("masks"),

        /** Parts of shape layers that are not imported. (The layers are imported.) */
        ShapeLayers("shape layers"),

        /** Parts of text layers that are not imported. (The layers themselves are imported.) */
        TextLayers("text layer features"),

        /** Camera layers that are not imported. */
        Cameras("cameras"),

        /** Light layers of a light type that is not sampled. */
        Lights("lights"),

        /** Layer styles (drop shadow, glow, stroke, ...) with set values. */
        LayerStyles("layer styles"),

        /** Marker data read in another form. */
        Markers("markers"),

        /** Blend mode codes outside the known ones (the layer keeps Normal). */
        BlendModes("blend modes"),

        /** Stored transform properties other than Anchor Point, Position, Scale, Rotation and Opacity
         * (3D rotations, orientation, separated position dimensions). */
        TransformProperties("transform properties"),

        /** Time remapping that is not imported. */
        TimeRemapping("time remapping"),

        /** Keyframe data that was imported in a simplified form (see the detail lines). */
        ApproximatedKeyframes("approximated keyframes"),

        /** Parent links to a layer that was not imported (or that would form a loop). */
        ParentLinks("parent links"),

        /** Track mattes that are not imported. */
        TrackMattes("track mattes"),

        /** Layers that could not be imported for another reason. */
        UnsupportedLayers("unsupported layers"),

        /** Project items of a kind that is not imported. */
        UnsupportedItems("unsupported items"),

        /** Data that could not be read (damaged headers, unreadable keyframes). */
        DamagedData("damaged data"),

        /** Project settings without an EffectCraft counterpart or stored as an unknown code. */
        ProjectSettings("project settings")
    }

    /** One category of things that were not imported. */
    class Loss(val category: LossCategory, var count: Int, val details: MutableList<String>) {
        override fun equals(other: Any?): Boolean =
            other is Loss && category == other.category && count == other.count && details == other.details

        override fun hashCode(): Int {
            var result = category.hashCode()
            result = 31 * result + count
            result = 31 * result + details.hashCode()
            return result
        }
    }

    /** What arrived in the project. */
    class Imported(
        var folders: Int = 0,
        var compositions: Int = 0,
        var solids: Int = 0,
        /** Footage items (files), present or missing. */
        var footage: Int = 0,
        /** All layers. */
        var layers: Int = 0,
        var solidLayers: Int = 0,
        var nullLayers: Int = 0,
        var adjustmentLayers: Int = 0,
        var footageLayers: Int = 0,
        var precompLayers: Int = 0,
        /** Text layers (with their Source Text). */
        var textLayers: Int = 0,
        /** Keyframes on the imported properties. */
        var keyframes: Int = 0,
        /** Expressions (kept as source text with their enable switch). */
        var expressions: Int = 0,
        /** Layer masks. */
        var masks: Int = 0,
        /** Track mattes (the layer's matte source and mode). */
        var trackMattes: Int = 0,
        /** Layer and composition markers. */
        var markers: Int = 0,
        /** Layers with Time Remapping. */
        var timeRemaps: Int = 0,
        /** Shape layers. */
        var shapeLayers: Int = 0,
        /** Shape contents items (groups, paths, paints, operators). */
        var shapeItems: Int = 0,
        /** Camera layers. */
        var cameras: Int = 0,
        /** Light layers (spot and point). */
        var lights: Int = 0,
        /** Text animators (with their range selectors). */
        var textAnimators: Int = 0,
        /** Effect instances (with their parameters). */
        var effects: Int = 0,
        /** Layer styles (Drop Shadow, Stroke, ...), one per style on a layer. */
        var layerStyles: Int = 0
    )

    /** The After Effects versions the importer is tested with (the major of the version a file is
     * written for): files written for 24.x, 25.x and 26.x. */
    val TESTED_MAJORS: IntRange = 24..26

    /** The text of a version the file does not state. */
    const val UNKNOWN = "unknown"

    /** The After Effects versions of a file, decoded from the container (never from the XMP packet,
     * which names the application of the first save). */
    class AeVersionInfo(
        /** The version the file is written for (`head`). `unknown` when the file does not say. */
        val fileVersion: String,
        /** The version of the After Effects that wrote the file (`svap`). `unknown` when the file
         * does not say. */
        val savedBy: String,
        /** Whether the importer is tested with files written for this version ([TESTED_MAJORS]).
         * When not, what is read is still imported, and a line in [ImportReport.limitations]
         * says the version is not tested. */
        val tested: Boolean
    ) {
        companion object {
            /** Reads the versions of a parsed file. */
            fun of(file: AepContainer.AepFile): AeVersionInfo {
                val fileVersion = file.fileVersion()
                return AeVersionInfo(
                    fileVersion = fileVersion?.text() ?: UNKNOWN,
                    savedBy = file.savedByVersion()?.text() ?: UNKNOWN,
                    tested = fileVersion != null && fileVersion.major in TESTED_MAJORS
                )
            }
        }

        /** The limitation line for a version that is not tested; null for a tested one. */
        fun limitation(): String? {
            if (tested) return null
            return "The file is written for After Effects $fileVersion (saved by $savedBy); " +
                "the importer is tested with files written for After Effects " +
                "${TESTED_MAJORS.first} to ${TESTED_MAJORS.last} only. What it reads is " +
                "imported, but this version is not tested."
        }

        override fun equals(other: Any?): Boolean =
            other is AeVersionInfo && fileVersion == other.fileVersion &&
                savedBy == other.savedBy && tested == other.tested

        override fun hashCode(): Int {
            var result = fileVersion.hashCode()
            result = 31 * result + savedBy.hashCode()
            result = 31 * result + tested.hashCode()
            return result
        }
    }

    /** The result of reading an `.aep`: counts of what was imported, the losses per category, the
     * footage files that could not be found and the standing limitations of the reader. */
    class ImportReport(
        val imported: Imported,
        /** Only categories that occurred, in category order. */
        val losses: List<Loss>,
        /** Footage files whose path does not exist here; the items are kept as missing-footage
         * placeholders (relink them after the files are found). */
        val missingFootage: List<String>,
        /** Fonts (PostScript names) that the text uses and that are not installed here. */
        val missingFonts: List<String>,
        /** What the file reader cannot see at all, so it can be neither imported nor counted. */
        val limitations: List<String>,
        /** The After Effects version the file is written for and the one that wrote it. */
        val aeVersion: AeVersionInfo
    ) {
        /** The sum of all loss counts. */
        fun totalLosses(): Int = losses.sumOf { it.count }

        /** True when nothing was lost. */
        fun isLossless(): Boolean = losses.isEmpty()

        /** The loss count of a category. */
        fun count(category: LossCategory): Int =
            losses.firstOrNull { it.category == category }?.count ?: 0

        /** `effects: 3, masks: 1` (empty when lossless). */
        fun summary(): String =
            losses.joinToString(", ") { "${it.category.label}: ${it.count}" }
    }

    /** Collects losses while importing. */
    class Collector {
        val imported = Imported()
        private val losses = sortedMapOf<LossCategory, Loss>()
        val missingFootage = ArrayList<String>()
        val missingFonts = ArrayList<String>()

        /** Records one loss. */
        fun lose(category: LossCategory, detail: String) {
            val loss = losses.getOrPut(category) { Loss(category, 0, ArrayList()) }
            loss.count += 1
            if (loss.details.size < MAX_DETAILS) loss.details.add(detail)
        }

        fun finish(aeVersion: AeVersionInfo): ImportReport {
            val limitations = ArrayList(LIMITATIONS)
            aeVersion.limitation()?.let { limitations.add(it) }
            return ImportReport(
                imported = imported,
                losses = losses.values.toList(),
                missingFootage = missingFootage.toList(),
                missingFonts = missingFonts.toList(),
                limitations = limitations,
                aeVersion = aeVersion
            )
        }
    }

    /** Parts of a project that the container reader does not decode. They are never imported and,
     * as the reader cannot see them, never counted either. */
    val LIMITATIONS: List<String> = listOf(
        "Frame blending, 3D material options, audio levels, guides are not decoded and not imported.",
        "Mask colour, lock, feather falloff, motion blur and RotoBezier are not decoded: imported masks get the default colour, unlocked, Smooth falloff, motion blur Same As Layer and no RotoBezier.",
        "Text: per-character 3D, text on a path and several style slots of unknown meaning are not decoded (stored values that differ from the default are reported as text layer features).",
        "Footage that is not a solid is linked by its stored path with its stored frame rate and duration; the importer never opens the files (use Reload Footage after the files are found)."
    )
}
