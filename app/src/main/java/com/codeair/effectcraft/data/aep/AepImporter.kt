package com.codeair.effectcraft.data.aep

import com.codeair.effectcraft.data.AeComp
import com.codeair.effectcraft.data.AeInterchangeConverter
import com.codeair.effectcraft.data.AeKeyframe
import com.codeair.effectcraft.data.AeLayer
import com.codeair.effectcraft.data.AeLayerType
import com.codeair.effectcraft.data.AeProject
import com.codeair.effectcraft.data.AeValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Imports an After Effects project (`.aep` binary or `.aepx` XML) into a canonical
 * EffectCraft document (schema 2).
 *
 * The first composition only is imported, through the same [AeInterchangeConverter] the
 * ExtendScript export uses, so the canonical JSON is built by the proven path. The fields
 * the interchange model does not carry (stretch, blend mode, switches, work area, shutter,
 * renderer, display start, pixel aspect, full keyframes with interpolation/ease/spatial
 * tangents/roving/label, expressions) are patched into the converted JSON afterwards.
 *
 * Nothing is silently dropped: every skipped piece is recorded in the [AepImportReport]
 * under its loss category. [analyze] is the dry run the import dialog shows; [import]
 * refuses when there are losses and the user has not allowed them.
 *
 * Honest v1 coverage: first composition only; footage/solid/null layers with timing,
 * switches, blend mode, stretch and parent links; transform static values and keyframes
 * for Anchor Point, Position, Scale, Rotation and Opacity; effects with parameters and
 * keyframes (the 63 mapped effects, from the native effect registry); masks with path,
 * mode, feather/opacity/expansion and keyframes; layer and composition markers; time
 * remapping; orientation keyframes; footage linked by its stored path (a folder
 * resolver maps the stored path onto the folder the project lives in; files it
 * cannot resolve are listed as missing, never a refusal); expressions kept as
 * source text; shape contents (groups, rect, ellipse, polystar, bezier paths, fill,
 * stroke, gradient fill and stroke, trim, round corners, merge, offset and repeater,
 * with keyframes and expressions); every composition in the project, and precomp
 * layers pointing at them.
 * NOT imported in v1 (all reported as losses):
 * light layers, layer styles, 3D rotations,
 * project settings, folders. Text imports with
 * its first-run style, runs, paragraphs and box; text animators, Source Text
 * keyframes and expressions are losses. Cameras import since the real-file
 * fix: source, transform (point of interest, position with depth, rotations),
 * camera options (zoom, focus distance, aperture, depth of field) and the
 * two-node auto orientation; a camera's position z is patched as a Vec3, as
 * is any 3D layer's stored depth.
 * There is no `.aep` export in this build.
 *
 * Ports the mapping of `import/build.rs`, `import/props.rs`, `import/time.rs` and
 * `container/values.rs` from the official EffectCraft project (storytold/effectcraft,
 * MIT OR Apache-2.0), cut to what this app's renderer can do.
 */
object AepImporter {

    /** What [analyze] returned: the converted project and its loss report. */
    data class Analysis(
        val project: AeProject,
        val report: AepImportReport.ImportReport,
        val lossCategories: Set<AepImportReport.LossCategory>
    )

    /** What [import] returned. */
    sealed class ImportResult {
        /** The file has losses the user has not allowed: nothing was imported. */
        class Refused(val report: AepImportReport.ImportReport) : ImportResult()

        /** The canonical document and its loss report. */
        class Imported(val canonicalJson: String, val report: AepImportReport.ImportReport) : ImportResult()
    }

    /**
     * Dry run: parses the file and builds the project and its loss report without
     * producing a document. Used by the import dialog before the user allows losses.
     * @throws IllegalArgumentException with a user-facing reason for a file that cannot
     * be read at all (not a project, no composition, unreadable headers).
     */
    fun analyze(
        bytes: ByteArray,
        provider: AepEffectImport.EffectSpecProvider = AepEffectImport.NativeEffectSpecs(),
        mediaResolver: (String) -> String? = AepFootageResolver::default
    ): Analysis {
        val built = build(AepxReader.parseAny(bytes), provider, mediaResolver)
        return Analysis(
            built.project,
            built.report,
            built.report.losses.map { it.category }.toSet()
        )
    }

    /**
     * Imports the file. When [allowLosses] is false and the report has any loss, returns
     * [ImportResult.Refused] with the report instead of a document.
     * @throws IllegalArgumentException with a user-facing reason for a file that cannot
     * be read at all.
     */
    fun import(
        bytes: ByteArray,
        allowLosses: Boolean,
        provider: AepEffectImport.EffectSpecProvider = AepEffectImport.NativeEffectSpecs(),
        mediaResolver: (String) -> String? = AepFootageResolver::default
    ): ImportResult {
        val built = build(AepxReader.parseAny(bytes), provider, mediaResolver)
        if (!allowLosses && built.report.losses.isNotEmpty()) {
            return ImportResult.Refused(built.report)
        }
        val json = AeInterchangeConverter.toCanonicalJson(built.project, mediaResolver)
        return ImportResult.Imported(patchCanonical(json, built), built.report)
    }

    // ------------------------------------------------------------------ internals

    /** No sensible coordinate, angle or percentage comes near this; larger numbers (and NaN
     * or infinity) mean damaged data. */
    private const val LIMIT = 1e9

    private fun ticks(seconds: Double): Long =
        (seconds * AeInterchangeConverter.TICKS_PER_SECOND).roundToLong()

    private fun secondsToTicks(seconds: Double?): Long? =
        seconds?.takeIf { it.isFinite() }?.let { ticks(it) }

    private data class RichEase(val speed: Double, val influence: Double)

    private data class RichKeyframe(
        val timeTicks: Long,
        /** Converted value: 3 components for Vec3 props, 1 for Scalar props. */
        val value: List<Double>,
        val isVec3: Boolean,
        val inInterp: String,
        val outInterp: String,
        val inEase: List<RichEase>,
        val outEase: List<RichEase>,
        val spatialIn: DoubleArray?,
        val spatialOut: DoubleArray?,
        val spatialAuto: Boolean?,
        val roving: Boolean,
        val label: Int
    )

    private data class Switches(
        val video: Boolean,
        val audio: Boolean,
        val solo: Boolean,
        val locked: Boolean,
        val shy: Boolean,
        val collapse: Boolean,
        val quality: String,
        val effects: Boolean,
        val motionBlur: Boolean,
        val adjustment: Boolean,
        val threeD: Boolean,
        val guide: Boolean
    )

    private data class LayerPatch(
        val stretch: Double,
        val blendMode: String,
        val switches: Switches,
        /** Rich keyframes by canonical property id (`anchor`, `position`, `scale`,
         * `rotation`, `opacity`, `rotationX`, `rotationY`). */
        val richKeys: Map<String, List<RichKeyframe>>,
        /** Expressions by canonical property id: text to enabled. */
        val expressions: Map<String, Pair<String, Boolean>>,
        /** Static values by canonical property id, for properties the converter
         * writes as a default (`rotationX`, `rotationY`, `orientation`). */
        val staticValues: Map<String, List<Double>> = emptyMap(),
        /** The layer's id in the file, for resolving layer references in the patch step. */
        val aepLayerId: Long = 0,
        /** Imported effect groups, in stack order (uids in patch space; remapped with
         * the converter's `next_id` in the patch step). */
        val effectGroups: List<JSONObject> = emptyList(),
        /** Layer parameters that name another layer, resolved in the patch step once
         * every layer's project id exists. */
        val pendingLayerRefs: List<AepEffectImport.PendingLayerRef> = emptyList(),
        /** Imported mask groups, in stack order (uids in patch space, as effect groups). */
        val maskGroups: List<JSONObject> = emptyList(),
        /** Imported shape groups, in file order (uids in patch space, as effect groups). */
        val shapeGroups: List<JSONObject> = emptyList(),
        /** Layer markers, as canonical marker JSON, in time order. */
        val markers: JSONArray = JSONArray(),
        /** The Time Remapping property, inserted first in the layer's properties. */
        val timeRemap: JSONObject? = null,
        /** Imported camera options by canonical prop id (cameras only). */
        val cameraOptions: Map<String, AepCameraImport.CameraOption> = emptyMap(),
        /** The auto orientation of a camera layer: "Off" or "TowardsPointOfInterest". */
        val autoOrient: String = "Off",
        /** A track matte waiting for the patch step: the canonical kind and the
         * matte layer's id in the file. */
        val trackMatte: PendingTrackMatte? = null,
        /** Text animator groups (uids in patch space), appended to text/animators. */
        val animatorGroups: List<JSONObject> = emptyList()
    )

    /** A track matte resolved in the patch step, once every layer's project id exists. */
    private data class PendingTrackMatte(val kind: String, val targetAepId: Long, val what: String)

    private data class CompPatch(
        val pixelAspect: Double,
        val displayStartTicks: Long,
        val workAreaStartTicks: Long,
        val workAreaEndTicks: Long,
        val shutterAngle: Double,
        val shutterPhase: Double,
        val renderer: String,
        /** Composition markers, as canonical marker JSON, in time order. */
        val markers: JSONArray = JSONArray()
    )

    private data class Built(
        val project: AeProject,
        val report: AepImportReport.ImportReport,
        val compPatches: List<CompPatch>,
        val layerPatches: List<List<LayerPatch>>
    )

    private enum class PropKind { ANCHOR, POSITION, SCALE, ROTATION, OPACITY }

    private val TRANSFORM_SPECS: List<Triple<String, String, PropKind>> = listOf(
        Triple("ADBE Anchor Point", "anchor", PropKind.ANCHOR),
        Triple("ADBE Position", "position", PropKind.POSITION),
        Triple("ADBE Scale", "scale", PropKind.SCALE),
        Triple("ADBE Rotate Z", "rotation", PropKind.ROTATION),
        Triple("ADBE Opacity", "opacity", PropKind.OPACITY)
    )

    /** Match names of the transform group that are always present and carry nothing in
     * their default state (props.rs IGNORED). */
    private val IGNORED_TRANSFORM: Set<String> = setOf(
        "ADBE Position_0", "ADBE Position_1", "ADBE Position_2",
        "ADBE Envir Appear in Reflect"
    )

    /** Layer property groups the official importer does not read (no loss in build.rs):
     * always present, default/empty in every layer. */
    private val IGNORED_LAYER_GROUPS: Set<String> = setOf(
        "ADBE Plane Options Group",
        "ADBE Extrsn Options Group",
        "ADBE Data Group",
        "ADBE Layer Overrides",
        "ADBE Layer Sets",
        "ADBE Source Options Group"
    )

    /** The blend mode codes the reader knows (`ldta` byte 99): the six sampled modes plus
     * the codes the official importer established by name (906) and by rendering (907).
     * Codes 0, 1, 3, 21, 22 and above 34 are not imported. */
    private fun blendName(code: Int): String? = when (code) {
        2 -> "Normal"
        4 -> "Add"
        5 -> "Multiply"
        6 -> "Screen"
        7 -> "Overlay"
        26 -> "Difference"
        8 -> "SoftLight"
        9 -> "HardLight"
        10 -> "Darken"
        11 -> "Lighten"
        12 -> "ClassicDifference"
        13 -> "Hue"
        14 -> "Saturation"
        15 -> "Color"
        16 -> "Luminosity"
        17 -> "StencilAlpha"
        18 -> "StencilLuma"
        19 -> "SilhouetteAlpha"
        20 -> "SilhouetteLuma"
        23 -> "ClassicColorDodge"
        24 -> "ClassicColorBurn"
        25 -> "Exclusion"
        27 -> "ColorDodge"
        28 -> "ColorBurn"
        29 -> "LinearDodge"
        30 -> "LinearBurn"
        31 -> "LinearLight"
        32 -> "VividLight"
        33 -> "PinLight"
        34 -> "HardMix"
        else -> null
    }

    private fun build(
        file: AepContainer.AepFile,
        provider: AepEffectImport.EffectSpecProvider,
        mediaResolver: (String) -> String?
    ): Built {
        val versions = AepImportReport.AeVersionInfo.of(file)
        val col = AepImportReport.Collector()
        val project = AepTypedView.Project.of(file)
            ?: throw IllegalArgumentException(
                "This .aep file has no project items; it may be damaged."
            )
        val allItems = project.allItems()
        val comps = allItems.filter { it.kind() == AepTypedView.ItemKind.Composition }
        if (comps.isEmpty()) {
            throw IllegalArgumentException("This .aep file has no composition to import.")
        }
        for (item in allItems) {
            when (item.kind()) {
                AepTypedView.ItemKind.Folder -> col.lose(
                    AepImportReport.LossCategory.UnsupportedItems,
                    "folder '${item.name() ?: "Folder"}' is not imported in this build " +
                        "(folders are not in this project's model); its contents are still imported"
                )
                is AepTypedView.ItemKind.Other -> col.lose(
                    AepImportReport.LossCategory.UnsupportedItems,
                    "project item '${item.name() ?: "Item"}' is not imported in this build"
                )
                else -> Unit
            }
        }
        // Project settings are not decoded in this build; the converter defaults are used.
        col.lose(
            AepImportReport.LossCategory.ProjectSettings,
            "project settings were not read in this build (working space, colour depth, " +
                "time display); the defaults are used"
        )

        // Which compositions are readable at all; the rest are skipped with a loss, so
        // one damaged composition does not take the whole project down with it.
        val importableComps = ArrayList<AepTypedView.Item>()
        for (comp in comps) {
            val view = comp.comp()
            if (view == null || view.header() == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "composition '${comp.name() ?: "Composition"}': the composition data is " +
                        "unreadable, the composition is skipped"
                )
                continue
            }
            importableComps.add(comp)
        }
        if (importableComps.isEmpty()) {
            throw IllegalArgumentException("This .aep file has no composition to import.")
        }
        // Index of each imported composition in the project's comp list, by item id;
        // precomp layers use it to point at the composition they reference.
        val compIndexById = importableComps.mapIndexedNotNull { index, comp ->
            comp.id()?.let { it to index }
        }.toMap()

        // Effect group skeletons come from the native registry; patch uids start at 1 and
        // are remapped with the converter's `next_id` in the patch step. The counter is
        // shared across compositions, so patch uids stay unique project-wide.
        val defs = AepEffects.EffectDefinitions.of(project)
        var patchUid = 1L
        val allocBlock: (Int) -> Long = { n ->
            val base = patchUid
            patchUid += n
            base
        }

        val aeComps = ArrayList<AeComp>()
        val compPatches = ArrayList<CompPatch>()
        val layerPatches = ArrayList<List<LayerPatch>>()
        for (compItem in importableComps) {
            val built = buildComp(
                compItem, project, compIndexById, defs, provider, allocBlock, mediaResolver, col
            ) ?: continue
            aeComps.add(built.first)
            compPatches.add(built.second)
            layerPatches.add(built.third)
        }
        col.imported.compositions = aeComps.size
        col.imported.layers = aeComps.sumOf { it.layers.size }
        val aeProject = AeProject("Imported Project", aeComps, warnings = emptyList())
        return Built(aeProject, col.finish(versions), compPatches, layerPatches)
    }

    /**
     * Builds one composition: its layers, its patch and its markers. Returns null (with the
     * loss recorded) when the composition's frame rate or duration is unreadable.
     */
    private fun buildComp(
        compItem: AepTypedView.Item,
        project: AepTypedView.Project,
        compIndexById: Map<Long, Int>,
        defs: AepEffects.EffectDefinitions,
        provider: AepEffectImport.EffectSpecProvider,
        allocBlock: (Int) -> Long,
        mediaResolver: (String) -> String?,
        col: AepImportReport.Collector
    ): Triple<AeComp, CompPatch, List<LayerPatch>>? {
        val compView = compItem.comp()
            ?: throw IllegalArgumentException(
                "composition \"${compItem.name() ?: "Composition"}\": the composition data is unreadable"
            )
        val compName = compView.name() ?: "Composition"
        val header = compView.header()
            ?: throw IllegalArgumentException(
                "composition \"$compName\": the composition header is unreadable"
            )
        val frameRate = header.frameRate() ?: run {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "composition \"$compName\": the frame rate is unreadable, the composition is skipped"
            )
            return null
        }
        val durationSeconds = header.durationSeconds() ?: run {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "composition \"$compName\": the duration is unreadable, the composition is skipped"
            )
            return null
        }
        val background = header.backgroundRgb().toList()
        val compWidth = header.width
        val compHeight = header.height

        // First pass over the layers: decide what imports, so parent links can point only
        // at imported layers.
        val layerRows = compView.layers().mapNotNull { layer ->
            val layerHeader = layer.header()
            if (layerHeader == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "layer '${layer.name() ?: "Layer"}': the layer header is unreadable, " +
                        "the layer is skipped"
                )
                return@mapNotNull null
            }
            val name = layer.name() ?: project.layerDisplayName(layer) ?: "Layer"
            LayerRow(layer, layerHeader, name)
        }
        val importable = layerRows.filter { row -> layerImports(row, project, col) }
        val idToName = importable.associate { it.header.id to it.name }
        val nameCounts = importable.groupingBy { it.name }.eachCount()
        // Parent links, with loop detection: follow each chain; a cycle breaks the link.
        val parentNameById = HashMap<Long, String?>()
        for (row in importable) {
            val parentId = row.header.parentId
            if (parentId == null) {
                parentNameById[row.header.id] = null
                continue
            }
            if (parentId == row.header.id) {
                col.lose(
                    AepImportReport.LossCategory.ParentLinks,
                    "layer '${row.name}': the layer is its own parent, the link is dropped"
                )
                parentNameById[row.header.id] = null
                continue
            }
            val parentName = idToName[parentId]
            if (parentName == null) {
                col.lose(
                    AepImportReport.LossCategory.ParentLinks,
                    "layer '${row.name}': the parent layer was not imported, the link is dropped"
                )
                parentNameById[row.header.id] = null
                continue
            }
            if (nameCounts.getValue(parentName) > 1) {
                col.lose(
                    AepImportReport.LossCategory.ParentLinks,
                    "layer '${row.name}': the parent name '$parentName' is ambiguous, " +
                        "the link is dropped"
                )
                parentNameById[row.header.id] = null
                continue
            }
            // Walk the chain from the parent: a cycle drops the link.
            val seen = HashSet<Long>()
            var cursor: Long? = parentId
            var cyclic = false
            while (cursor != null) {
                if (!seen.add(cursor)) {
                    cyclic = true
                    break
                }
                val next = importable.firstOrNull { it.header.id == cursor }?.header?.parentId
                cursor = next?.takeIf { idToName.containsKey(it) }
            }
            if (cyclic) {
                col.lose(
                    AepImportReport.LossCategory.ParentLinks,
                    "layer '${row.name}': the parent link forms a loop, the link is dropped"
                )
                parentNameById[row.header.id] = null
            } else {
                parentNameById[row.header.id] = parentName
            }
        }

        val aeLayers = ArrayList<AeLayer>()
        val layerPatches = ArrayList<LayerPatch>()
        // The layer above in file order, for the older track matte form: the
        // previous row, whether or not it imports.
        val aboveIdById = HashMap<Long, Long?>()
        for ((index, row) in layerRows.withIndex()) {
            aboveIdById[row.header.id] = layerRows.getOrNull(index - 1)?.header?.id
        }
        for ((layerIndex, row) in importable.withIndex()) {
            val built = buildLayer(
                row, project, compView, header, durationSeconds, parentNameById[row.header.id],
                compName, layerIndex, aboveIdById[row.header.id], compIndexById, defs, provider, allocBlock, mediaResolver, col
            )
            if (built == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "layer '${row.name}': the layer could not be built and is skipped"
                )
                continue
            }
            aeLayers.add(built.first)
            layerPatches.add(built.second)
        }
        // Layer references whose target was not imported stay unset (the patch step leaves
        // them as such); the loss is recorded here, where the imported set is known.
        val importedIds = layerPatches.map { it.aepLayerId }.toSet()
        for (patch in layerPatches) {
            for (ref in patch.pendingLayerRefs) {
                if (ref.target !in importedIds) {
                    col.lose(
                        AepImportReport.LossCategory.Effects,
                        "${ref.what} names a layer that is not imported (id ${ref.target}), left unset"
                    )
                }
            }
        }
        // Track mattes whose matte layer was not imported stay unset too; a
        // resolved matte counts as imported here, where the set is known.
        for (patch in layerPatches) {
            val matte = patch.trackMatte ?: continue
            if (matte.targetAepId in importedIds) {
                col.imported.trackMattes++
            } else {
                col.lose(
                    AepImportReport.LossCategory.TrackMattes,
                    "${matte.what} has a layer that was not imported as its track matte"
                )
            }
        }
        // (The composition and layer totals are set in build(), after every comp.)

        val pixelAspect = header.pixelAspectRatio()?.takeIf { it.isFinite() && it > 0.0 }
            ?: run {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "composition \"$compName\": the pixel aspect ratio is unreadable, 1.0 is used"
                )
                1.0
            }
        val rendererName = compView.renderer()?.matchName
        val renderer = when (rendererName) {
            null -> "Classic3D"
            "ADBE Escher" -> "Classic3D"
            "ADBE Calder" -> "Advanced3D"
            else -> {
                col.lose(
                    AepImportReport.LossCategory.ProjectSettings,
                    "composition \"$compName\": the renderer '$rendererName' is not known, " +
                        "Classic 3D is used"
                )
                "Classic3D"
            }
        }
        val compPatch = CompPatch(
            pixelAspect = pixelAspect,
            displayStartTicks = secondsToTicks(header.displayStartSeconds()) ?: 0L,
            workAreaStartTicks = secondsToTicks(header.workAreaStartSeconds()) ?: 0L,
            workAreaEndTicks = secondsToTicks(header.workAreaEndSeconds()) ?: ticks(durationSeconds),
            shutterAngle = header.shutterAngle.toDouble(),
            shutterPhase = header.shutterPhase?.toDouble() ?: -90.0,
            renderer = renderer,
            markers = compView.item.childList("SecL")?.let { secL ->
                AepMarkers.importMarkers(
                    AepMarkers.markersIn(secL), secL, "composition \"$compName\"",
                    header.timeBase.timescale, col
                )
            } ?: JSONArray()
        )

        val aeComp = AeComp(
            name = compName,
            width = compWidth,
            height = compHeight,
            frameRate = frameRate,
            durationSeconds = durationSeconds,
            background = background,
            layers = aeLayers
        )
        return Triple(aeComp, compPatch, layerPatches)
    }

    private data class LayerRow(
        val layer: AepTypedView.Layer,
        val header: AepHeader.LayerHeader,
        val name: String
    )

    /** Whether the layer imports at all (type + source), recording the loss when it does not. */
    private fun layerImports(
        row: LayerRow,
        project: AepTypedView.Project,
        col: AepImportReport.Collector
    ): Boolean {
        val name = row.name
        return when (row.header.layerType) {
            AepHeader.LayerType.Light -> {
                col.lose(
                    AepImportReport.LossCategory.UnsupportedLayers,
                    "light layer '$name' is not imported in this build"
                )
                false
            }
            AepHeader.LayerType.Camera -> true
            AepHeader.LayerType.Text, AepHeader.LayerType.Shape -> true
            AepHeader.LayerType.Av -> {
                if (row.header.isNullObject()) return true
                val source = project.layerSource(row.layer)
                if (source == null) {
                    col.lose(
                        AepImportReport.LossCategory.UnsupportedLayers,
                        "layer '$name' has no source item and is skipped"
                    )
                    return false
                }
                when (source.kind()) {
                    // A precomp layer imports as a COMP layer pointing at the
                    // referenced composition (converted with every other comp).
                    AepTypedView.ItemKind.Composition -> true
                    AepTypedView.ItemKind.Footage -> {
                        val footage = source.footage()
                        if (footage == null) {
                            col.lose(
                                AepImportReport.LossCategory.DamagedData,
                                "layer '$name': the footage data is unreadable, the layer is skipped"
                            )
                            false
                        } else if (!footage.isSolid && footage.path == null) {
                            col.lose(
                                AepImportReport.LossCategory.UnsupportedLayers,
                                "footage layer '$name' has no stored file path and is skipped"
                            )
                            false
                        } else {
                            true
                        }
                    }
                    else -> {
                        col.lose(
                            AepImportReport.LossCategory.UnsupportedLayers,
                            "layer '$name' has an unsupported source and is skipped"
                        )
                        false
                    }
                }
            }
            is AepHeader.LayerType.Other -> {
                col.lose(
                    AepImportReport.LossCategory.UnsupportedLayers,
                    "layer '$name' has an unknown layer type and is skipped"
                )
                false
            }
        }
    }

    /** Builds one importable layer: the [AeLayer] for the converter and its patch. */
    private fun buildLayer(
        row: LayerRow,
        project: AepTypedView.Project,
        compView: AepTypedView.Comp,
        compHeader: AepHeader.CompHeader,
        compDuration: Double,
        parentName: String?,
        compName: String,
        layerIndex: Int,
        /** The id of the layer above in file order, for the older track matte form. */
        aboveId: Long?,
        /** The index of each imported composition in the project's comp list, by item id. */
        compIndexById: Map<Long, Int>,
        defs: AepEffects.EffectDefinitions,
        provider: AepEffectImport.EffectSpecProvider,
        allocBlock: (Int) -> Long,
        mediaResolver: (String) -> String?,
        col: AepImportReport.Collector
    ): Pair<AeLayer, LayerPatch>? {
        val layer = row.layer
        val header = row.header
        val name = row.name
        val compWidth = compHeader.width
        val compHeight = compHeader.height
        val rootProps = layer.properties()
        val transformGroup = (rootProps?.get("ADBE Transform Group") as? AepTypedView.PropNode.Group)?.group
        val sourceSize = project.layerSourceSize(layer)
        val isAv = header.layerType == AepHeader.LayerType.Av && !header.isNullObject()

        // The layer type and source.
        var type = AeLayerType.NULL
        var file: String? = null
        var kind = "video"
        var hasAudio = false
        var frameRate: Double? = null
        var width = compWidth
        var height = compHeight
        var durationSeconds = compDuration
        var color = listOf(1.0, 1.0, 1.0, 1.0)
        var text = ""
        var font = "Inter"
        var fontSize = 72.0
        var textDoc: JSONObject? = null
        var compIndex: Int? = null
        when (header.layerType) {
            AepHeader.LayerType.Text -> {
                type = AeLayerType.TEXT
                col.imported.textLayers++
                val doc = layer.textDocument()
                if (doc == null) {
                    col.lose(
                        AepImportReport.LossCategory.DamagedData,
                        "text layer '$name': the text document is unreadable, an empty text layer is imported"
                    )
                } else {
                    val what = "text layer '$name'"
                    textDoc = AepTextImport.textDoc(doc, what, col)
                    text = doc.text
                    font = doc.font ?: "Inter"
                    fontSize = doc.fontSize
                    // Straight RGBA 0..1 of the first run's fill.
                    val fill = doc.fillArgb
                    color = listOf(
                        fill[1].coerceIn(0.0, 1.0),
                        fill[2].coerceIn(0.0, 1.0),
                        fill[3].coerceIn(0.0, 1.0),
                        fill[0].coerceIn(0.0, 1.0)
                    )
                }
                AepTextImport.reportTextExtras(layer, "text layer '$name'", col)
            }
            AepHeader.LayerType.Shape -> {
                type = AeLayerType.SHAPE
                col.imported.shapeLayers++
            }
            AepHeader.LayerType.Camera -> {
                // A camera's size is the composition size (import/build.rs);
                // its options and auto orientation are imported below.
                type = AeLayerType.CAMERA
                col.imported.cameras++
            }
            AepHeader.LayerType.Av -> {
                if (header.isNullObject()) {
                    type = AeLayerType.NULL
                    col.imported.nullLayers++
                } else {
                    val source = project.layerSource(layer) ?: return null
                    if (source.kind() == AepTypedView.ItemKind.Composition) {
                        val targetIndex = source.id()?.let { compIndexById[it] }
                        if (targetIndex == null) {
                            col.lose(
                                AepImportReport.LossCategory.DamagedData,
                                "layer '$name': the precomp it points at is not imported, " +
                                    "the layer is skipped"
                            )
                            return null
                        }
                        type = AeLayerType.COMP
                        compIndex = targetIndex
                        col.imported.precompLayers++
                        sourceSize?.let {
                            width = it.first.toInt().coerceAtLeast(1)
                            height = it.second.toInt().coerceAtLeast(1)
                        }
                    } else {
                        val footage = source.footage() ?: return null
                        width = footage.width.toInt().coerceAtLeast(1)
                        height = footage.height.toInt().coerceAtLeast(1)
                        if (footage.isSolid) {
                            type = AeLayerType.SOLID
                            col.imported.solids++
                            col.imported.solidLayers++
                            val rgb = footage.solidRgb()
                            color = if (rgb != null) {
                                listOf(rgb[0].toDouble(), rgb[1].toDouble(), rgb[2].toDouble())
                            } else {
                                listOf(1.0, 1.0, 1.0)
                            }
                        } else {
                            type = AeLayerType.FOOTAGE
                            col.imported.footage++
                            col.imported.footageLayers++
                            val storedPath = footage.path!!.replace('\\', '/')
                            file = storedPath
                            if (mediaResolver(storedPath) == null) {
                                col.missingFootage.add(storedPath)
                            }
                            hasAudio = footage.audioSampleRate != null
                            kind = footageKind(storedPath, hasAudio, footage.width)
                            frameRate = footage.nativeFrameRate.takeIf { it > 0 }?.toDouble()
                            durationSeconds = footage.duration?.toDoubleOrNull()
                                ?.takeIf { it > 0.0 && it.isFinite() } ?: compDuration
                        }
                    }
                }
            }
            else -> return null
        }

        // Transform: static values (or the first key) with the official conversions.
        val anchorSize: Pair<Double, Double>? = if (isAv) {
            sourceSize?.let { it.first.toDouble() to it.second.toDouble() }
        } else {
            null
        }
        val anchorDefault = if (anchorSize != null) {
            listOf(anchorSize.first / 2.0, anchorSize.second / 2.0, 0.0)
        } else {
            listOf(0.0, 0.0, 0.0)
        }
        val positionDefault = listOf(compWidth / 2.0, compHeight / 2.0, 0.0)
        val values = HashMap<String, List<Double>>()
        val richKeys = HashMap<String, List<RichKeyframe>>()
        val expressions = HashMap<String, Pair<String, Boolean>>()
        val timescale = compHeader.timeBase.timescale
        for ((matchName, propId, propKind) in TRANSFORM_SPECS) {
            // A camera's anchor point is its point of interest (import/camera.rs).
            val id = if (type == AeLayerType.CAMERA && propId == "anchor") "poi" else propId
            val prop = (transformGroup?.get(matchName) as? AepTypedView.PropNode.Property)?.property
            val default = when (propKind) {
                PropKind.ANCHOR -> anchorDefault
                PropKind.POSITION -> positionDefault
                PropKind.SCALE -> listOf(100.0, 100.0, 100.0)
                PropKind.ROTATION -> listOf(0.0)
                PropKind.OPACITY -> listOf(100.0)
            }
            if (prop == null) {
                values[id] = default
                continue
            }
            val read = readTransformValue(prop, default) { raw ->
                convertValue(propKind, raw, anchorSize)
            }
            if (read.unreadable) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "layer '$name': $matchName is unreadable, the default is used"
                )
            }
            values[id] = read.value
            if (prop.isAnimated()) {
                val keys = importKeys(prop, propKind, name, matchName, timescale, anchorSize, col)
                if (keys.isNotEmpty()) richKeys[id] = keys
            }
            val enabled = prop.expressionEnabled()
            val text = prop.expression()
            if (enabled != null && text != null) {
                expressions[id] = text to enabled
                col.imported.expressions++
            }
        }

        // 3D rotations (X/Y): imported like rotation — static value, keys, expression
        // (props.rs ROTATIONS).
        val staticValues = HashMap<String, List<Double>>()
        // The converter writes position, anchor and scale as 2D (z 0, or 100 for
        // scale): a stored z that differs is patched as a Vec3, so 3D layers keep
        // their depth. 2D layers and the converter defaults are untouched.
        for ((propId, value) in values) {
            if (value.size != 3) continue
            val defaultZ = if (propId == "scale") 100.0 else 0.0
            if (value[2] != defaultZ) staticValues[propId] = value
        }
        for ((matchName, propId) in listOf("ADBE Rotate X" to "rotationX", "ADBE Rotate Y" to "rotationY")) {
            val prop = (transformGroup?.get(matchName) as? AepTypedView.PropNode.Property)?.property
                ?: continue
            val raw = if (prop.isAnimated()) prop.firstKeyValue() else prop.staticValue()
            val v = raw?.firstOrNull()
            if (v == null || !v.isFinite() || abs(v) > LIMIT) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "layer '$name': $matchName is unreadable, the default is used"
                )
            } else {
                staticValues[propId] = listOf(v)
            }
            if (prop.isAnimated()) {
                val keys = importKeys(prop, PropKind.ROTATION, name, matchName, timescale, anchorSize, col)
                if (keys.isNotEmpty()) richKeys[propId] = keys
            }
            val enabled = prop.expressionEnabled()
            val text = prop.expression()
            if (enabled != null && text != null) {
                expressions[propId] = text to enabled
                col.imported.expressions++
            }
        }

        // Orientation: the static value is imported; keyframes are imported too
        // (values.rs orientation_keys, props.rs import_orientation_keys: bezier
        // sides become linear, a renderer difference from After Effects' slerp).
        // An expression is reported, not decoded.
        val orientation = layer.orientation()
        if (orientation != null) {
            when {
                orientation.hasExpression -> col.lose(
                    AepImportReport.LossCategory.TransformProperties,
                    "layer '$name': ADBE Orientation expression is not imported in this build"
                )
                orientation.animated -> {
                    val keys = AepMarkers.importOrientationKeys(layer, name, timescale, col)
                    if (keys != null) {
                        richKeys["orientation"] = keys.map { key ->
                            RichKeyframe(
                                timeTicks = key.timeTicks,
                                value = key.value,
                                isVec3 = true,
                                inInterp = key.inInterp,
                                outInterp = key.outInterp,
                                inEase = emptyList(),
                                outEase = emptyList(),
                                spatialIn = null,
                                spatialOut = null,
                                spatialAuto = null,
                                roving = false,
                                label = 0
                            )
                        }
                        staticValues["orientation"] = keys.first().value
                    }
                }
                orientation.degrees.any { !it.isFinite() || abs(it) > LIMIT } -> col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "layer '$name': ADBE Orientation is unreadable, the default is used"
                )
                orientation.degrees.any { it != 0.0 } ->
                    staticValues["orientation"] = orientation.degrees.toList()
            }
        }

        // Cameras: the Camera Options group (zoom, focus distance, aperture,
        // depth of field) and the two-node auto orientation (props.rs
        // camera_light_orient: auto-orient is set only for cameras and lights).
        var cameraOptions: Map<String, AepCameraImport.CameraOption> = emptyMap()
        var autoOrient = "Off"
        if (type == AeLayerType.CAMERA) {
            val position = AepCameraImport.cameraPosition(transformGroup)
            val optionsGroup = (rootProps?.get("ADBE Camera Options Group") as? AepTypedView.PropNode.Group)?.group
            cameraOptions = AepCameraImport.importCameraOptions(
                optionsGroup, position, "$compName: '$name'", compWidth.toDouble(), timescale, col
            )
            if (header.isAutoOriented()) autoOrient = "TowardsPointOfInterest"
        }

        // Text animators: the animator properties and range selectors of a text
        // layer (text_anim.rs) into the converter's text/animators group.
        var animatorGroups: List<JSONObject> = emptyList()
        if (type == AeLayerType.TEXT) {
            val animatorsGroup = (layer.properties()?.get("ADBE Text Properties") as? AepTypedView.PropNode.Group)?.group
                ?.let { (it.get("ADBE Text Animators") as? AepTypedView.PropNode.Group)?.group }
            animatorGroups = AepTextAnimImport.importAnimators(
                animatorsGroup, "$compName: '$name'", timescale, allocBlock, col
            )
        }

        // Everything else in the transform group: a property in its default state holds
        // nothing to lose (props.rs report_other_properties). Only a stored property
        // (animated, flags 1, expression) that is not in the known set is a loss.
        if (transformGroup != null) {
            val known = TRANSFORM_SPECS.map { it.first }.toSet() +
                setOf("ADBE Rotate X", "ADBE Rotate Y", "ADBE Orientation", "ADBE Group End")
            for (entry in transformGroup.entries()) {
                if (entry.matchName in known) continue
                val p = (entry.node as? AepTypedView.PropNode.Property)?.property ?: continue
                val stored = p.isAnimated() || p.flags() == 1L || p.expression() != null
                val ignored = entry.matchName in IGNORED_TRANSFORM &&
                    p.flags() != 1L && !p.isAnimated()
                if (stored && !ignored) {
                    col.lose(
                        AepImportReport.LossCategory.TransformProperties,
                        "layer '$name': ${entry.matchName}"
                    )
                }
            }
        }

        // Effect size: the source size for footage/solid/precomp layers, the composition
        // size for text and shape layers (they span the composition), and 100x100 for a
        // null layer with no source (import/build.rs).
        val effectSize: Pair<Double, Double> = sourceSize?.let {
            it.first.toDouble() to it.second.toDouble()
        } ?: if (type == AeLayerType.TEXT || type == AeLayerType.SHAPE) {
            compWidth.toDouble() to compHeight.toDouble()
        } else {
            100.0 to 100.0
        }
        var effectGroups: List<JSONObject> = emptyList()
        var pendingLayerRefs: List<AepEffectImport.PendingLayerRef> = emptyList()
        var maskGroups: List<JSONObject> = emptyList()
        var shapeGroups: List<JSONObject> = emptyList()
        /** True when the layer carries an `ADBE Root Vectors Group`: the converter then
         * writes an empty Contents group and the patch step appends the imported groups. */
        var shapesProvided = false
        var timeRemap: JSONObject? = null

        // Everything else in the layer's property tree is reported, never silent.
        if (rootProps != null) {
            for (entry in rootProps.entries()) {
                val entryName = entry.matchName
                when {
                    entryName == "ADBE Transform Group" -> Unit
                    entryName == "ADBE Camera Options Group" -> {
                        // Imported in the camera branch above; nothing to do here.
                    }
                    entryName.contains("Effect Parade", ignoreCase = true) -> {
                        // Cameras cannot have effects (the converter writes no
                        // effects group for them); the official importer skips
                        // the block too.
                        if (type != AeLayerType.CAMERA) {
                            val result = AepEffectImport.importEffects(
                                layer, compName, name, layerIndex, header.id, effectSize,
                                defs, provider, allocBlock, timescale, col
                            )
                            effectGroups = result.groups
                            pendingLayerRefs = result.refs
                        }
                    }
                    entryName.contains("Mask", ignoreCase = true) -> {
                        // Cameras cannot have masks either, for the same reason.
                        if (type != AeLayerType.CAMERA) {
                            // On a text or shape layer, which has no source, the mask box is
                            // stored in layer pixels, so the scale is 1 (import/build.rs).
                            val maskSize: Pair<Double, Double> =
                                if (type == AeLayerType.TEXT || type == AeLayerType.SHAPE) {
                                    1.0 to 1.0
                                } else {
                                    effectSize
                                }
                            maskGroups = AepMasks.importMasks(
                                rootProps, compName, name, maskSize,
                                type != AeLayerType.TEXT && type != AeLayerType.SHAPE,
                                allocBlock, timescale, col
                            )
                        }
                    }
                    entryName.contains("Time Remap", ignoreCase = true) -> {
                        timeRemap = AepMarkers.importTimeRemap(
                            rootProps, name, timescale, { allocBlock(1) }, col
                        )
                    }
                    entryName.contains("Root Vectors", ignoreCase = true) -> {
                        shapeGroups = AepShapeImport.importShapes(
                            rootProps, "shape layer '$name'", timescale, allocBlock, col
                        )
                        shapesProvided = true
                    }
                    entryName.contains("Layer Styles", ignoreCase = true) -> {
                        // A style group with no entries holds nothing set (the fixture's
                        // styles are all empty groups); only a style with values is a loss.
                        // "ADBE Blend Options Group" is blend settings, not a style.
                        val styles = (entry.node as? AepTypedView.PropNode.Group)?.group
                        val set = styles?.entries().orEmpty().count {
                            it.matchName != "ADBE Blend Options Group" &&
                                (it.node as? AepTypedView.PropNode.Group)?.group
                                    ?.entries()?.isNotEmpty() == true
                        } ?: 0
                        if (set > 0) {
                            col.lose(
                                AepImportReport.LossCategory.LayerStyles,
                                "layer '$name': $set layer style(s) with set values are not " +
                                    "imported in this build"
                            )
                        }
                    }
                    entryName.contains("Text Properties", ignoreCase = true) -> {
                        // The text loss was already recorded for text layers.
                        if (type != AeLayerType.TEXT) {
                            col.lose(
                                AepImportReport.LossCategory.TextLayers,
                                "layer '$name': text properties are not imported in this build"
                            )
                        }
                    }
                    entryName.contains("Marker", ignoreCase = true) -> {
                        // Markers live under `mrst`, handled after this loop; nothing to do here.
                    }
                    entryName.contains("Audio", ignoreCase = true) -> {
                        // Covered by the standing limitations (audio levels are not decoded).
                    }
                    entryName.contains("Material", ignoreCase = true) -> {
                        // Covered by the standing limitations (3D material options are not decoded).
                    }
                    entryName in IGNORED_LAYER_GROUPS -> {
                        // Always present, never read by the official importer either.
                    }
                    else -> {
                        col.lose(
                            AepImportReport.LossCategory.UnsupportedLayers,
                            "layer '$name': property group '$entryName' is not imported in this build"
                        )
                    }
                }
            }
        }
        // Layer markers live under `mrst`, not in the property tree entries.
        val layerMarkers = AepMarkers.importMarkers(
            AepMarkers.markersIn(layer.chunk), layer.chunk,
            "$compName: '$name'", timescale, col
        )

        val startSeconds = header.startSeconds()
        val inSeconds = header.inPointSeconds()
        val outSeconds = header.outPointSeconds()
        if (startSeconds == null || inSeconds == null || outSeconds == null) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "layer '$name': the layer times are unreadable, the composition range is used"
            )
        }
        val anchor = if (type == AeLayerType.CAMERA) values.getValue("poi") else values.getValue("anchor")
        val position = values.getValue("position")
        val scale = values.getValue("scale")
        val aeLayer = AeLayer(
            name = name,
            type = type,
            text = text,
            font = font,
            fontSize = fontSize,
            color = color,
            textDoc = textDoc,
            shapesProvided = shapesProvided,
            compIndex = compIndex,
            file = file,
            kind = kind,
            hasAudio = hasAudio,
            frameRate = frameRate,
            width = width,
            height = height,
            durationSeconds = durationSeconds,
            position = position[0] to position[1],
            anchorPoint = anchor[0] to anchor[1],
            scale = scale[0] to scale[1],
            rotation = values.getValue("rotation")[0],
            opacity = values.getValue("opacity")[0],
            startTime = startSeconds ?: 0.0,
            inPoint = inSeconds ?: 0.0,
            outPoint = outSeconds ?: compDuration,
            parentName = parentName,
            keyframes = simpleKeys(richKeys)
        )
        val blend = blendName(header.blendMode.code())
        // Cameras and lights have no blend mode (their byte is 0): no loss.
        if (blend == null && type != AeLayerType.CAMERA) {
            col.lose(
                AepImportReport.LossCategory.BlendModes,
                "layer '$name': blend mode code ${header.blendMode.code()} is not known, " +
                    "Normal is used"
            )
        }
        // Track matte: the mode at ldta byte 107, the matte layer id at byte 160,
        // or the layer above when the header holds no id (matte.rs).
        val trackMatte = readTrackMatte(header, aboveId, "$compName: '$name'", col)
        val patch = LayerPatch(
            stretch = header.stretchPercent(),
            blendMode = blend ?: "Normal",
            switches = Switches(
                video = header.isVideoEnabled(),
                audio = header.isAudioEnabled(),
                solo = header.isSolo(),
                locked = header.isLocked(),
                shy = header.isShy(),
                collapse = header.collapsesTransformations(),
                quality = when (header.quality) {
                    AepHeader.Quality.Draft -> "Draft"
                    else -> "Best"
                },
                effects = header.isEffectsEnabled(),
                motionBlur = header.isMotionBlur(),
                adjustment = header.isAdjustment(),
                threeD = header.isThreeD(),
                guide = header.isGuide()
            ),
            richKeys = richKeys,
            expressions = expressions,
            staticValues = staticValues,
            aepLayerId = header.id,
            effectGroups = effectGroups,
            pendingLayerRefs = pendingLayerRefs,
            maskGroups = maskGroups,
            shapeGroups = shapeGroups,
            markers = layerMarkers,
            timeRemap = timeRemap,
            cameraOptions = cameraOptions,
            autoOrient = autoOrient,
            trackMatte = trackMatte,
            animatorGroups = animatorGroups
        )
        return aeLayer to patch
    }

    /** The track matte of a layer: its canonical kind and the matte layer's id
     * in the file. The matte layer id at `ldta` byte 160 wins; without it the
     * matte is the layer above (the form of After Effects before 23.0). */
    private fun readTrackMatte(
        header: AepHeader.LayerHeader,
        aboveId: Long?,
        what: String,
        col: AepImportReport.Collector
    ): PendingTrackMatte? {
        val kind = when (header.matteModeCode) {
            0 -> return null
            1 -> "Alpha"
            2 -> "AlphaInverted"
            3 -> "Luma"
            4 -> "LumaInverted"
            else -> {
                col.lose(
                    AepImportReport.LossCategory.TrackMattes,
                    "$what has a track matte of unknown mode ${header.matteModeCode}"
                )
                return null
            }
        }
        val target = header.matteLayerId ?: aboveId
        if (target == null) {
            col.lose(
                AepImportReport.LossCategory.TrackMattes,
                "$what has a track matte but no layer above it"
            )
            return null
        }
        if (target == header.id) {
            col.lose(
                AepImportReport.LossCategory.TrackMattes,
                "$what is its own track matte"
            )
            return null
        }
        return PendingTrackMatte(kind, target, what)
    }

    private data class PropRead(val value: List<Double>, val unreadable: Boolean)

    /** Reads a transform property: the static value, or the first key's value when animated.
     * Absent means the default; present-but-unreadable means the default plus a flag. */
    private fun readTransformValue(
        prop: AepTypedView.Property,
        default: List<Double>,
        convert: (List<Double>) -> List<Double>?
    ): PropRead {
        val raw = if (prop.isAnimated()) prop.firstKeyValue() else prop.staticValue()
        if (raw == null) return PropRead(default, true)
        val converted = convert(raw) ?: return PropRead(default, true)
        return PropRead(converted, false)
    }

    /** The stored value in project units, or null when a needed component is missing or
     * not a finite number within the sane range. */
    private fun convertValue(
        kind: PropKind,
        raw: List<Double>,
        anchorSize: Pair<Double, Double>?
    ): List<Double>? {
        fun component(i: Int): Double? =
            raw.getOrNull(i)?.takeIf { it.isFinite() && abs(it) <= LIMIT }
        return when (kind) {
            PropKind.ANCHOR -> {
                val x = component(0) ?: return null
                val y = component(1) ?: return null
                val z = component(2) ?: 0.0
                if (anchorSize != null) {
                    listOf(x * anchorSize.first, y * anchorSize.second, z)
                } else {
                    listOf(x, y, z)
                }
            }
            PropKind.POSITION -> {
                val x = component(0) ?: return null
                val y = component(1) ?: return null
                val z = component(2) ?: 0.0
                listOf(x, y, z)
            }
            PropKind.SCALE -> {
                val x = component(0) ?: return null
                val y = component(1) ?: return null
                val z = raw.getOrNull(2)?.takeIf { it.isFinite() && abs(it) <= LIMIT }
                    ?.let { it * 100.0 } ?: 100.0
                listOf(x * 100.0, y * 100.0, z)
            }
            PropKind.ROTATION -> {
                val v = component(0) ?: return null
                listOf(v)
            }
            PropKind.OPACITY -> {
                val v = component(0) ?: return null
                listOf(v * 100.0)
            }
        }
    }

    /** The keyframes of an animated transform property as rich keyframes. */
    private fun importKeys(
        prop: AepTypedView.Property,
        kind: PropKind,
        layerName: String,
        matchName: String,
        compTimescale: Long,
        anchorSize: Pair<Double, Double>?,
        col: AepImportReport.Collector
    ): List<RichKeyframe> {
        val what = "layer '$layerName': $matchName"
        val list = prop.keys() ?: return emptyList()
        val scale = prop.timeScale()?.takeIf { it != 0L } ?: compTimescale
        if (scale == 0L) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: the time scale is zero, the keyframes are skipped"
            )
            return emptyList()
        }
        val isVec3 = kind == PropKind.ANCHOR || kind == PropKind.POSITION || kind == PropKind.SCALE
        val factor = when (kind) {
            PropKind.ANCHOR -> anchorSize?.second ?: 1.0
            PropKind.SCALE, PropKind.OPACITY -> 100.0
            else -> 1.0
        }
        val keys = ArrayList<RichKeyframe>()
        for (rec in list.keyframes()) {
            val units = rec.timeUnits()
            val raw = rec.value()
            if (units == null || raw == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: unreadable keyframe"
                )
                continue
            }
            val value = convertValue(kind, raw, anchorSize)
            if (value == null) {
                col.lose(
                    AepImportReport.LossCategory.DamagedData,
                    "$what: keyframe value is missing or not a number"
                )
                continue
            }
            var inInterp = interp(rec.interpolationIn(), col, what)
            var outInterp = interp(rec.interpolationOut(), col, what)
            val spatial = rec.spatial()
            val spatialIn = spatial?.let { convertTangent(kind, it.inTangent, anchorSize) }
            val spatialOut = spatial?.let { convertTangent(kind, it.outTangent, anchorSize) }
            val spatialAuto = spatial?.let { it.autoBezierFlag != 0L }
            val roving = rec.isRoving() ?: false
            val label = rec.label()?.takeIf { it <= 16 } ?: 0
            var inEase = emptyList<RichEase>()
            var outEase = emptyList<RichEase>()
            if (inInterp == "Bezier" || outInterp == "Bezier") {
                val eases = keyEases(rec, factor)
                if (eases == null) {
                    col.lose(
                        AepImportReport.LossCategory.ApproximatedKeyframes,
                        "$what: bezier key read as linear (ease data not decodable)"
                    )
                    if (inInterp == "Bezier") inInterp = "Linear"
                    if (outInterp == "Bezier") outInterp = "Linear"
                } else {
                    if (inInterp == "Bezier") inEase = eases.first
                    if (outInterp == "Bezier") outEase = eases.second
                }
            }
            keys.add(
                RichKeyframe(
                    timeTicks = ticks(units.toDouble() / scale.toDouble()),
                    value = value,
                    isVec3 = isVec3,
                    inInterp = inInterp,
                    outInterp = outInterp,
                    inEase = inEase,
                    outEase = outEase,
                    spatialIn = spatialIn,
                    spatialOut = spatialOut,
                    spatialAuto = spatialAuto,
                    roving = roving,
                    label = label
                )
            )
        }
        if (keys.isEmpty()) return emptyList()
        keys.sortBy { it.timeTicks }
        val deduped = ArrayList<RichKeyframe>(keys.size)
        var lastTime: Long? = null
        var hadDupes = false
        for (key in keys) {
            if (key.timeTicks == lastTime) {
                hadDupes = true
                continue
            }
            deduped.add(key)
            lastTime = key.timeTicks
        }
        if (hadDupes) {
            col.lose(
                AepImportReport.LossCategory.DamagedData,
                "$what: keyframes at the same time"
            )
        }
        // The first key's incoming and the last key's outgoing ease mean nothing.
        val cleaned = deduped.mapIndexed { index, key ->
            when (index) {
                0 -> key.copy(inEase = emptyList())
                deduped.lastIndex -> key.copy(outEase = emptyList())
                else -> key
            }
        }
        col.imported.keyframes += cleaned.size
        return cleaned
    }

    /** The interpolation of one side of a key; unknown codes read as linear. */
    private fun interp(
        interp: AepKeys.Interpolation?,
        col: AepImportReport.Collector,
        what: String
    ): String = when (interp) {
        null, AepKeys.Interpolation.Linear -> "Linear"
        AepKeys.Interpolation.Bezier -> "Bezier"
        AepKeys.Interpolation.Hold -> "Hold"
        is AepKeys.Interpolation.Other -> {
            col.lose(
                AepImportReport.LossCategory.ApproximatedKeyframes,
                "$what: unknown interpolation ${interp.code} read as linear"
            )
            "Linear"
        }
    }

    /** The temporal ease of both sides of a key: one entry per dimension, or null where the
     * data cannot be used. */
    private fun keyEases(
        rec: AepTypedView.Keyframe,
        factor: Double
    ): Pair<List<RichEase>, List<RichEase>>? {
        fun ease(speed: Double, influence: Double): RichEase? {
            val scaled = speed * factor
            if (!scaled.isFinite() || abs(scaled) > LIMIT * 100.0 || !influence.isFinite()) {
                return null
            }
            return RichEase(scaled, influence.coerceIn(0.001, 1.0))
        }
        return when (val kind = rec.kind()) {
            is AepKeys.KeyframeKind.Spatial, is AepKeys.KeyframeKind.Plain -> {
                if (kind is AepKeys.KeyframeKind.Plain && kind.components != 1) {
                    val all = rec.easeComponents() ?: return null
                    if (all.size != kind.components) return null
                    val inEases = ArrayList<RichEase>(all.size)
                    val outEases = ArrayList<RichEase>(all.size)
                    for (e in all) {
                        inEases.add(ease(e.inSpeed, e.inInfluence) ?: return null)
                        outEases.add(ease(e.outSpeed, e.outInfluence) ?: return null)
                    }
                    inEases to outEases
                } else {
                    val e = rec.ease() ?: return null
                    listOf(ease(e.inSpeed, e.inInfluence) ?: return null) to
                        listOf(ease(e.outSpeed, e.outInfluence) ?: return null)
                }
            }
            else -> null
        }
    }

    /** A tangent offset, in the same units as the value. */
    private fun convertTangent(
        kind: PropKind,
        tangent: List<Double>,
        anchorSize: Pair<Double, Double>?
    ): DoubleArray {
        fun component(i: Int): Double =
            tangent.getOrNull(i)?.takeIf { it.isFinite() } ?: 0.0
        val t = when (kind) {
            PropKind.ANCHOR -> if (anchorSize != null) {
                doubleArrayOf(
                    component(0) * anchorSize.first,
                    component(1) * anchorSize.second,
                    component(2)
                )
            } else {
                doubleArrayOf(component(0), component(1), component(2))
            }
            else -> doubleArrayOf(component(0), component(1), component(2))
        }
        return if (t.all { abs(it) <= LIMIT }) t else doubleArrayOf(0.0, 0.0, 0.0)
    }

    /** The footage kind for the converter: still, audio or video. */
    private fun footageKind(file: String, hasAudio: Boolean, width: Long): String {
        val ext = file.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff" -> "still"
            "mp3", "wav", "aac", "flac", "ogg", "m4a", "opus" -> "audio"
            else -> if (hasAudio && width == 0L) "audio" else "video"
        }
    }

    /** The simple keyframes the converter writes (time + value); the patch replaces them
     * with the rich keyframes. */
    private fun simpleKeys(richKeys: Map<String, List<RichKeyframe>>): Map<String, List<AeKeyframe>> {
        val out = HashMap<String, List<AeKeyframe>>()
        for ((propId, keys) in richKeys) {
            val frames = keys.map { key ->
                val seconds = key.timeTicks.toDouble() / AeInterchangeConverter.TICKS_PER_SECOND
                val value = if (key.isVec3) {
                    AeValue.Vec2(key.value[0], key.value[1])
                } else {
                    AeValue.Scalar(key.value[0])
                }
                AeKeyframe(seconds, value)
            }
            out[propId] = frames
        }
        return out
    }

    // ------------------------------------------------------------------ patching

    /** Patches the converter output with the fields the interchange model does not carry. */
    private fun patchCanonical(json: String, built: Built): String {
        val root = JSONObject(json)
        val items = root.getJSONObject("items")
        // The converter writes one item per composition, in project order; match each
        // patch to its composition by name (two compositions with the same name would
        // collide, as they already do for layer names in the converter).
        val compKindByName = HashMap<String, JSONObject>()
        val itemKeys = items.keys()
        while (itemKeys.hasNext()) {
            val key = itemKeys.next()
            val item = items.getJSONObject(key)
            val kind = item.optJSONObject("kind") ?: continue
            if (kind.optString("type") == "Comp") {
                compKindByName[item.optString("name")] = kind
            }
        }
        // Effect groups carry patch-space uids (from 1); they live above the converter's
        // `next_id`, so every patch uid is shifted by next_id - 1 and the root's next_id
        // moves past the highest patch uid. The offset and the counters stay project-global,
        // so patch uids never collide across compositions.
        val uidOffset = root.optLong("next_id", 1L) - 1L
        val state = PatchUidState()
        for ((index, aeComp) in built.project.comps.withIndex()) {
            val comp = compKindByName[aeComp.name]
                ?: throw IllegalStateException("converter output has no composition '${aeComp.name}'")
            patchOneComp(
                root, comp, built.compPatches[index], built.layerPatches[index], uidOffset, state
            )
        }
        if (state.maxPatchUid > 0L || state.extraUid > 0L) {
            root.put("next_id", root.optLong("next_id", 1L) + state.maxPatchUid + state.extraUid)
        }
        return root.toString()
    }

    /** Uids created by the patch step, shared across compositions so they never collide. */
    private class PatchUidState(var maxPatchUid: Long = 0L, var extraUid: Long = 0L)

    /**
     * Patches one composition: its fields and markers, then each layer's stretch, blend,
     * switches, rich keys, expressions, effect/mask/shape groups and time remap, then
     * resolves that composition's layer references. Layer ids can repeat across
     * compositions in a real .aep, so the file-id map is scoped to this composition.
     */
    private fun patchOneComp(
        root: JSONObject,
        comp: JSONObject,
        patch: CompPatch,
        layerPatches: List<LayerPatch>,
        uidOffset: Long,
        state: PatchUidState
    ) {
        comp.put("pixel_aspect", patch.pixelAspect)
        comp.put("display_start", patch.displayStartTicks)
        comp.put("work_area", JSONArray().put(patch.workAreaStartTicks).put(patch.workAreaEndTicks))
        comp.put("shutter_angle", patch.shutterAngle)
        comp.put("shutter_phase", patch.shutterPhase)
        comp.put("renderer", patch.renderer)
        if (patch.markers.length() > 0) comp.put("markers", patch.markers)
        val layers = comp.getJSONArray("layers")
        // Effect groups carry patch-space uids (from 1); they live above the converter's
        // `next_id`, so every patch uid is shifted by next_id - 1.
        val allRefs = ArrayList<AepEffectImport.PendingLayerRef>()
        val fileIdToEcId = HashMap<Long, Long>()
        for (i in 0 until layers.length()) {
            val layerJson = layers.getJSONObject(i)
            val layerPatch = layerPatches.getOrNull(i) ?: continue
            fileIdToEcId[layerPatch.aepLayerId] = layerJson.optLong("id")
            for (group in layerPatch.effectGroups) {
                state.maxPatchUid = maxOf(state.maxPatchUid, AepEffectImport.maxUid(group).toLong())
            }
            for (group in layerPatch.maskGroups) {
                state.maxPatchUid = maxOf(state.maxPatchUid, AepEffectImport.maxUid(group).toLong())
            }
            for (group in layerPatch.shapeGroups) {
                state.maxPatchUid = maxOf(state.maxPatchUid, AepEffectImport.maxUid(group).toLong())
            }
            for (group in layerPatch.animatorGroups) {
                state.maxPatchUid = maxOf(state.maxPatchUid, AepEffectImport.maxUid(group).toLong())
            }
            layerPatch.timeRemap?.let { state.maxPatchUid = maxOf(state.maxPatchUid, it.optLong("uid")) }
            for (ref in layerPatch.pendingLayerRefs) {
                allRefs.add(
                    AepEffectImport.PendingLayerRef(
                        ref.layerIndex, ref.propUid + uidOffset, ref.target, ref.what
                    )
                )
            }
        }
        for (i in 0 until layers.length()) {
            val layerJson = layers.getJSONObject(i)
            val layerPatch = layerPatches.getOrNull(i) ?: continue
            layerJson.put("stretch", layerPatch.stretch)
            layerJson.put("blend_mode", layerPatch.blendMode)
            layerJson.put("auto_orient", layerPatch.autoOrient)
            layerJson.put("switches", switchesJson(layerPatch.switches))
            if (layerPatch.markers.length() > 0) layerJson.put("markers", layerPatch.markers)
            val props = layerJson.optJSONObject("props") ?: continue
            val children = props.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.getJSONObject(c)
                if (child.optString("match") != "transform") continue
                val tchildren = child.optJSONArray("children") ?: continue
                for (t in 0 until tchildren.length()) {
                    val prop = tchildren.getJSONObject(t)
                    val match = prop.optString("match")
                    val keys = layerPatch.richKeys[match]
                    if (keys != null) {
                        prop.put("keys", richKeysJson(keys))
                    }
                    val static = layerPatch.staticValues[match]
                    if (static != null) {
                        prop.put("value", staticValueJson(match, static))
                    }
                    val expr = layerPatch.expressions[match]
                    if (expr != null) {
                        prop.put(
                            "expr",
                            JSONObject().put("text", expr.first).put("enabled", expr.second)
                        )
                    }
                }
            }
            // Camera options: overwrite the converter defaults with the imported values.
            if (layerPatch.cameraOptions.isNotEmpty()) {
                for (c in 0 until children.length()) {
                    val child = children.getJSONObject(c)
                    if (child.optString("match") != "cameraOptions") continue
                    val ochildren = child.optJSONArray("children") ?: continue
                    for (o in 0 until ochildren.length()) {
                        val prop = ochildren.getJSONObject(o)
                        val option = layerPatch.cameraOptions[prop.optString("match")] ?: continue
                        prop.put("value", option.value)
                        if (option.keys != null) prop.put("keys", option.keys)
                        if (option.expr != null) {
                            prop.put(
                                "expr",
                                JSONObject().put("text", option.expr.first)
                                    .put("enabled", option.expr.second)
                            )
                        }
                    }
                }
            }
            // Track matte: the matte layer's project id, resolved now that every
            // layer's project id exists. A matte whose layer was not imported
            // stays unset (the loss was recorded in buildComp).
            layerPatch.trackMatte?.let { matte ->
                val matteId = fileIdToEcId[matte.targetAepId] ?: return@let
                layerJson.put(
                    "track_matte",
                    JSONObject().put("layer", matteId).put("kind", matte.kind)
                )
            }
            // Effects: append the imported groups (uids remapped into project space)
            // to the layer's effects group.
            if (layerPatch.effectGroups.isNotEmpty()) {
                for (c in 0 until children.length()) {
                    val child = children.getJSONObject(c)
                    if (child.optString("match") != "effects") continue
                    val fxChildren = child.optJSONArray("children") ?: continue
                    for (group in layerPatch.effectGroups) {
                        AepEffectImport.remapUids(group, uidOffset)
                        fxChildren.put(group)
                    }
                }
            }
            // Masks: the same, into the layer's masks group. The converter writes no
            // masks group for null layers, so one is created with a fresh uid.
            if (layerPatch.maskGroups.isNotEmpty()) {
                var masksChild: JSONObject? = null
                for (c in 0 until children.length()) {
                    val child = children.getJSONObject(c)
                    if (child.optString("match") == "masks") masksChild = child
                }
                if (masksChild == null) {
                    val freshUid = root.optLong("next_id", 1L) + state.maxPatchUid + state.extraUid
                    state.extraUid += 1
                    masksChild = JSONObject()
                        .put("node", "Group")
                        .put("uid", freshUid)
                        .put("match", "masks")
                        .put("name", "Masks")
                        .put("kind", JSONObject().put("kind", "Plain"))
                        .put("enabled", true)
                        .put("children", JSONArray())
                    children.put(masksChild)
                }
                val maskChildren = masksChild.optJSONArray("children") ?: continue
                for (group in layerPatch.maskGroups) {
                    AepEffectImport.remapUids(group, uidOffset)
                    maskChildren.put(group)
                }
            }
            // Shapes: the same, into the layer's contents group. The converter writes
            // an empty contents group when the importer provides the shapes.
            if (layerPatch.shapeGroups.isNotEmpty()) {
                for (c in 0 until children.length()) {
                    val child = children.getJSONObject(c)
                    if (child.optString("match") != "contents") continue
                    val contentsChildren = child.optJSONArray("children") ?: continue
                    for (group in layerPatch.shapeGroups) {
                        AepEffectImport.remapUids(group, uidOffset)
                        contentsChildren.put(group)
                    }
                }
            }
            // Text animators: append the imported groups (uids remapped into
            // project space) to the text/animators group.
            if (layerPatch.animatorGroups.isNotEmpty()) {
                for (c in 0 until children.length()) {
                    val child = children.getJSONObject(c)
                    if (child.optString("match") != "text") continue
                    val textChildren = child.optJSONArray("children") ?: continue
                    for (t in 0 until textChildren.length()) {
                        val textChild = textChildren.getJSONObject(t)
                        if (textChild.optString("match") != "animators") continue
                        val animChildren = textChild.optJSONArray("children") ?: continue
                        for (group in layerPatch.animatorGroups) {
                            AepEffectImport.remapUids(group, uidOffset)
                            animChildren.put(group)
                        }
                    }
                }
            }
            // Time Remapping goes first in the layer's properties.
            layerPatch.timeRemap?.let { remap ->
                remap.put("uid", remap.optLong("uid") + uidOffset)
                val current = props.optJSONArray("children") ?: return@let
                val shifted = JSONArray().put(remap)
                for (c in 0 until current.length()) shifted.put(current.get(c))
                props.put("children", shifted)
            }
        }
        // Layer parameters that name another layer resolve now that every layer's
        // project id exists. A target that was not imported was already reported in
        // build(); here it simply stays unset.
        if (allRefs.isNotEmpty()) {
            AepEffectImport.resolveLayerRefs(
                layers, fileIdToEcId, allRefs, AepImportReport.Collector(),
                reportMissing = false
            )
        }
    }

    private fun switchesJson(switches: Switches): JSONObject = JSONObject()
        .put("video", switches.video)
        .put("audio", switches.audio)
        .put("solo", switches.solo)
        .put("locked", switches.locked)
        .put("shy", switches.shy)
        .put("collapse", switches.collapse)
        .put("quality", switches.quality)
        .put("sampling", "Bilinear")
        .put("effects", switches.effects)
        .put("frame_blend", "Off")
        .put("motion_blur", switches.motionBlur)
        .put("adjustment", switches.adjustment)
        .put("three_d", switches.threeD)
        .put("guide", switches.guide)

    /** The static value of a patched property: a Vec3 for the orientation and
     * for a 3D position, anchor, point of interest or scale; a scalar otherwise. */
    private fun staticValueJson(match: String, values: List<Double>): JSONObject =
        if (match == "orientation" || values.size == 3) {
            JSONObject().put("t", "Vec3").put(
                "v",
                numberArray(listOf(values.getOrElse(0) { 0.0 }, values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 }))
            )
        } else {
            JSONObject().put("t", "Scalar").put("v", values.firstOrNull() ?: 0.0)
        }

    private fun richKeysJson(keys: List<RichKeyframe>): JSONArray {
        val array = JSONArray()
        for (key in keys) {
            if (!key.value.all { it.isFinite() }) continue
            val obj = JSONObject()
                .put("time", key.timeTicks)
                .put("value", valueJson(key))
                .put("in_interp", key.inInterp)
                .put("out_interp", key.outInterp)
            if (key.inEase.isNotEmpty()) obj.put("in_ease", easeArray(key.inEase))
            if (key.outEase.isNotEmpty()) obj.put("out_ease", easeArray(key.outEase))
            if (key.spatialIn != null) obj.put("spatial_in", numberArray(key.spatialIn.toList()))
            if (key.spatialOut != null) obj.put("spatial_out", numberArray(key.spatialOut.toList()))
            if (key.spatialAuto == false) obj.put("spatial_auto", false)
            if (key.roving) obj.put("roving", true)
            if (key.label != 0) obj.put("label", key.label)
            array.put(obj)
        }
        return array
    }

    private fun valueJson(key: RichKeyframe): JSONObject =
        if (key.isVec3) {
            JSONObject().put("t", "Vec3")
                .put("v", numberArray(listOf(key.value[0], key.value[1], key.value.getOrElse(2) { 0.0 })))
        } else {
            JSONObject().put("t", "Scalar").put("v", key.value[0])
        }

    private fun easeArray(eases: List<RichEase>): JSONArray {
        val array = JSONArray()
        for (e in eases) {
            array.put(JSONObject().put("speed", e.speed).put("influence", e.influence))
        }
        return array
    }

    private fun numberArray(values: List<Double>): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value)
        return array
    }
}
