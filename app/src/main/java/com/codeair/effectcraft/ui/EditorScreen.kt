package com.codeair.effectcraft.ui

import com.codeair.effectcraft.data.BezierCurveEditor
import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalCurveKey
import com.codeair.effectcraft.data.CanonicalLayerInfo
import com.codeair.effectcraft.data.CanonicalShapePathDraft
import com.codeair.effectcraft.data.CanonicalProjectRepository
import com.codeair.effectcraft.data.CurveHandle
import com.codeair.effectcraft.data.CurveInterp
import com.codeair.effectcraft.data.CurveKey
import com.codeair.effectcraft.data.CurveTrack
import com.codeair.effectcraft.data.MotionPath
import com.codeair.effectcraft.data.OfflineAssetStore
import com.codeair.effectcraft.data.SafPermissionRegistry

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.effectcraft.data.CanonicalGradientParam
import com.codeair.effectcraft.data.CanonicalParamGroup
import com.codeair.effectcraft.data.holdValueAt
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.PropertyKeyframe
import com.codeair.effectcraft.data.HevcEncoderProbe
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ShapePathCanvasMapping(
    val minX: Double,
    val minY: Double,
    val scale: Float,
    val left: Float,
    val bottom: Float,
    val height: Float
) {
    fun toCanvas(vertex: CanonicalShapePathDraft.Vertex) = Offset(
        left + ((vertex.x - minX) * scale).toFloat(),
        height - bottom - ((vertex.y - minY) * scale).toFloat()
    )

    fun fromCanvas(point: Offset) = CanonicalShapePathDraft.Vertex(
        minX + ((point.x - left) / scale).toDouble(),
        minY + ((height - bottom - point.y) / scale).toDouble()
    )
}

private fun shapePathCanvasMapping(
    vertices: List<CanonicalShapePathDraft.Vertex>,
    width: Float,
    height: Float
): ShapePathCanvasMapping {
    val minX = vertices.minOf { it.x }
    val maxX = vertices.maxOf { it.x }
    val minY = vertices.minOf { it.y }
    val maxY = vertices.maxOf { it.y }
    val spanX = (maxX - minX).coerceAtLeast(1.0)
    val spanY = (maxY - minY).coerceAtLeast(1.0)
    val availableWidth = (width - 24f).coerceAtLeast(1f)
    val availableHeight = (height - 24f).coerceAtLeast(1f)
    val scale = minOf(availableWidth / spanX.toFloat(), availableHeight / spanY.toFloat()).coerceAtLeast(0.001f)
    val drawnWidth = spanX.toFloat() * scale
    val drawnHeight = spanY.toFloat() * scale
    return ShapePathCanvasMapping(minX, minY, scale, (width - drawnWidth) / 2f, (height - drawnHeight) / 2f, height)
}

private object EditorPalette {
    val background = Color(0xFF0A0C11)
    val panel = Color(0xFF131720)
    val panelRaised = Color(0xFF1A202B)
    val border = Color(0xFF293241)
    val text = Color(0xFFE9EAF2)
    val muted = Color(0xFF929BAD)
    val violet = Color(0xFFAA95FF)
    val mint = Color(0xFF67D7C5)
    val amber = Color(0xFFFFC477)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    project: ProjectDocument,
    selectedLayer: LayerDocument?,
    selectedLayerId: String,
    playheadFrame: Int,
    selectedOpacityKeyframes: List<PropertyKeyframe>,
    canonicalTransformKeyframes: List<Int> = emptyList(),
    canonicalTransformDurationFrames: Int = project.durationFrames,
    canonicalColorKeys: List<CanonicalCurveKey> = emptyList(),
    canonicalColorDurationFrames: Int = project.durationFrames,
    isPlaying: Boolean,
    saveMessage: String,
    savedProjects: List<ProjectDocument>,
    repositoryProjects: List<CanonicalProjectRepository.Metadata> = emptyList(),
    activeRepositoryProjectId: String? = null,
    onOpenRepositoryProject: (String) -> Unit = {},
    offlineAssets: List<OfflineAssetStore.Asset>,
    referencedOfflineAssetIds: Set<String>,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteOfflineAsset: (String) -> Unit,
    onValidateEcproj: () -> Unit,
    onExportEcproj: () -> Unit,
    onExportEcprojx: () -> Unit,
    isExportingFrame: Boolean,
    onExportRenderedFrame: () -> Unit,
    onCancelFrameExport: () -> Unit,
    isExportingSequence: Boolean,
    isSequenceExportWritingOutput: Boolean,
    onExportPngSequence: (width: Int, height: Int, fps: Int, startFrame: Int, endFrame: Int) -> Unit,
    onCancelSequenceExport: () -> Unit,
    sequenceExportProgress: Int,
    isExportingMp4: Boolean = false,
    isMp4ExportWritingOutput: Boolean = false,
    onOpenMp4Settings: () -> Unit = {},
    onCancelMp4Sequence: () -> Unit = {},
    mp4ExportProgress: Int = 0,
    onExportMp4Sequence: (width: Int, height: Int, fps: Int, startFrame: Int, endFrame: Int, bitrate: Int, outputFormat: RenderOutputFormat) -> Unit = { _, _, _, _, _, _, _ -> },
    onNewProject: () -> Unit,
    onAddLayer: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onDeleteLayer: () -> Unit,
    onMoveLayer: (Int) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onRenameLayer: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScrub: (Int) -> Unit,
    onAddOpacityKeyframe: () -> Unit,
    onRemoveOpacityKeyframe: () -> Unit,
    onTogglePlayback: () -> Unit,
    onApplyComposition: (Int, Int, Int, Int) -> Unit,
    canonicalProject: CanonicalProjectInfo?,
    canEditCanonicalProject: Boolean,
    canonicalSelectedLayerIds: Set<Long> = emptySet(),
    onCanonicalSelectionChange: (Set<Long>) -> Unit = {},
    canUndoCanonical: Boolean = false,
    canRedoCanonical: Boolean = false,
    onUndoCanonical: () -> Unit = {},
    onRedoCanonical: () -> Unit = {},
    onSetCanonicalLayerOpacityForSelection: (Long, Set<Long>, Double) -> Unit = { _, _, _ -> },
    onSetCanonicalLayerVisibilityForSelection: (Long, Set<Long>, Boolean) -> Unit = { _, _, _ -> },
    onGestureEditCanonicalSelection: (Long, Map<Long, Pair<Double, Double>>, Map<Long, Pair<Double, Double>>, Map<Long, Double>) -> Unit = { _, _, _, _ -> },
    onRenameCanonicalLayer: (Long, Long, String) -> Unit,
    onAddCanonicalTextLayer: (Long, String) -> Unit,
    onSetCanonicalLayerVisibility: (Long, Long, Boolean) -> Unit,
    onMoveCanonicalLayer: (Long, Long, Int) -> Unit,
    onDeleteCanonicalLayer: (Long, Long) -> Unit,
    onSetCanonicalLayerOpacity: (Long, Long, Double) -> Unit,
    onAddCanonicalLayerOpacityKeyframe: (Long, Long, Int, Double) -> Unit,
    onAddCanonicalLayerTransformKeyframe: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
    onRemoveCanonicalLayerTransformKeyframe: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerTransformInterpolation: (Long, Long, String, Int, String) -> Unit = { _, _, _, _, _ -> },
    onSetCanonicalKeyframeRoving: (Long, Long, String, Int, Boolean) -> Unit = { _, _, _, _, _ -> },
    onSetCanonicalKeyframeSpatialTangents: (Long, Long, String, Int, Double, Double, Double, Double, Double, Double, Boolean) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onMoveCanonicalCurveKey: (Long, Long, String, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onMoveCanonicalTransformKeyframe: (Int, Int) -> Unit = { _, _ -> },
    onSetCanonicalCurveHandle: (Long, Long, String, Int, String, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddCanonicalCurveKey: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
    onRemoveCanonicalCurveKey: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
    onRemoveCanonicalLayerOpacityKeyframe: (Long, Long, Int) -> Unit,
    onSetCanonicalLayerPosition: (Long, Long, Double, Double) -> Unit,
    onSetCanonicalLayerScale: (Long, Long, Double, Double) -> Unit,
    onSetCanonicalLayerRotation: (Long, Long, Double) -> Unit,
    onSetCanonicalLayerAnchor: (Long, Long, Double, Double) -> Unit,
    onSetCanonicalLayerOrientation: (Long, Long, Double, Double, Double) -> Unit,
    onSetCanonicalLayerRotationX: (Long, Long, Double) -> Unit,
    onSetCanonicalLayerRotationY: (Long, Long, Double) -> Unit,
    onSetCanonicalLayerLocked: (Long, Long, Boolean) -> Unit,
    onSetCanonicalTextLayerText: (Long, Long, String) -> Unit,
    onSetCanonicalTextLayerTextWholeLayer: (Long, Long, String) -> Unit = { _, _, _ -> },
    onSetCanonicalTextLayerKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalTextLayerSize: (Long, Long, Double) -> Unit,
    onSetCanonicalTextLayerTracking: (Long, Long, Double) -> Unit,
    onSetCanonicalTextLayerFont: (Long, Long, String) -> Unit = { _, _, _ -> },
    onSetCanonicalTextLayerFontKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalTextLayerTrackingKeyframe: (Long, Long, Long, Int, Double, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalLayerScalarProperty: (Long, Long, Long, Double) -> Unit,
    onSetCanonicalLayerVecProperty: (Long, Long, Long, Double, Double, Double) -> Unit,
    onSetCanonicalLayerColorProperty: (Long, Long, Long, Double, Double, Double, Double) -> Unit,
    onSetCanonicalLayerColorKeyframe: (Long, Long, Long, Int, Double, Double, Double, Double, Boolean) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onSetCanonicalLayerGradientProperty: (Long, Long, Long, String) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerPathProperty: (Long, Long, Long, String) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerPathKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalLayerReferenceProperty: (Long, Long, Long, Long?) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerReferenceKeyframe: (Long, Long, Long, Int, Long?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalLayerBoolProperty: (Long, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerPopupProperty: (Long, Long, Long, Int) -> Unit = { _, _, _, _ -> },
    onSetCanonicalLayerBoolKeyframe: (Long, Long, Long, Int, Boolean, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetCanonicalLayerPopupKeyframe: (Long, Long, Long, Int, Int, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onRelinkCanonicalAsset: (Long) -> Unit,
    onRenderCanonicalFrame: (Long, Long) -> ByteArray?,
    onQueryBackendStatus: (() -> String?)? = null,
    onQueryMediaCacheStatus: (() -> String?)? = null,
    safGrants: List<SafPermissionRegistry.Grant>,
    missingManifestEntries: Int,
    onReleaseSafGrant: (String, SafPermissionRegistry.Kind) -> Unit,
    onReplaceOfflineAsset: (String) -> Unit,
    onCollectUnusedOfflineAssets: () -> Unit
) {
    val selectedTool = remember { mutableStateOf("Select") }
    var interpolationProperty by remember { mutableStateOf("position") }
    var interpolationSide by remember { mutableStateOf("both") }
    var showPhoneInspector by remember { mutableStateOf(false) }
    // Deleting the inspected layer removes the inspector's subject, so on a
    // phone the inspector sheet must close and reveal the updated editor and
    // its status message instead of staying open over them.
    val handleDeleteLayer: () -> Unit = {
        onDeleteLayer()
        showPhoneInspector = false
    }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showRepositoryPicker by remember { mutableStateOf(false) }
    var showMediaLibrary by remember { mutableStateOf(false) }
    var assetPendingDelete by remember { mutableStateOf<OfflineAssetStore.Asset?>(null) }
    var showCompositionSettings by remember { mutableStateOf(false) }
    var showCanonicalEditor by remember { mutableStateOf(false) }
    var showSequenceSettings by remember { mutableStateOf(false) }
    var sequenceWidthText by remember { mutableStateOf("1920") }
    var sequenceHeightText by remember { mutableStateOf("1080") }
    var sequenceFpsText by remember { mutableStateOf("30") }
    var sequenceStartText by remember { mutableStateOf("0") }
    var sequenceEndText by remember { mutableStateOf("0") }
    var sequenceBitrateText by remember { mutableStateOf("8000000") }
    var sequenceOutputFormat by remember { mutableStateOf(RenderOutputFormat.PNG_SEQUENCE_ZIP) }
    var sequenceSettingsError by remember { mutableStateOf<String?>(null) }
    // Hardware HEVC probe for the H.265 opt-in: null while unprobed, true
    // only when a hardware HEVC encoder exists. Probed once, when the
    // export settings dialog first opens.
    var hevcAvailable by remember { mutableStateOf<Boolean?>(null) }
    val openSequenceSettings: (RenderOutputFormat) -> Unit = { format ->
        sequenceOutputFormat = format
        sequenceBitrateText = "8000000"
        val composition = canonicalProject?.compositions?.firstOrNull()
        if (composition == null) {
            sequenceSettingsError = "Import a canonical composition first"
        } else {
            sequenceWidthText = composition.width.toString()
            sequenceHeightText = composition.height.toString()
            sequenceFpsText = (composition.frameRateNum.toDouble() / composition.frameRateDen.coerceAtLeast(1)).roundToInt().toString()
            sequenceStartText = "0"
            sequenceEndText = (composition.durationFrames - 1).toString()
            sequenceSettingsError = null
            showSequenceSettings = true
        }
    }

    // The workspace viewer renders through the shared native session for the
    // imported canonical project (onRenderCanonicalFrame is session-backed).
    // The bootstrap .efx-draft has no session and is explicitly labelled as
    // unrendered rather than showing a decorative fake composition.
    val previewComposition = canonicalProject?.compositions?.firstOrNull()
    var workspaceFrame by remember(previewComposition?.id) { mutableStateOf<Bitmap?>(null) }
    var showMotionPath by remember { mutableStateOf(true) }
    // The workspace overlay follows the selected canonical layer's position
    // track; it draws only when that track has a path to show (≥2 keys).
    val workspaceMotionPathKeys = previewComposition?.layers
        ?.firstOrNull { it.id in canonicalSelectedLayerIds }
        ?.transformCurves?.get("position").orEmpty()
    LaunchedEffect(canonicalProject, previewComposition?.id, playheadFrame, canEditCanonicalProject) {
        workspaceFrame = if (previewComposition != null && canEditCanonicalProject) {
            withContext(Dispatchers.Default) {
                onRenderCanonicalFrame(previewComposition.id, playheadFrame.toLong())?.let(::decodeRenderedFrame)
            }
        } else null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorPalette.background)
    ) {
        val isTabletLayout = maxWidth >= 760.dp

        Column(Modifier.fillMaxSize()) {
            HeaderBar(
                projectName = project.name,
                saveMessage = saveMessage,
                onSave = onSave,
                onOpenProject = { showProjectPicker = true },
                onOpenMediaLibrary = { showMediaLibrary = true },
                onValidateEcproj = onValidateEcproj,
                onExportEcproj = onExportEcproj,
                onExportEcprojx = onExportEcprojx,
                isExportingFrame = isExportingFrame,
                onExportRenderedFrame = onExportRenderedFrame,
                onCancelFrameExport = onCancelFrameExport,
                isExportingSequence = isExportingSequence,
                isSequenceExportWritingOutput = isSequenceExportWritingOutput,
                onExportPngSequence = { openSequenceSettings(RenderOutputFormat.PNG_SEQUENCE_ZIP) },
                onCancelSequenceExport = onCancelSequenceExport,
                sequenceExportProgress = sequenceExportProgress,
                isExportingMp4 = isExportingMp4,
                isMp4ExportWritingOutput = isMp4ExportWritingOutput,
                onExportMp4Sequence = {
                    openSequenceSettings(RenderOutputFormat.MP4_H264)
                    onOpenMp4Settings()
                },
                onCancelMp4Sequence = onCancelMp4Sequence,
                mp4ExportProgress = mp4ExportProgress,
                onNewProject = onNewProject,
                hasCanonicalProject = canonicalProject != null,
                onOpenCanonicalEditor = { showCanonicalEditor = true },
                onOpenRepositoryPicker = { showRepositoryPicker = true }
            )

            if (isTabletLayout) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PreviewStage(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        bitmap = workspaceFrame,
                        compositionName = previewComposition?.name,
                        motionPathKeys = workspaceMotionPathKeys,
                        compositionWidth = previewComposition?.width ?: 0,
                        compositionHeight = previewComposition?.height ?: 0,
                        showMotionPath = showMotionPath,
                        currentFrame = playheadFrame
                    )
                    InspectorPanel(
                        modifier = Modifier
                            .width(276.dp)
                            .fillMaxHeight(),
                        layer = selectedLayer,
                        onOpacityChange = onOpacityChange,
                        onDeleteLayer = handleDeleteLayer,
                        onMoveLayer = onMoveLayer,
                        onVisibilityChange = onVisibilityChange,
                        onRenameLayer = onRenameLayer
                    )
                }
            } else {
                PreviewStage(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    bitmap = workspaceFrame,
                    compositionName = previewComposition?.name,
                    motionPathKeys = workspaceMotionPathKeys,
                    compositionWidth = previewComposition?.width ?: 0,
                    compositionHeight = previewComposition?.height ?: 0,
                    showMotionPath = showMotionPath,
                    currentFrame = playheadFrame
                )
            }

            ToolBar(
                selectedTool = selectedTool.value,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = onUndo,
                onRedo = onRedo,
                onSelectTool = { selectedTool.value = it },
                onOpenInspector = { showPhoneInspector = true },
                onOpenCompositionSettings = { showCompositionSettings = true },
                onAddLayer = onAddLayer
            )

            TimelinePanel(
                project = project,
                selectedLayerId = selectedLayerId,
                playheadFrame = playheadFrame,
                selectedOpacityKeyframes = selectedOpacityKeyframes,
                canonicalTransformKeyframes = canonicalTransformKeyframes,
                canonicalTransformDurationFrames = canonicalTransformDurationFrames,
                canonicalColorKeys = canonicalColorKeys,
                canonicalColorDurationFrames = canonicalColorDurationFrames,
                isPlaying = isPlaying,
                onSelectLayer = onSelectLayer,
                onScrub = onScrub,
                onMoveCanonicalTransformKeyframe = onMoveCanonicalTransformKeyframe,
                onAddOpacityKeyframe = onAddOpacityKeyframe,
                onRemoveOpacityKeyframe = onRemoveOpacityKeyframe,
                onTogglePlayback = onTogglePlayback,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTabletLayout) 222.dp else 204.dp)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            )
        }

        if (showProjectPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showProjectPicker = false },
                title = { Text("Open offline project") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (savedProjects.isEmpty()) {
                            Text("No saved drafts yet. Save a composition to open it later.", color = EditorPalette.muted)
                        } else {
                            savedProjects.forEach { saved ->
                                TextButton(
                                    onClick = {
                                        showProjectPicker = false
                                        onOpenProject(saved.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(saved.name, color = EditorPalette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${saved.width}×${saved.height} · ${saved.frameRate} FPS · ${saved.layers.size} layers",
                                            color = EditorPalette.muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProjectPicker = false }) { Text("Close") }
                }
            )
        }

        if (showRepositoryPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRepositoryPicker = false },
                title = { Text("Open canonical project") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (repositoryProjects.isEmpty()) {
                            Text("No canonical projects saved yet. Import a .ecproj or .ecprojx to add one.", color = EditorPalette.muted)
                        } else {
                            repositoryProjects.forEach { entry ->
                                TextButton(
                                    onClick = {
                                        showRepositoryPicker = false
                                        onOpenRepositoryProject(entry.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(entry.displayName, color = EditorPalette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${entry.format} · schema v${entry.schemaVersion}" + if (entry.id == activeRepositoryProjectId) " · Current" else "",
                                            color = EditorPalette.muted, fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showRepositoryPicker = false }) { Text("Close") }
                }
            )
        }

        if (showMediaLibrary) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showMediaLibrary = false },
                title = { Text("Offline media library") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        if (missingManifestEntries > 0) {
                            Text(
                                "$missingManifestEntries linked item${if (missingManifestEntries == 1) "" else "s"} ${if (missingManifestEntries == 1) "is" else "are"} missing media. Relink ${if (missingManifestEntries == 1) "it" else "them"} from the canonical project sheet.",
                                color = EditorPalette.amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        if (offlineAssets.isEmpty()) {
                            Text("No media imported yet. Relink footage from a canonical project to copy it here for offline use.", color = EditorPalette.muted)
                        } else {
                            if (offlineAssets.any { it.sha256 !in referencedOfflineAssetIds }) {
                                TextButton(onClick = { showMediaLibrary = false; onCollectUnusedOfflineAssets() }) {
                                    Text("Remove unused media", color = EditorPalette.violet, fontSize = 11.sp)
                                }
                            }
                            offlineAssets.forEach { asset ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(asset.displayName, color = EditorPalette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${asset.extension.uppercase()} · ${asset.sizeBytes / 1024} KB · ${asset.sha256.take(12)}…",
                                            color = EditorPalette.muted, fontSize = 10.sp)
                                    }
                                    if (asset.sha256 in referencedOfflineAssetIds) {
                                        Text("Linked", color = EditorPalette.mint, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                        TextButton(onClick = { showMediaLibrary = false; onReplaceOfflineAsset(asset.sha256) }) {
                                            Text("Replace", color = EditorPalette.violet, fontSize = 11.sp)
                                        }
                                    } else {
                                        TextButton(onClick = { assetPendingDelete = asset }) {
                                            Text("Remove", color = Color(0xFFFF8B91), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Text("Storage access", color = EditorPalette.text, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        if (safGrants.isEmpty()) {
                            Text("No persisted document access recorded yet. Importing or exporting a document records its access here.", color = EditorPalette.muted, fontSize = 11.sp)
                        } else {
                            safGrants.forEach { grant ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(grant.displayName, color = EditorPalette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                        Text(
                                            "${grant.kind.name} · ${if (grant.status == SafPermissionRegistry.Status.LOST) "LOST — re-open the document to restore it" else "Granted"}",
                                            color = if (grant.status == SafPermissionRegistry.Status.LOST) EditorPalette.amber else EditorPalette.muted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    TextButton(onClick = { onReleaseSafGrant(grant.uri, grant.kind) }) {
                                        Text("Release", color = Color(0xFFFF8B91), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showMediaLibrary = false }) { Text("Close") } }
            )
        }

        if (assetPendingDelete != null) {
            val asset = assetPendingDelete!!
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { assetPendingDelete = null },
                title = { Text("Remove offline media?") },
                text = { Text(if (asset.sha256 in referencedOfflineAssetIds) "This media is linked by a project and cannot be removed yet." else "Delete ${asset.displayName} from this device?") },
                confirmButton = {
                    TextButton(onClick = {
                        if (asset.sha256 !in referencedOfflineAssetIds) onDeleteOfflineAsset(asset.sha256)
                        assetPendingDelete = null
                    }, enabled = asset.sha256 !in referencedOfflineAssetIds) { Text("Delete", color = Color(0xFFFF8B91)) }
                },
                dismissButton = { TextButton(onClick = { assetPendingDelete = null }) { Text("Cancel") } }
            )
        }

        if (showSequenceSettings) {
            val composition = canonicalProject?.compositions?.firstOrNull()
            val isMp4 = sequenceOutputFormat == RenderOutputFormat.MP4_H264 ||
                sequenceOutputFormat == RenderOutputFormat.MP4_HEVC
            LaunchedEffect(showSequenceSettings) {
                if (hevcAvailable == null) {
                    hevcAvailable = withContext(Dispatchers.Default) {
                        HevcEncoderProbe.probe().codecName != null
                    }
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSequenceSettings = false },
                title = {
                    Text(
                        when (sequenceOutputFormat) {
                            RenderOutputFormat.MP4_H264 -> "H.264 MP4 settings"
                            RenderOutputFormat.MP4_HEVC -> "H.265 MP4 settings"
                            else -> "PNG sequence settings"
                        }
                    )
                },
                text = {
                    Column(
                        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            composition?.let { "${it.name} · ${it.durationFrames} frames" } ?: "Canonical composition",
                            color = EditorPalette.muted,
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { sequenceOutputFormat = RenderOutputFormat.PNG_SEQUENCE_ZIP }) { Text("PNG ZIP") }
                            TextButton(onClick = { sequenceOutputFormat = RenderOutputFormat.MP4_H264 }) { Text("MP4 H.264") }
                            TextButton(
                                onClick = { sequenceOutputFormat = RenderOutputFormat.MP4_HEVC },
                                enabled = hevcAvailable == true
                            ) { Text("MP4 H.265") }
                        }
                        if (hevcAvailable == null) {
                            Text("Checking for a hardware HEVC encoder…", color = EditorPalette.muted, fontSize = 11.sp)
                        } else if (hevcAvailable == false) {
                            Text("H.265 needs a hardware HEVC encoder — this device has none.", color = EditorPalette.muted, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sequenceWidthText, onValueChange = { sequenceWidthText = it.filter(Char::isDigit) },
                                label = { Text("Width") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sequenceHeightText, onValueChange = { sequenceHeightText = it.filter(Char::isDigit) },
                                label = { Text("Height") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = sequenceFpsText, onValueChange = { sequenceFpsText = it.filter(Char::isDigit) },
                            label = { Text("FPS") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Export FPS" }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sequenceStartText, onValueChange = { sequenceStartText = it.filter(Char::isDigit) },
                                label = { Text("Start frame") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sequenceEndText, onValueChange = { sequenceEndText = it.filter(Char::isDigit) },
                                label = { Text("End frame") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                        if (isMp4) {
                            OutlinedTextField(
                                value = sequenceBitrateText,
                                onValueChange = { sequenceBitrateText = it.filter(Char::isDigit) },
                                label = { Text("Bitrate (bps)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            when (sequenceOutputFormat) {
                                RenderOutputFormat.MP4_H264 -> "H.264 MP4 uses the device MediaCodec encoder; AAC audio from the composition is passed through when available, otherwise the export is video-only and the result says so."
                                RenderOutputFormat.MP4_HEVC -> "H.265 MP4 uses the device hardware HEVC encoder; AAC audio from the composition is passed through when available, otherwise the export is video-only and the result says so."
                                else -> "PNG frames are rendered at the selected dimensions; the ZIP contains no video/audio track."
                            },
                            color = EditorPalette.muted, fontSize = 11.sp
                        )
                        sequenceSettingsError?.let { Text(it, color = EditorPalette.amber, fontSize = 12.sp) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val width = sequenceWidthText.toIntOrNull()
                        val height = sequenceHeightText.toIntOrNull()
                        val fps = sequenceFpsText.toIntOrNull()
                        val start = sequenceStartText.toIntOrNull()
                        val end = sequenceEndText.toIntOrNull()
                        val bitrate = sequenceBitrateText.toIntOrNull() ?: 8_000_000
                        if (width == null || height == null || fps == null || start == null || end == null) {
                            sequenceSettingsError = "Enter valid numeric settings"
                        } else {
                            val settings = RenderQueueSettings(width, height, fps, start, end, sequenceOutputFormat, bitrate)
                            runCatching { RenderQueuePlan.create(settings, composition?.durationFrames ?: end + 1) }
                                .onSuccess {
                                    showSequenceSettings = false
                                    if (isMp4)
                                        onExportMp4Sequence(width, height, fps, start, end, bitrate, sequenceOutputFormat)
                                    else onExportPngSequence(width, height, fps, start, end)
                                }
                                .onFailure { sequenceSettingsError = it.message ?: "Render settings are invalid" }
                        }
                    }) { Text(if (isMp4) "Render MP4" else "Render ZIP") }
                },
                dismissButton = {
                    TextButton(onClick = { showSequenceSettings = false }) { Text("Cancel") }
                }
            )
        }

        if (showCompositionSettings) {
            ModalBottomSheet(
                onDismissRequest = { showCompositionSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = EditorPalette.background,
                contentColor = EditorPalette.text
            ) {
                CompositionSettingsPanel(
                    project = project,
                    onApply = { width, height, fps, duration ->
                        onApplyComposition(width, height, fps, duration)
                        showCompositionSettings = false
                    },
                    onCancel = { showCompositionSettings = false },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (showCanonicalEditor && canonicalProject != null) {
            ModalBottomSheet(
                onDismissRequest = { showCanonicalEditor = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = EditorPalette.background,
                contentColor = EditorPalette.text
            ) {
                CanonicalProjectEditor(
                    project = canonicalProject,
                    canEdit = canEditCanonicalProject,
                    selectedLayerIds = canonicalSelectedLayerIds,
                    onSelectionChange = onCanonicalSelectionChange,
                    canUndoCanonical = canUndoCanonical,
                    canRedoCanonical = canRedoCanonical,
                    onUndoCanonical = onUndoCanonical,
                    onRedoCanonical = onRedoCanonical,
                    onSetLayerOpacityForSelection = onSetCanonicalLayerOpacityForSelection,
                    onSetLayerVisibilityForSelection = onSetCanonicalLayerVisibilityForSelection,
                    onGestureEditSelection = onGestureEditCanonicalSelection,
                    onRenameLayer = onRenameCanonicalLayer,
                    onAddTextLayer = onAddCanonicalTextLayer,
                    onSetLayerVisibility = onSetCanonicalLayerVisibility,
                    onMoveLayer = onMoveCanonicalLayer,
                    onDeleteLayer = onDeleteCanonicalLayer,
                    onSetLayerOpacity = onSetCanonicalLayerOpacity,
                    onAddLayerOpacityKeyframe = onAddCanonicalLayerOpacityKeyframe,
                    onAddLayerTransformKeyframe = onAddCanonicalLayerTransformKeyframe,
                    onRemoveLayerTransformKeyframe = onRemoveCanonicalLayerTransformKeyframe,
                    interpolationProperty = interpolationProperty,
                    onInterpolationPropertyChange = { interpolationProperty = it },
                    interpolationSide = interpolationSide,
                    onInterpolationSideChange = { interpolationSide = it },
                    onSetLayerTransformInterpolation = onSetCanonicalLayerTransformInterpolation,
                    onSetCanonicalKeyframeRoving = onSetCanonicalKeyframeRoving,
                    onSetCanonicalKeyframeSpatialTangents = onSetCanonicalKeyframeSpatialTangents,
                    showMotionPath = showMotionPath,
                    onShowMotionPathChange = { showMotionPath = it },
                    onMoveCanonicalCurveKey = onMoveCanonicalCurveKey,
                    onSetCanonicalCurveHandle = onSetCanonicalCurveHandle,
                    onAddCanonicalCurveKey = onAddCanonicalCurveKey,
                    onRemoveCanonicalCurveKey = onRemoveCanonicalCurveKey,
                    onRemoveLayerOpacityKeyframe = onRemoveCanonicalLayerOpacityKeyframe,
                    onSetLayerPosition = onSetCanonicalLayerPosition,
                    onSetLayerScale = onSetCanonicalLayerScale,
                    onSetLayerRotation = onSetCanonicalLayerRotation,
                    onSetLayerAnchor = onSetCanonicalLayerAnchor,
                    onSetLayerOrientation = onSetCanonicalLayerOrientation,
                    onSetLayerRotationX = onSetCanonicalLayerRotationX,
                    onSetLayerRotationY = onSetCanonicalLayerRotationY,
                    onSetLayerLocked = onSetCanonicalLayerLocked,
                    onSetTextLayerText = onSetCanonicalTextLayerText,
                    onSetTextLayerTextWholeLayer = onSetCanonicalTextLayerTextWholeLayer,
                    onSetCanonicalTextLayerKeyframe = onSetCanonicalTextLayerKeyframe,
                    onSetTextLayerSize = onSetCanonicalTextLayerSize,
                    onSetTextLayerTracking = onSetCanonicalTextLayerTracking,
                    onSetTextLayerFont = onSetCanonicalTextLayerFont,
                    onSetTextLayerFontKeyframe = onSetCanonicalTextLayerFontKeyframe,
                    onSetTextLayerTrackingKeyframe = onSetCanonicalTextLayerTrackingKeyframe,
                    onSetLayerScalarProperty = onSetCanonicalLayerScalarProperty,
                    onSetLayerVecProperty = onSetCanonicalLayerVecProperty,
                    onSetLayerColorProperty = onSetCanonicalLayerColorProperty,
                    onSetLayerColorKeyframe = onSetCanonicalLayerColorKeyframe,
                    onSetLayerGradientProperty = onSetCanonicalLayerGradientProperty,
                    onSetLayerPathProperty = onSetCanonicalLayerPathProperty,
                    onSetLayerPathKeyframe = onSetCanonicalLayerPathKeyframe,
                    onSetLayerReferenceProperty = onSetCanonicalLayerReferenceProperty,
                    onSetLayerReferenceKeyframe = onSetCanonicalLayerReferenceKeyframe,
                    onSetLayerBoolProperty = onSetCanonicalLayerBoolProperty,
                    onSetLayerPopupProperty = onSetCanonicalLayerPopupProperty,
                    onSetLayerBoolKeyframe = onSetCanonicalLayerBoolKeyframe,
                    onSetLayerPopupKeyframe = onSetCanonicalLayerPopupKeyframe,
                    onRelinkAsset = onRelinkCanonicalAsset,
                    onRenderFrame = onRenderCanonicalFrame,
                    onQueryBackendStatus = onQueryBackendStatus,
                    onQueryMediaCacheStatus = onQueryMediaCacheStatus,
                    playheadFrame = playheadFrame,
                    onPreviewFrameChange = onScrub,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (showPhoneInspector && !isTabletLayout) {
            ModalBottomSheet(
                onDismissRequest = { showPhoneInspector = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = EditorPalette.background,
                contentColor = EditorPalette.text
            ) {
                InspectorPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 560.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    layer = selectedLayer,
                    onOpacityChange = onOpacityChange,
                    onDeleteLayer = handleDeleteLayer,
                    onMoveLayer = onMoveLayer,
                    onVisibilityChange = onVisibilityChange,
                    onRenameLayer = onRenameLayer
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(
    projectName: String,
    saveMessage: String,
    onSave: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenMediaLibrary: () -> Unit,
    onValidateEcproj: () -> Unit,
    onExportEcproj: () -> Unit,
    onExportEcprojx: () -> Unit,
    isExportingFrame: Boolean,
    onExportRenderedFrame: () -> Unit,
    onCancelFrameExport: () -> Unit,
    isExportingSequence: Boolean,
    isSequenceExportWritingOutput: Boolean,
    onExportPngSequence: () -> Unit,
    onCancelSequenceExport: () -> Unit,
    sequenceExportProgress: Int,
    isExportingMp4: Boolean = false,
    isMp4ExportWritingOutput: Boolean = false,
    onExportMp4Sequence: () -> Unit = {},
    onCancelMp4Sequence: () -> Unit = {},
    mp4ExportProgress: Int = 0,
    onNewProject: () -> Unit,
    hasCanonicalProject: Boolean,
    onOpenCanonicalEditor: () -> Unit,
    onOpenRepositoryPicker: () -> Unit = {}
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        // The single-row desktop header needs ~840dp to fit its action bar
        // (logo, OFFLINE chip, nine icon buttons, three text buttons) without
        // squeezing the status column to zero width — on a 731dp landscape
        // phone the old 620dp threshold chose it and the status text was
        // measured to zero and never displayed. Narrower windows use the
        // two-row header, where the status has its own full-width row.
        if (maxWidth < 840.dp) {
            // Two compact rows prevent the project status from being squeezed to zero
            // width by the desktop-style action bar on phones.
            Column(Modifier.fillMaxWidth().height(88.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                            .background(Brush.linearGradient(listOf(EditorPalette.violet, Color(0xFF6851C7)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("E", color = Color(0xFF171326), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("EFFECTCRAFT", color = EditorPalette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text(projectName, color = EditorPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(
                        color = Color(0xFF13231F),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF24453E))
                    ) {
                        Text("OFFLINE", modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            color = EditorPalette.mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    }
                    IconButton(onClick = onSave, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save draft", tint = EditorPalette.text)
                    }
                    TextButton(onClick = onNewProject) {
                        Text("New", color = EditorPalette.violet, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        saveMessage,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Editor status: $saveMessage" },
                        color = EditorPalette.mint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onOpenProject, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Open offline project", tint = EditorPalette.mint)
                    }
                    IconButton(onClick = onOpenMediaLibrary, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Offline media library", tint = EditorPalette.violet)
                    }
                    IconButton(onClick = onValidateEcproj, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Validate .ecproj", tint = EditorPalette.text)
                    }
                    IconButton(onClick = onOpenCanonicalEditor, enabled = hasCanonicalProject, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit imported .ecproj", tint = if (hasCanonicalProject) EditorPalette.mint else EditorPalette.muted)
                    }
                    IconButton(onClick = onExportEcproj, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecproj", tint = EditorPalette.text)
                    }
                    IconButton(onClick = onExportEcprojx, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecprojx XML", tint = EditorPalette.mint)
                    }
                    IconButton(
                        onClick = if (isExportingFrame) onCancelFrameExport else onExportRenderedFrame,
                        enabled = isExportingFrame || hasCanonicalProject,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isExportingFrame) Icons.Outlined.Pause else Icons.Outlined.FileDownload,
                            contentDescription = if (isExportingFrame) "Cancel frame export" else "Export rendered frame PNG",
                            tint = if (isExportingFrame) EditorPalette.amber else EditorPalette.violet
                        )
                    }
                    IconButton(
                        onClick = if (isExportingSequence && !isSequenceExportWritingOutput) onCancelSequenceExport else onExportPngSequence,
                        enabled = if (isExportingSequence) !isSequenceExportWritingOutput else hasCanonicalProject && !isExportingFrame,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isExportingSequence) Icons.Outlined.Pause else Icons.Outlined.FileDownload,
                            contentDescription = when {
                                isExportingSequence && isSequenceExportWritingOutput -> "Writing PNG sequence ZIP · $sequenceExportProgress%"
                                isExportingSequence -> "Cancel PNG sequence export · $sequenceExportProgress%"
                                else -> "Export PNG sequence ZIP"
                            },
                            tint = if (isExportingSequence) EditorPalette.amber else EditorPalette.mint
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(68.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(EditorPalette.violet, Color(0xFF6851C7)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("E", color = Color(0xFF171326), fontWeight = FontWeight.Black, fontSize = 21.sp)
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("EFFECTCRAFT", color = EditorPalette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(projectName, color = EditorPalette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("  ·  ", color = EditorPalette.border, fontSize = 12.sp)
                        Text(
                            saveMessage,
                            modifier = Modifier.semantics { contentDescription = "Editor status: $saveMessage" },
                            color = EditorPalette.mint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    color = Color(0xFF13231F), shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF24453E))
                ) {
                    Text("OFFLINE", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        color = EditorPalette.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                IconButton(onClick = onOpenMediaLibrary) { Icon(Icons.Outlined.FolderOpen, contentDescription = "Offline media library", tint = EditorPalette.violet) }
                IconButton(onClick = onValidateEcproj) { Icon(Icons.Outlined.FolderOpen, contentDescription = "Validate .ecproj", tint = EditorPalette.text) }
                IconButton(onClick = onOpenCanonicalEditor, enabled = hasCanonicalProject) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit imported .ecproj", tint = if (hasCanonicalProject) EditorPalette.mint else EditorPalette.muted)
                }
                IconButton(onClick = onExportEcproj) { Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecproj", tint = EditorPalette.text) }
                IconButton(onClick = onExportEcprojx) { Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecprojx XML", tint = EditorPalette.mint) }
                IconButton(
                    onClick = if (isExportingFrame) onCancelFrameExport else onExportRenderedFrame,
                    enabled = isExportingFrame || hasCanonicalProject
                ) {
                    Icon(
                        if (isExportingFrame) Icons.Outlined.Pause else Icons.Outlined.FileDownload,
                        contentDescription = if (isExportingFrame) "Cancel frame export" else "Export rendered frame PNG",
                        tint = if (isExportingFrame) EditorPalette.amber else EditorPalette.violet
                    )
                }
                IconButton(
                    onClick = if (isExportingSequence && !isSequenceExportWritingOutput) onCancelSequenceExport else onExportPngSequence,
                    enabled = if (isExportingSequence) !isSequenceExportWritingOutput else hasCanonicalProject && !isExportingFrame,
                ) {
                    Icon(
                        if (isExportingSequence) Icons.Outlined.Pause else Icons.Outlined.FileDownload,
                        contentDescription = when {
                            isExportingSequence && isSequenceExportWritingOutput -> "Writing PNG sequence ZIP · $sequenceExportProgress%"
                            isExportingSequence -> "Cancel PNG sequence export · $sequenceExportProgress%"
                            else -> "Export PNG sequence ZIP"
                        },
                        tint = if (isExportingSequence) EditorPalette.amber else EditorPalette.mint
                    )
                }
                IconButton(onClick = onSave) { Icon(Icons.Outlined.Save, contentDescription = "Save draft", tint = EditorPalette.text) }
                IconButton(
                    onClick = if (isExportingMp4 && !isMp4ExportWritingOutput) onCancelMp4Sequence else onExportMp4Sequence,
                    enabled = if (isExportingMp4) !isMp4ExportWritingOutput else hasCanonicalProject && !isExportingFrame && !isExportingSequence
                ) {
                    Icon(
                        if (isExportingMp4) Icons.Outlined.Pause else Icons.Outlined.FileDownload,
                        contentDescription = when {
                            isExportingMp4 && isMp4ExportWritingOutput -> "Writing MP4 · $mp4ExportProgress%"
                            isExportingMp4 -> "Cancel MP4 export · $mp4ExportProgress%"
                            else -> "Export H.264 MP4"
                        },
                        tint = if (isExportingMp4) EditorPalette.amber else EditorPalette.violet
                    )
                }
                TextButton(onClick = onOpenProject) { Text("Open", color = EditorPalette.mint, fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = onOpenRepositoryPicker) { Text("Projects", color = EditorPalette.mint, fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = onNewProject) { Text("New", color = EditorPalette.violet, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun MotionPathOverlay(
    keys: List<CanonicalCurveKey>,
    compositionWidth: Int,
    compositionHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
    currentFrame: Int = -1,
    modifier: Modifier = Modifier
) {
    if (keys.size < 2 || compositionWidth <= 0 || compositionHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
    val path = remember(keys) { MotionPath.samplePath(keys) }
    Box(modifier = modifier.fillMaxSize().semantics { contentDescription = "Motion path overlay" }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
        // The rendered frame is comp-sized, so ContentScale.Fit maps comp
        // coordinates onto the drawn rect with one uniform scale; the rect
        // is centred when the box letterboxes the frame.
        val scale = minOf(size.width / bitmapWidth.toFloat(), size.height / bitmapHeight.toFloat())
        val drawWidth = bitmapWidth * scale
        val drawHeight = bitmapHeight * scale
        val offsetX = (size.width - drawWidth) / 2f
        val offsetY = (size.height - drawHeight) / 2f
        val scaleX = drawWidth / compositionWidth.toFloat()
        val scaleY = drawHeight / compositionHeight.toFloat()
        fun point(x: Double, y: Double) = Offset(offsetX + x.toFloat() * scaleX, offsetY + y.toFloat() * scaleY)
        val points = path.map { point(it.x, it.y) }
        for (i in 0 until points.size - 1) {
            drawLine(EditorPalette.mint, points[i], points[i + 1], strokeWidth = 2f)
        }
        keys.forEach { key ->
            val centre = point(key.components.getOrElse(0) { 0.0 }, key.components.getOrElse(1) { 0.0 })
            if (!key.spatialAuto) {
                // Manual tangents: draw the handle lines the renderer bends
                // the path with, in amber, with a dot at each handle end.
                val inEnd = point(
                    key.components.getOrElse(0) { 0.0 } + key.spatialIn.getOrElse(0) { 0.0 },
                    key.components.getOrElse(1) { 0.0 } + key.spatialIn.getOrElse(1) { 0.0 }
                )
                val outEnd = point(
                    key.components.getOrElse(0) { 0.0 } + key.spatialOut.getOrElse(0) { 0.0 },
                    key.components.getOrElse(1) { 0.0 } + key.spatialOut.getOrElse(1) { 0.0 }
                )
                drawLine(EditorPalette.amber, centre, inEnd, strokeWidth = 1.5f)
                drawLine(EditorPalette.amber, centre, outEnd, strokeWidth = 1.5f)
                drawCircle(EditorPalette.amber, radius = 3f, center = inEnd)
                drawCircle(EditorPalette.amber, radius = 3f, center = outEnd)
            }
            val isCurrent = key.frame == currentFrame
            drawCircle(if (isCurrent) EditorPalette.violet else EditorPalette.mint, radius = if (isCurrent) 6f else 5f, center = centre)
        }
        }
    }
}

@Composable
private fun PreviewStage(
    modifier: Modifier = Modifier,
    bitmap: Bitmap?,
    compositionName: String?,
    motionPathKeys: List<CanonicalCurveKey> = emptyList(),
    compositionWidth: Int = 0,
    compositionHeight: Int = 0,
    showMotionPath: Boolean = true,
    currentFrame: Int = -1
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(EditorPalette.panel)
            .border(1.dp, EditorPalette.border, RoundedCornerShape(20.dp))
    ) {
        Text(
            if (compositionName != null) "COMPOSITION VIEWER · $compositionName" else "COMPOSITION VIEWER · OFFLINE DRAFT",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 15.dp, top = 12.dp),
            color = EditorPalette.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(top = 32.dp, bottom = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                // Keep the Image semantics node inside the visible preview
                // viewport even when the composition is letterboxed. Image
                // ContentScale.Fit and MotionPathOverlay use the same contain
                // geometry, so the frame is never height-first oversized.
                // Put the preview's accessible node on a full-viewport layout
                // container, not on Image's intrinsic/drawn bounds. ContentScale.Fit
                // still controls pixels; the semantics bounds remain stable when
                // the frame is letterboxed or the preview is resized.
                Box(modifier = Modifier.fillMaxSize()) {
                    // Expose the actual drawable Image bounds to accessibility and
                    // Compose tests. A semantics label on a full-size wrapper can
                    // report a viewport that is not itself painted/displayed on
                    // constrained preview layouts, causing false visibility
                    // failures while the native frame is already decoded.
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Rendered composition frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (showMotionPath) {
                        MotionPathOverlay(
                            keys = motionPathKeys,
                            compositionWidth = compositionWidth,
                            compositionHeight = compositionHeight,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            currentFrame = currentFrame,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }            } else {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (compositionName == null) "Draft preview not rendered" else "Native frame unavailable",
                        color = EditorPalette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (compositionName == null) "Import a supported .ecproj to see the actual composition here. Draft editing remains offline."
                        else "The project is loaded, but its native renderer did not return a frame for this ABI.",
                        color = EditorPalette.muted,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorPanel(
    modifier: Modifier,
    layer: LayerDocument?,
    onOpacityChange: (Float) -> Unit,
    onDeleteLayer: () -> Unit,
    onMoveLayer: (Int) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onRenameLayer: (String) -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(EditorPalette.panel)
            .border(1.dp, EditorPalette.border, RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = EditorPalette.violet, modifier = Modifier.size(18.dp))
            Text(
                "PROPERTIES",
                modifier = Modifier.padding(start = 8.dp),
                color = EditorPalette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
        }

        Spacer(Modifier.height(23.dp))
        Text("SELECTED LAYER", color = EditorPalette.muted, fontSize = 9.sp, letterSpacing = 1.2.sp)

        if (layer == null) {
            Text("No layer selected", color = EditorPalette.muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            var layerNameDraft by remember(layer.id, layer.name) { mutableStateOf(layer.name) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = layerNameDraft,
                    onValueChange = { layerNameDraft = it.take(120) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = EditorPalette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Layer name" },
                    decorationBox = { innerTextField ->
                        Box {
                            if (layerNameDraft.isEmpty()) {
                                Text("Layer name", color = EditorPalette.muted, fontSize = 13.sp)
                            }
                            innerTextField()
                        }
                    }
                )
                TextButton(
                    onClick = { onRenameLayer(layerNameDraft) },
                    enabled = layerNameDraft.trim().isNotEmpty() && layerNameDraft.trim() != layer.name
                ) {
                    Text("Apply", color = EditorPalette.violet, fontSize = 11.sp)
                }
            }
            Text(layer.kind.uppercase(Locale.US), color = EditorPalette.violet, fontSize = 9.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Visible", color = EditorPalette.text, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = layer.visible, onCheckedChange = onVisibilityChange, modifier = Modifier.semantics { contentDescription = "Layer visibility" })
            }
            Spacer(Modifier.height(12.dp))
            PropertyLine("Transform", "›")
            PropertyValueLine("Position", "540, 960")
            PropertyValueLine("Scale", "100%")
            Spacer(Modifier.height(18.dp))
            PropertyLine("Appearance", "⌄")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", color = EditorPalette.text, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${(layer.opacity * 100).roundToInt()}%", color = EditorPalette.muted, fontSize = 11.sp)
            }
            Slider(
                value = layer.opacity,
                onValueChange = onOpacityChange,
                valueRange = 0f..1f,
                modifier = Modifier.padding(top = 3.dp).semantics { contentDescription = "Layer opacity" }
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onMoveLayer(-1) }, modifier = Modifier.semantics { contentDescription = "Move selected layer up" }) {
                    Text("Move ↑", color = EditorPalette.muted, fontSize = 11.sp)
                }
                TextButton(onClick = { onMoveLayer(1) }, modifier = Modifier.semantics { contentDescription = "Move selected layer down" }) {
                    Text("Move ↓", color = EditorPalette.muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDeleteLayer, modifier = Modifier.semantics { contentDescription = "Delete selected layer" }) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFFF858D))
                Text("  Remove layer", color = Color(0xFFFF858D), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "INSPECTOR",
            color = EditorPalette.muted.copy(alpha = 0.65f),
            fontSize = 9.sp,
            letterSpacing = 1.5.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanonicalProjectEditor(
    project: CanonicalProjectInfo,
    canEdit: Boolean,
    playheadFrame: Int,
    onPreviewFrameChange: (Int) -> Unit,
    selectedLayerIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    canUndoCanonical: Boolean,
    canRedoCanonical: Boolean,
    onUndoCanonical: () -> Unit,
    onRedoCanonical: () -> Unit,
    onSetLayerOpacityForSelection: (Long, Set<Long>, Double) -> Unit,
    onSetLayerVisibilityForSelection: (Long, Set<Long>, Boolean) -> Unit,
    onGestureEditSelection: (Long, Map<Long, Pair<Double, Double>>, Map<Long, Pair<Double, Double>>, Map<Long, Double>) -> Unit,
    onRenameLayer: (Long, Long, String) -> Unit,
    onAddTextLayer: (Long, String) -> Unit,
    onSetLayerVisibility: (Long, Long, Boolean) -> Unit,
    onMoveLayer: (Long, Long, Int) -> Unit,
    onDeleteLayer: (Long, Long) -> Unit,
    onSetLayerOpacity: (Long, Long, Double) -> Unit,
    onAddLayerOpacityKeyframe: (Long, Long, Int, Double) -> Unit,
    onAddLayerTransformKeyframe: (Long, Long, String, Int, Double, Double, Double) -> Unit,
    onRemoveLayerTransformKeyframe: (Long, Long, String, Int) -> Unit,
    interpolationProperty: String = "position",
    onInterpolationPropertyChange: (String) -> Unit = {},
    interpolationSide: String = "both",
    onInterpolationSideChange: (String) -> Unit = {},
    onSetLayerTransformInterpolation: (Long, Long, String, Int, String) -> Unit = { _, _, _, _, _ -> },
    onSetCanonicalKeyframeRoving: (Long, Long, String, Int, Boolean) -> Unit = { _, _, _, _, _ -> },
    onSetCanonicalKeyframeSpatialTangents: (Long, Long, String, Int, Double, Double, Double, Double, Double, Double, Boolean) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    showMotionPath: Boolean = true,
    onShowMotionPathChange: (Boolean) -> Unit = {},
    onMoveCanonicalCurveKey: (Long, Long, String, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onSetCanonicalCurveHandle: (Long, Long, String, Int, String, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddCanonicalCurveKey: (Long, Long, String, Int, Double, Double, Double) -> Unit = { _, _, _, _, _, _, _ -> },
    onRemoveCanonicalCurveKey: (Long, Long, String, Int) -> Unit = { _, _, _, _ -> },
    onRemoveLayerOpacityKeyframe: (Long, Long, Int) -> Unit,
    onSetLayerPosition: (Long, Long, Double, Double) -> Unit,
    onSetLayerScale: (Long, Long, Double, Double) -> Unit,
    onSetLayerRotation: (Long, Long, Double) -> Unit,
    onSetLayerAnchor: (Long, Long, Double, Double) -> Unit,
    onSetLayerOrientation: (Long, Long, Double, Double, Double) -> Unit,
    onSetLayerRotationX: (Long, Long, Double) -> Unit,
    onSetLayerRotationY: (Long, Long, Double) -> Unit,
    onSetLayerLocked: (Long, Long, Boolean) -> Unit,
    onSetTextLayerText: (Long, Long, String) -> Unit,
    onSetTextLayerTextWholeLayer: (Long, Long, String) -> Unit = { _, _, _ -> },
    onSetCanonicalTextLayerKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetTextLayerSize: (Long, Long, Double) -> Unit,
    onSetTextLayerTracking: (Long, Long, Double) -> Unit,
    onSetTextLayerFont: (Long, Long, String) -> Unit = { _, _, _ -> },
    onSetTextLayerFontKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetTextLayerTrackingKeyframe: (Long, Long, Long, Int, Double, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetLayerScalarProperty: (Long, Long, Long, Double) -> Unit,
    onSetLayerVecProperty: (Long, Long, Long, Double, Double, Double) -> Unit,
    onSetLayerColorProperty: (Long, Long, Long, Double, Double, Double, Double) -> Unit,
    onSetLayerColorKeyframe: (Long, Long, Long, Int, Double, Double, Double, Double, Boolean) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onSetLayerGradientProperty: (Long, Long, Long, String) -> Unit = { _, _, _, _ -> },
    onSetLayerPathProperty: (Long, Long, Long, String) -> Unit = { _, _, _, _ -> },
    onSetLayerPathKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetLayerReferenceProperty: (Long, Long, Long, Long?) -> Unit = { _, _, _, _ -> },
    onSetLayerReferenceKeyframe: (Long, Long, Long, Int, Long?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetLayerBoolProperty: (Long, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onSetLayerPopupProperty: (Long, Long, Long, Int) -> Unit = { _, _, _, _ -> },
    onSetLayerBoolKeyframe: (Long, Long, Long, Int, Boolean, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onSetLayerPopupKeyframe: (Long, Long, Long, Int, Int, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onRelinkAsset: (Long) -> Unit,
    onRenderFrame: (Long, Long) -> ByteArray?,
    onQueryBackendStatus: (() -> String?)? = null,
    onQueryMediaCacheStatus: (() -> String?)? = null,
    modifier: Modifier = Modifier
) {
    var compositionId by remember { mutableStateOf<Long?>(null) }
    var newTextName by remember { mutableStateOf("Text") }
    val composition = project.compositions.firstOrNull { it.id == compositionId } ?: project.compositions.firstOrNull()
    // Selection parity: the highlighted list rows, the inspector fields and
    // the layers an edit targets are all this one resolved selection. The
    // inspector shows the first selected layer; opacity, visibility and
    // gesture edits apply to the whole selected set. When a re-parse drops
    // a selected id (for example after a delete), selection keeps the
    // survivors and falls back to the first remaining layer when none
    // survive, instead of pointing at stale data.
    val selectedLayers = composition?.let { com.codeair.effectcraft.data.CanonicalLayerSelection.resolveAll(it.layers, selectedLayerIds) } ?: emptyList()
    // The ids actually shown as selected: when the parent selection is
    // empty, resolveAll falls back to the first layer, so the marker must
    // follow the resolved selection, not the raw empty set.
    val effectiveSelectedIds = selectedLayers.map { it.id }.toSet()
    val layer = selectedLayers.firstOrNull()
    LaunchedEffect(composition?.id, composition?.layers?.map { it.id }) {
        val current = composition?.layers ?: emptyList()
        val pruned = com.codeair.effectcraft.data.CanonicalLayerSelection.pruneSelection(selectedLayerIds, current)
        val next = if (pruned.isNotEmpty()) pruned
            else current.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
        if (next != selectedLayerIds) onSelectionChange(next)
    }
    var nameDraft by remember(composition?.id, layer?.id, layer?.name) { mutableStateOf(layer?.name.orEmpty()) }
    var opacityDraft by remember(composition?.id, layer?.id, layer?.opacityPercent) { mutableFloatStateOf((layer?.opacityPercent ?: 100.0).toFloat()) }
    var positionXDraft by remember(composition?.id, layer?.id, layer?.positionX) { mutableStateOf((layer?.positionX ?: 0.0).toString()) }
    var positionYDraft by remember(composition?.id, layer?.id, layer?.positionY) { mutableStateOf((layer?.positionY ?: 0.0).toString()) }
    var scaleXDraft by remember(composition?.id, layer?.id, layer?.scaleX) { mutableStateOf((layer?.scaleX ?: 100.0).toString()) }
    var scaleYDraft by remember(composition?.id, layer?.id, layer?.scaleY) { mutableStateOf((layer?.scaleY ?: 100.0).toString()) }
    var rotationDraft by remember(composition?.id, layer?.id, layer?.rotationDegrees) { mutableStateOf((layer?.rotationDegrees ?: 0.0).toString()) }
    var anchorXDraft by remember(composition?.id, layer?.id, layer?.anchorX) { mutableStateOf((layer?.anchorX ?: 0.0).toString()) }
    var anchorYDraft by remember(composition?.id, layer?.id, layer?.anchorY) { mutableStateOf((layer?.anchorY ?: 0.0).toString()) }
    var orientationXDraft by remember(composition?.id, layer?.id, layer?.orientationX) { mutableStateOf((layer?.orientationX ?: 0.0).toString()) }
    var orientationYDraft by remember(composition?.id, layer?.id, layer?.orientationY) { mutableStateOf((layer?.orientationY ?: 0.0).toString()) }
    var orientationZDraft by remember(composition?.id, layer?.id, layer?.orientationZ) { mutableStateOf((layer?.orientationZ ?: 0.0).toString()) }
    var rotationXDraft by remember(composition?.id, layer?.id, layer?.rotationXDegrees) { mutableStateOf((layer?.rotationXDegrees ?: 0.0).toString()) }
    var rotationYDraft by remember(composition?.id, layer?.id, layer?.rotationYDegrees) { mutableStateOf((layer?.rotationYDegrees ?: 0.0).toString()) }
    var textDraft by remember(composition?.id, layer?.id, layer?.textParams?.text) { mutableStateOf(layer?.textParams?.text.orEmpty()) }
    var textSizeDraft by remember(composition?.id, layer?.id, layer?.textParams?.size) { mutableStateOf((layer?.textParams?.size ?: 0.0).toString()) }
    var textTrackingDraft by remember(composition?.id, layer?.id, layer?.textParams?.tracking) { mutableStateOf((layer?.textParams?.tracking ?: 0.0).toString()) }
    var textFontDraft by remember(composition?.id, layer?.id, layer?.textParams?.font) { mutableStateOf(layer?.textParams?.font.orEmpty()) }
    var paramDrafts by remember(composition?.id, layer?.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var previewFrame by remember(composition?.id) { mutableIntStateOf(0) }
    LaunchedEffect(playheadFrame, composition?.durationFrames) {
        previewFrame = playheadFrame.coerceIn(0, (composition?.durationFrames ?: 1) - 1)
    }
    var renderedFrameBytes by remember(composition?.id) { mutableStateOf<ByteArray?>(null) }
    var previewMessage by remember(composition?.id) { mutableStateOf("Waiting for native renderer") }
    var backendStatusText by remember(composition?.id) { mutableStateOf<String?>(null) }
    var mediaCacheStatusText by remember(composition?.id) { mutableStateOf<String?>(null) }
    // A surface attach/resize owes one fresh render: the nonce re-runs the
    // render effect below, and the new bytes flow into TexturePreview.
    var surfaceRenderNonce by remember(composition?.id) { mutableIntStateOf(0) }
    val surfacePresentation = remember(composition?.id) { com.codeair.effectcraft.engine.SurfacePresentation() }
    var previewWidthPx by remember { mutableIntStateOf(0) }
    // The motion-path overlay must sit exactly over the drawn preview
    // frame: its bounds come from the measured Image, not from the sheet
    // width, so it stays aligned (and capped at 220dp) on wide screens.
    var previewImageSize by remember { mutableStateOf(IntSize.Zero) }
    // Read at gesture end, not at detector setup, so a position Apply or a
    // re-parse between gestures never drags from stale layer positions.
    val gestureLayers by rememberUpdatedState(selectedLayers)
    val gestureEditCallback by rememberUpdatedState(onGestureEditSelection)
    val gestureCanEdit by rememberUpdatedState(canEdit)
    LaunchedEffect(project, composition?.id, previewFrame, canEdit, surfaceRenderNonce) {
        if (composition != null && canEdit) {
            previewMessage = "Rendering frame ${previewFrame}…"
            renderedFrameBytes = withContext(Dispatchers.Default) { onRenderFrame(composition.id, previewFrame.toLong()) }
            previewMessage = if (renderedFrameBytes != null) "Frame ready · ${previewFrame}" else "Native preview unavailable"
            // Backend/media diagnostics are queried on the same background
            // hop as the render (the first backend probe can block briefly)
            // and only then assigned to state on the main thread.
            backendStatusText = withContext(Dispatchers.Default) { onQueryBackendStatus?.invoke() }
            mediaCacheStatusText = withContext(Dispatchers.Default) { onQueryMediaCacheStatus?.invoke() }
        } else {
            renderedFrameBytes = null
            previewMessage = "ARM64 native renderer required"
            backendStatusText = null
            mediaCacheStatusText = null
        }
    }
    val previewBitmap = remember(renderedFrameBytes) { renderedFrameBytes?.let(::decodeRenderedFrame) }

    Column(modifier = modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CANONICAL .ECPROJ", color = EditorPalette.violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Native project model · ${project.compositions.size} compositions", color = EditorPalette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = canUndoCanonical,
                onClick = onUndoCanonical,
                modifier = Modifier.semantics { contentDescription = "Canonical undo" }
            ) { Text("Canonical Undo", color = if (canUndoCanonical) EditorPalette.violet else EditorPalette.muted, fontSize = 11.sp) }
            TextButton(
                enabled = canRedoCanonical,
                onClick = onRedoCanonical,
                modifier = Modifier.semantics { contentDescription = "Canonical redo" }
            ) { Text("Canonical Redo", color = if (canRedoCanonical) EditorPalette.violet else EditorPalette.muted, fontSize = 11.sp) }
        }
        if (selectedLayers.size > 1) {
            Text(
                "${selectedLayers.size} layers selected · opacity, visibility and preview gestures apply to all selected layers",
                color = EditorPalette.mint, fontSize = 11.sp
            )
        }
        if (composition == null) {
            Text("No compositions found in this project.", color = EditorPalette.muted)
        } else {
            Text("Composition", color = EditorPalette.muted, fontSize = 10.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                project.compositions.forEach { item ->
                    FilterChip(
                        selected = item.id == composition.id,
                        onClick = { compositionId = item.id; onSelectionChange(item.layers.firstOrNull()?.let { setOf(it.id) } ?: emptySet()) },
                        label = { Text(item.name, fontSize = 11.sp) }
                    )
                }
            }
            Text("${composition.width} × ${composition.height} · ${composition.layers.size} layers", color = EditorPalette.muted, fontSize = 11.sp)
            Text("CPU PREVIEW · ${composition.frameRateNum}/${composition.frameRateDen} FPS", color = EditorPalette.violet, fontSize = 10.sp, letterSpacing = 1.1.sp)
            // Drag moves every selected layer by the drag distance mapped
            // to composition pixels. The drag lands as one canonical
            // history step through the batch callback, so one undo
            // reverses the whole gesture. Pinch and twist mapping stays
            // available through the batch callback's scale and rotation
            // maps, but no multi-touch detector is wired in this change:
            // the transform detector never fired under test swipe
            // injection, while the drag detector is the proven path for
            // single-pointer drags.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .onSizeChanged { previewWidthPx = it.width }
                    .pointerInput(composition.id) {
                        while (true) {
                            var dragTotal = Offset.Zero
                            detectDragGestures(
                                onDragEnd = {
                                    if (gestureCanEdit && dragTotal != Offset.Zero) {
                                        val delta = com.codeair.effectcraft.data.CanonicalGestureEdit.dragPositionDelta(
                                            dragTotal.x, dragTotal.y, previewWidthPx.toFloat(), composition.width.toFloat()
                                        )
                                        val positions = com.codeair.effectcraft.data.CanonicalGestureEdit.draggedPositions(
                                            gestureLayers, gestureLayers.map { it.id }.toSet(), delta
                                        )
                                        if (positions.isNotEmpty()) {
                                            gestureEditCallback(composition.id, positions, emptyMap(), emptyMap())
                                        }
                                    }
                                }
                            ) { _, dragAmount ->
                                dragTotal += dragAmount
                            }
                        }
                    }
                    .semantics { contentDescription = "Canonical preview gesture area" }
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "EffectCraft rendered composition frame",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .onSizeChanged { previewImageSize = it },
                        contentScale = ContentScale.Fit
                    )
                    if (showMotionPath) {
                        MotionPathOverlay(
                            keys = layer?.transformCurves?.get("position").orEmpty(),
                            compositionWidth = composition.width,
                            compositionHeight = composition.height,
                            bitmapWidth = previewBitmap.width,
                            bitmapHeight = previewBitmap.height,
                            currentFrame = previewFrame,
                            modifier = if (previewImageSize == IntSize.Zero) {
                                Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            } else {
                                with(LocalDensity.current) { Modifier.size(previewImageSize.width.toDp(), previewImageSize.height.toDp()) }
                            }
                        )
                    }
                } else {
                    Text(previewMessage, color = EditorPalette.muted, fontSize = 11.sp)
                }
            }
            Text("Drag the preview to move selected layers", color = EditorPalette.muted, fontSize = 10.sp)
            Text("Frame ${previewFrame} / ${composition.durationFrames - 1}", color = EditorPalette.muted, fontSize = 10.sp)
            Slider(
                value = previewFrame.toFloat(),
                onValueChange = {
                    val frame = it.roundToInt().coerceIn(0, composition.durationFrames - 1)
                    previewFrame = frame
                    onPreviewFrameChange(frame)
                },
                valueRange = 0f..(composition.durationFrames - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.semantics { contentDescription = "Canonical preview frame" }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newTextName,
                    onValueChange = { newTextName = it.take(120) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 13.sp),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "New text layer name" },
                    decorationBox = { inner ->
                        Box {
                            if (newTextName.isEmpty()) Text("New text layer name", color = EditorPalette.muted)
                            inner()
                        }
                    }
                )
                TextButton(
                    enabled = canEdit && newTextName.trim().isNotEmpty(),
                    onClick = { onAddTextLayer(composition.id, newTextName) }
                ) { Text("Add Text", color = EditorPalette.violet) }
            }
            Text("Layers", color = EditorPalette.muted, fontSize = 10.sp)
            Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                composition.layers.forEach { item ->
                    // Tap selects one layer; long-press toggles it in or out
                    // of the multi-selection. The inspector always shows the
                    // first selected layer.
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelectionChange(setOf(item.id)); nameDraft = item.name },
                                onLongClick = {
                                    onSelectionChange(
                                        com.codeair.effectcraft.data.CanonicalLayerSelection.toggleSelection(
                                            effectiveSelectedIds, item.id, composition.layers
                                        )
                                    )
                                }
                            )
                            .semantics { contentDescription = "Canonical layer row ${item.name}" }
                    ) {
                        Text(
                            "${if (item.id in effectiveSelectedIds) "● " else "○ "}${item.name}  ·  ${item.source}",
                            color = if (item.id in effectiveSelectedIds) EditorPalette.mint else EditorPalette.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // Surface preview sits below the layer list: the 220dp surface
            // above the list pushed the list below the sheet fold, so the
            // layer rows failed assertIsDisplayed on open. The preview
            // itself is unchanged, only its position in this column.
            if (backendStatusText != null) {
                Text("Render backend · $backendStatusText", color = EditorPalette.muted, fontSize = 11.sp)
            }
            // let-bound: a delegated `by remember` state value cannot be
            // smart-cast after a null check, so bind it to a local first.
            mediaCacheStatusText?.let {
                Text(it, color = EditorPalette.muted, fontSize = 11.sp)
            }
            Text("SURFACE PREVIEW", color = EditorPalette.violet, fontSize = 10.sp, letterSpacing = 1.1.sp)
            // The same native frame, presented on a TextureView Surface:
            // attach/resize re-renders through surfaceRenderNonce, and a
            // session loss/recovery re-attaches through surfacePresentation.
            TexturePreview(
                frameBytes = renderedFrameBytes,
                onSurfaceReady = { surfaceRenderNonce++ },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                presentation = surfacePresentation,
            )
            if (layer != null) {
                // A locked layer shows its fields but accepts no edit: every
                // control below is disabled while locked, and the native
                // layer rejects the edit as well, so the UI can never drift
                // from the canonical data. Only the lock switch itself stays
                // enabled, so a locked layer can always be unlocked.
                val editable = canEdit && !layer.locked
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visible", color = EditorPalette.text, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = layer.visible,
                        onCheckedChange = {
                            if (selectedLayers.size > 1) onSetLayerVisibilityForSelection(composition.id, selectedLayers.map { it.id }.toSet(), it)
                            else onSetLayerVisibility(composition.id, layer.id, it)
                        },
                        enabled = editable,
                        modifier = Modifier.semantics { contentDescription = "Canonical layer visibility" }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Locked", color = EditorPalette.text, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = layer.locked,
                        onCheckedChange = { onSetLayerLocked(composition.id, layer.id, it) },
                        enabled = canEdit,
                        modifier = Modifier.semantics { contentDescription = "Canonical layer locked" }
                    )
                }
                if (layer.locked) {
                    Text("Locked layers cannot be edited until unlocked.", color = EditorPalette.amber, fontSize = 11.sp)
                }
                Text("Position · X / Y", color = EditorPalette.text, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = positionXDraft,
                        onValueChange = { positionXDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical position X" },
                        decorationBox = { inner -> Box { if (positionXDraft.isEmpty()) Text("X", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = positionYDraft,
                        onValueChange = { positionYDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical position Y" },
                        decorationBox = { inner -> Box { if (positionYDraft.isEmpty()) Text("Y", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && positionXDraft.toDoubleOrNull()?.isFinite() == true && positionYDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerPosition(composition.id, layer.id, positionXDraft.toDouble(), positionYDraft.toDouble()) }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("Scale · X / Y (%)", color = EditorPalette.text, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = scaleXDraft,
                        onValueChange = { scaleXDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical scale X" },
                        decorationBox = { inner -> Box { if (scaleXDraft.isEmpty()) Text("X %", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = scaleYDraft,
                        onValueChange = { scaleYDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical scale Y" },
                        decorationBox = { inner -> Box { if (scaleYDraft.isEmpty()) Text("Y %", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && scaleXDraft.toDoubleOrNull()?.isFinite() == true && scaleYDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerScale(composition.id, layer.id, scaleXDraft.toDouble(), scaleYDraft.toDouble()) }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Rotation · °", color = EditorPalette.text, fontSize = 12.sp)
                    BasicTextField(
                        value = rotationDraft,
                        onValueChange = { rotationDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical rotation degrees" },
                        decorationBox = { inner -> Box { if (rotationDraft.isEmpty()) Text("Degrees", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && rotationDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerRotation(composition.id, layer.id, rotationDraft.toDouble()) }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "position", previewFrame, layer.positionX, layer.positionY, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add position keyframe" }
                    ) { Text("Key Position", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "scale", previewFrame, layer.scaleX, layer.scaleY, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add scale keyframe" }
                    ) { Text("Key Scale", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "rotation", previewFrame, layer.rotationDegrees, 0.0, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add rotation keyframe" }
                    ) { Text("Key Rotation", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "anchor", previewFrame, layer.anchorX, layer.anchorY, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add anchor keyframe" }
                    ) { Text("Key Anchor", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "orientation", previewFrame, layer.orientationX, layer.orientationY, layer.orientationZ) },
                        modifier = Modifier.semantics { contentDescription = "Add orientation keyframe" }
                    ) { Text("Key Orientation", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "rotationX", previewFrame, layer.rotationXDegrees, 0.0, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add X rotation keyframe" }
                    ) { Text("Key X", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerTransformKeyframe(composition.id, layer.id, "rotationY", previewFrame, layer.rotationYDegrees, 0.0, 0.0) },
                        modifier = Modifier.semantics { contentDescription = "Add Y rotation keyframe" }
                    ) { Text("Key Y", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("position", "scale", "rotation", "anchor").forEach { property ->
                        TextButton(
                            enabled = editable,
                            onClick = { onRemoveLayerTransformKeyframe(composition.id, layer.id, property, previewFrame) },
                            modifier = Modifier.semantics { contentDescription = "Remove ${property} keyframe" }
                        ) { Text("− ${property.replaceFirstChar { it.uppercase() }}", color = EditorPalette.amber, fontSize = 10.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("orientation", "rotationX", "rotationY").forEach { property ->
                        TextButton(
                            enabled = editable,
                            onClick = { onRemoveLayerTransformKeyframe(composition.id, layer.id, property, previewFrame) },
                            modifier = Modifier.semantics { contentDescription = "Remove ${property} keyframe" }
                        ) { Text("− ${property.replaceFirstChar { it.uppercase() }}", color = EditorPalette.amber, fontSize = 10.sp) }
                    }
                }
                Text("Transform keys are native and evaluated by the composition renderer.", color = EditorPalette.muted, fontSize = 10.sp)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    listOf(
                        "position" to "Position", "scale" to "Scale", "rotation" to "Rotation",
                        "anchor" to "Anchor", "orientation" to "Orientation",
                        "rotationX" to "X Rotation", "rotationY" to "Y Rotation"
                    ).forEach { (property, label) ->
                        val frames = layer.transformKeyframes[property].orEmpty()
                        TextButton(
                            enabled = frames.isNotEmpty(),
                            onClick = {
                                frames.minByOrNull { frame -> abs(frame - previewFrame) }?.let { target ->
                                    previewFrame = target
                                    onPreviewFrameChange(target)
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Jump to $label keyframe" }
                        ) {
                            Text(
                                "$label keys · ${if (frames.isEmpty()) "—" else frames.joinToString(", ")}",
                                color = if (frames.isEmpty()) EditorPalette.muted else EditorPalette.mint,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                Text("Key interpolation · current frame", color = EditorPalette.text, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("position" to "Position", "scale" to "Scale", "rotation" to "Rotation", "anchor" to "Anchor").forEach { (property, label) ->
                        FilterChip(
                            selected = interpolationProperty == property,
                            onClick = { onInterpolationPropertyChange(property) },
                            enabled = editable,
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
                val hasInterpolationKey = layer.transformKeyframes[interpolationProperty].orEmpty().contains(previewFrame)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("both" to "Both", "incoming" to "Incoming", "outgoing" to "Outgoing").forEach { (side, label) ->
                        FilterChip(
                            selected = interpolationSide == side,
                            onClick = { onInterpolationSideChange(side) },
                            enabled = editable,
                            label = { Text(label, fontSize = 10.sp) },
                            modifier = Modifier.semantics { contentDescription = "Select $side interpolation side" }
                        )
                    }
                }
                // Two short rows so every preset stays on screen on phone
                // widths; one long row pushed the trailing Hold button past
                // the visible edge, where its tap was swallowed.
                listOf(
                    listOf("linear" to "Linear", "ease_in" to "Ease In", "ease_out" to "Ease Out", "ease_in_out" to "Ease In Out"),
                    listOf("easy_ease" to "Easy Ease", "bezier" to "Bezier", "hold" to "Hold")
                ).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowPresets.forEach { (preset, label) ->
                            TextButton(
                                enabled = editable && hasInterpolationKey,
                                onClick = {
                                    val nativePreset = if (interpolationSide == "both") preset else "${interpolationSide}_${preset}"
                                    onSetLayerTransformInterpolation(composition.id, layer.id, interpolationProperty, previewFrame, nativePreset)
                                },
                                modifier = Modifier.semantics { contentDescription = "Set $interpolationProperty key to $label on $interpolationSide side at frame $previewFrame" }
                            ) { Text(label, color = if (preset == "easy_ease") EditorPalette.violet else EditorPalette.mint, fontSize = 10.sp) }
                        }
                    }
                }
                Text("Both edits both tangents; Incoming/Outgoing preserves the opposite side. Ease In slows the segment after this key; Ease Out slows the segment before it; Bezier is an asymmetric custom curve. The renderer evaluates the ease.", color = EditorPalette.muted, fontSize = 10.sp)
                var moveTargetDraft by remember(composition?.id, layer?.id, previewFrame) { mutableStateOf(previewFrame.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Move key · to frame", color = EditorPalette.text, fontSize = 12.sp)
                    BasicTextField(
                        value = moveTargetDraft,
                        onValueChange = { moveTargetDraft = it.take(16) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Move key target frame" },
                        decorationBox = { inner -> Box { if (moveTargetDraft.isEmpty()) Text("Frame", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && hasInterpolationKey && moveTargetDraft.toIntOrNull()?.let { it >= 0 } == true,
                        onClick = { onMoveCanonicalCurveKey(composition.id, layer.id, "transform:$interpolationProperty", previewFrame, moveTargetDraft.toInt()) },
                        modifier = Modifier.semantics { contentDescription = "Move $interpolationProperty keyframe to frame ${moveTargetDraft}" }
                    ) { Text("Move", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("Dragging a marker on the TRANSFORM timeline lane moves that key; the curve graph move applies when several keys share the frame.", color = EditorPalette.muted, fontSize = 10.sp)
                // Roving + motion-path controls exist only for spatial
                // properties (position/anchor): the layer moves along a path
                // in comp space, so a key can rove across time and the path
                // itself can be auto or straight.
                if (com.codeair.effectcraft.data.MotionPath.isSpatialProperty(interpolationProperty)) {
                    val currentKey = layer.transformCurves[interpolationProperty]?.firstOrNull { it.frame == previewFrame }
                    if (currentKey != null && composition != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Roving keyframe", color = EditorPalette.text, fontSize = 12.sp)
                            Switch(
                                checked = currentKey.roving,
                                onCheckedChange = { onSetCanonicalKeyframeRoving(composition.id, layer.id, interpolationProperty, previewFrame, it) },
                                enabled = editable,
                                modifier = Modifier.semantics { contentDescription = "Roving keyframe for $interpolationProperty at frame $previewFrame" }
                            )
                        }
                        Text("Roving keys slide in time so the layer travels the path at constant speed; first and last keys never rove.", color = EditorPalette.muted, fontSize = 10.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Auto motion path", color = EditorPalette.text, fontSize = 12.sp)
                            Switch(
                                checked = currentKey.spatialAuto,
                                onCheckedChange = {
                                    onSetCanonicalKeyframeSpatialTangents(
                                        composition.id, layer.id, interpolationProperty, previewFrame,
                                        currentKey.spatialIn.getOrElse(0) { 0.0 }, currentKey.spatialIn.getOrElse(1) { 0.0 }, currentKey.spatialIn.getOrElse(2) { 0.0 },
                                        currentKey.spatialOut.getOrElse(0) { 0.0 }, currentKey.spatialOut.getOrElse(1) { 0.0 }, currentKey.spatialOut.getOrElse(2) { 0.0 },
                                        it
                                    )
                                },
                                enabled = editable,
                                modifier = Modifier.semantics { contentDescription = "Auto motion path for $interpolationProperty at frame $previewFrame" }
                            )
                            TextButton(
                                enabled = editable && (!currentKey.spatialAuto || currentKey.spatialIn.any { it != 0.0 } || currentKey.spatialOut.any { it != 0.0 }),
                                onClick = {
                                    onSetCanonicalKeyframeSpatialTangents(composition.id, layer.id, interpolationProperty, previewFrame, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false)
                                },
                                modifier = Modifier.semantics { contentDescription = "Straight path for $interpolationProperty at frame $previewFrame" }
                            ) { Text("Straight path", color = EditorPalette.mint, fontSize = 10.sp) }
                        }
                        Text("Auto bends the path smoothly through the keys; Straight path runs each segment directly between keys.", color = EditorPalette.muted, fontSize = 10.sp)
                    }
                }
                // The overlay toggle lives with the motion-path controls and
                // is a view preference, so it stays enabled even for a locked
                // layer; it only appears when the selected layer has a path.
                val positionKeys = layer.transformCurves["position"].orEmpty()
                if (positionKeys.size >= 2) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Show motion path", color = EditorPalette.text, fontSize = 12.sp)
                        Switch(
                            checked = showMotionPath,
                            onCheckedChange = onShowMotionPathChange,
                            modifier = Modifier.semantics { contentDescription = "Show motion path overlay" }
                        )
                    }
                }
                if (composition != null) {
                    CurveGraphSection(
                        layer = layer,
                        composition = composition,
                        previewFrame = previewFrame,
                        editable = editable,
                        onMoveCurveKey = onMoveCanonicalCurveKey,
                        onSetCurveHandle = onSetCanonicalCurveHandle,
                        onAddCurveKey = onAddCanonicalCurveKey,
                        onRemoveCurveKey = onRemoveCanonicalCurveKey
                    )
                }
                Text("Anchor Point · X / Y", color = EditorPalette.text, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = anchorXDraft,
                        onValueChange = { anchorXDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical anchor X" },
                        decorationBox = { inner -> Box { if (anchorXDraft.isEmpty()) Text("X", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = anchorYDraft,
                        onValueChange = { anchorYDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical anchor Y" },
                        decorationBox = { inner -> Box { if (anchorYDraft.isEmpty()) Text("Y", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && anchorXDraft.toDoubleOrNull()?.isFinite() == true && anchorYDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerAnchor(composition.id, layer.id, anchorXDraft.toDouble(), anchorYDraft.toDouble()) },
                        modifier = Modifier.semantics { contentDescription = "Apply canonical anchor" }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("3D Rotation · Orientation X / Y / Z (°)", color = EditorPalette.text, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = orientationXDraft,
                        onValueChange = { orientationXDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical orientation X" },
                        decorationBox = { inner -> Box { if (orientationXDraft.isEmpty()) Text("X", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = orientationYDraft,
                        onValueChange = { orientationYDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical orientation Y" },
                        decorationBox = { inner -> Box { if (orientationYDraft.isEmpty()) Text("Y", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = orientationZDraft,
                        onValueChange = { orientationZDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical orientation Z" },
                        decorationBox = { inner -> Box { if (orientationZDraft.isEmpty()) Text("Z", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && orientationXDraft.toDoubleOrNull()?.isFinite() == true && orientationYDraft.toDoubleOrNull()?.isFinite() == true && orientationZDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerOrientation(composition.id, layer.id, orientationXDraft.toDouble(), orientationYDraft.toDouble(), orientationZDraft.toDouble()) },
                        modifier = Modifier.semantics { contentDescription = "Apply canonical orientation" }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("3D Rotation · X / Y (°)", color = EditorPalette.text, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = rotationXDraft,
                        onValueChange = { rotationXDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical rotation X" },
                        decorationBox = { inner -> Box { if (rotationXDraft.isEmpty()) Text("X °", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = rotationYDraft,
                        onValueChange = { rotationYDraft = it.take(32) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical rotation Y" },
                        decorationBox = { inner -> Box { if (rotationYDraft.isEmpty()) Text("Y °", color = EditorPalette.muted); inner() } }
                    )
                    TextButton(
                        enabled = editable && rotationXDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerRotationX(composition.id, layer.id, rotationXDraft.toDouble()) },
                        modifier = Modifier.semantics { contentDescription = "Apply canonical rotation X" }
                    ) { Text("Apply X", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable && rotationYDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerRotationY(composition.id, layer.id, rotationYDraft.toDouble()) },
                        modifier = Modifier.semantics { contentDescription = "Apply canonical rotation Y" }
                    ) { Text("Apply Y", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                if (layer.threeD) {
                    Text("3D layer · orientation and X/Y rotation apply in the 3D renderer.", color = EditorPalette.muted, fontSize = 11.sp)
                }
                layer.textParams?.let { textParams ->
                    Text("Text · Source Text", color = EditorPalette.text, fontSize = 12.sp)
                    // After Effects two-mode (owner pick 2026-11-06): keyframes
                    // OFF means an edit changes the whole layer everywhere
                    // (keys are dropped, static text set); keyframes ON means
                    // an edit changes only the hold key at the playhead.
                    // The mode follows the layer: ON while it has any Source
                    // Text key, OFF otherwise.
                    val textKeyframingOn = textParams.keys.isNotEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Source Text keyframes",
                            color = EditorPalette.text, fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = textKeyframingOn,
                            onCheckedChange = { on ->
                                if (!editable) return@Switch
                                if (on) onSetCanonicalTextLayerKeyframe(composition.id, layer.id, textParams.uid, previewFrame, textDraft, false)
                                else onSetTextLayerTextWholeLayer(composition.id, layer.id, textDraft)
                            },
                            modifier = Modifier.semantics { contentDescription = "Toggle Source Text keyframes" }
                        )
                    }
                    Text(
                        if (textKeyframingOn) "Keyframes on · edits change only the hold key at the playhead."
                        else "Keyframes off · edits change the whole layer everywhere.",
                        color = EditorPalette.muted, fontSize = 10.sp
                    )
                    Text("Font · ${textParams.font}", color = EditorPalette.muted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicTextField(
                            value = textFontDraft,
                            onValueChange = { textFontDraft = it.take(128) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer font" },
                            decorationBox = { inner -> Box { if (textFontDraft.isEmpty()) Text("Font family", color = EditorPalette.muted); inner() } }
                        )
                        TextButton(
                            enabled = editable && textFontDraft.isNotBlank() && textFontDraft.length <= 128,
                            onClick = { onSetTextLayerFont(composition.id, layer.id, textFontDraft) },
                            modifier = Modifier.semantics { contentDescription = "Apply canonical text font" }
                        ) { Text("Apply font", color = EditorPalette.violet, fontSize = 10.sp) }
                        val hasFontKey = textParams.fontKeys.any { key -> key.frame == previewFrame }
                        TextButton(
                            enabled = editable && textFontDraft.isNotBlank() && textFontDraft.length <= 128,
                            onClick = { onSetTextLayerFontKeyframe(composition.id, layer.id, textParams.uid, previewFrame, textFontDraft, hasFontKey) },
                            modifier = Modifier.semantics {
                                contentDescription = if (hasFontKey) "Remove canonical font keyframe frame $previewFrame"
                                else "Add canonical font keyframe frame $previewFrame"
                            }
                        ) { Text(if (hasFontKey) "Remove key" else "Key font", color = if (hasFontKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
                    }
                    Text(
                        "Font keys · " + if (textParams.fontKeys.isEmpty()) "none" else textParams.fontKeys.joinToString { key -> "${key.frame}: ${key.font}" },
                        color = EditorPalette.amber, fontSize = 10.sp,
                        modifier = Modifier.semantics { contentDescription = "Canonical font keyframes" }
                    )
                    BasicTextField(
                        value = textDraft,
                        onValueChange = { textDraft = it.take(20000) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Canonical text layer text" },
                        decorationBox = { inner -> Box { if (textDraft.isEmpty()) Text("Source text", color = EditorPalette.muted); inner() } }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = editable,
                            onClick = {
                                if (textKeyframingOn) onSetCanonicalTextLayerKeyframe(composition.id, layer.id, textParams.uid, previewFrame, textDraft, false)
                                else onSetTextLayerTextWholeLayer(composition.id, layer.id, textDraft)
                            },
                            modifier = Modifier.semantics { contentDescription = "Apply canonical text" }
                        ) { Text("Apply text", color = EditorPalette.violet, fontSize = 11.sp) }
                        val hasTextKey = textParams.keys.any { key -> key.frame == previewFrame }
                        TextButton(
                            enabled = editable && (hasTextKey || textDraft.isNotEmpty()),
                            onClick = { onSetCanonicalTextLayerKeyframe(composition.id, layer.id, textParams.uid, previewFrame, textDraft, hasTextKey) },
                            modifier = Modifier.semantics {
                                contentDescription = if (hasTextKey) "Remove canonical Source Text keyframe frame $previewFrame"
                                else "Add canonical Source Text keyframe frame $previewFrame"
                            }
                        ) { Text(if (hasTextKey) "Remove key" else "Add key", color = if (hasTextKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
                    }
                    Text(
                        "Source Text keys · " + if (textParams.keys.isEmpty()) "none" else textParams.keys.joinToString { key -> key.frame.toString() + ": " + key.text.take(24) },
                        color = EditorPalette.amber, fontSize = 10.sp,
                        modifier = Modifier.semantics { contentDescription = "Canonical Source Text keyframes" }
                    )
                    Text("Text · Size / Tracking", color = EditorPalette.text, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicTextField(
                            value = textSizeDraft,
                            onValueChange = { textSizeDraft = it.take(32) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer size" },
                            decorationBox = { inner -> Box { if (textSizeDraft.isEmpty()) Text("Size", color = EditorPalette.muted); inner() } }
                        )
                        BasicTextField(
                            value = textTrackingDraft,
                            onValueChange = { textTrackingDraft = it.take(32) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer tracking" },
                            decorationBox = { inner -> Box { if (textTrackingDraft.isEmpty()) Text("Tracking", color = EditorPalette.muted); inner() } }
                        )
                        TextButton(
                            enabled = editable && textSizeDraft.toDoubleOrNull()?.isFinite() == true,
                            onClick = { onSetTextLayerSize(composition.id, layer.id, textSizeDraft.toDouble()) },
                            modifier = Modifier.semantics { contentDescription = "Apply canonical text size" }
                        ) { Text("Apply size", color = EditorPalette.violet, fontSize = 10.sp) }
                        TextButton(
                            enabled = editable && textTrackingDraft.toDoubleOrNull()?.isFinite() == true,
                            onClick = { onSetTextLayerTracking(composition.id, layer.id, textTrackingDraft.toDouble()) },
                            modifier = Modifier.semantics { contentDescription = "Apply canonical text tracking" }
                        ) { Text("Apply tracking", color = EditorPalette.violet, fontSize = 10.sp) }
                        val hasTrackingKey = textParams.keys.any { key -> key.frame == previewFrame }
                        TextButton(
                            enabled = editable && textTrackingDraft.toDoubleOrNull()?.isFinite() == true,
                            onClick = { onSetTextLayerTrackingKeyframe(composition.id, layer.id, textParams.uid, previewFrame, textTrackingDraft.toDouble(), hasTrackingKey) },
                            modifier = Modifier.semantics {
                                contentDescription = if (hasTrackingKey) "Remove canonical Source Text keyframe frame $previewFrame"
                                else "Add canonical Source Text tracking keyframe frame $previewFrame"
                            }
                        ) { Text(if (hasTrackingKey) "Remove key" else "Key tracking", color = if (hasTrackingKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
                    }
                    Text(
                        "Tracking at Source Text keys · " + if (textParams.keys.isEmpty()) "none" else textParams.keys.joinToString { "${it.frame}: ${it.tracking}" },
                        color = EditorPalette.amber, fontSize = 10.sp,
                        modifier = Modifier.semantics { contentDescription = "Canonical Source Text tracking keyframes" }
                    )
                }
                Text("Opacity · ${opacityDraft.roundToInt()}%", color = EditorPalette.text, fontSize = 12.sp)
                Slider(
                    value = opacityDraft,
                    onValueChange = { opacityDraft = it },
                    onValueChangeFinished = {
                        if (editable) {
                            if (selectedLayers.size > 1) onSetLayerOpacityForSelection(composition.id, selectedLayers.map { it.id }.toSet(), opacityDraft.toDouble())
                            else onSetLayerOpacity(composition.id, layer.id, opacityDraft.toDouble())
                        }
                    },
                    valueRange = 0f..100f,
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = "Canonical layer opacity" }
                )
                Text(
                    "Opacity keys · ${layer.opacityKeys.size}" +
                        if (layer.opacityKeys.isEmpty()) "" else " · " + layer.opacityKeys.joinToString { "${it.frame}: ${it.value.roundToInt()}%" },
                    color = EditorPalette.amber,
                    fontSize = 11.sp,
                    modifier = Modifier.semantics { contentDescription = "Canonical opacity keys" }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = editable,
                        onClick = { onAddLayerOpacityKeyframe(composition.id, layer.id, previewFrame, opacityDraft.toDouble()) },
                        modifier = Modifier.semantics { contentDescription = "Add canonical opacity keyframe" }
                    ) { Text("Add key · frame $previewFrame", color = EditorPalette.violet, fontSize = 11.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = { onRemoveLayerOpacityKeyframe(composition.id, layer.id, previewFrame) },
                        modifier = Modifier.semantics { contentDescription = "Remove canonical opacity keyframe" }
                    ) { Text("Remove key · frame $previewFrame", color = EditorPalette.amber, fontSize = 11.sp) }
                }
                if (layer.shapeItems.isNotEmpty()) {
                    Text("SHAPE PARAMETERS", color = EditorPalette.violet, fontSize = 10.sp, letterSpacing = 1.1.sp)
                    CanonicalParamGroupRows(
                        groups = layer.shapeItems,
                        section = "shape",
                        editable = editable,
                        paramDrafts = paramDrafts,
                        onParamDraftChange = { uid, value -> paramDrafts = paramDrafts + (uid to value) },
                        onApplyScalar = { uid, value -> onSetLayerScalarProperty(composition.id, layer.id, uid, value) },
                        onApplyVec = { uid, x, y, z -> onSetLayerVecProperty(composition.id, layer.id, uid, x, y, z) },
                        onApplyColor = { uid, r, g, b, a -> onSetLayerColorProperty(composition.id, layer.id, uid, r, g, b, a) },
                        onApplyGradient = { uid, gradientJson -> onSetLayerGradientProperty(composition.id, layer.id, uid, gradientJson) },
                        onApplyPath = { uid, pathJson -> onSetLayerPathProperty(composition.id, layer.id, uid, pathJson) },
                        onPathKeyframeAction = { uid, frame, pathJson, remove -> onSetLayerPathKeyframe(composition.id, layer.id, uid, frame, pathJson, remove) },
                        availableLayers = composition.layers,
                        onApplyLayerReference = { uid, targetId -> onSetLayerReferenceProperty(composition.id, layer.id, uid, targetId) },
                        onLayerReferenceKeyframeAction = { uid, frame, targetId, remove ->
                            onSetLayerReferenceKeyframe(composition.id, layer.id, uid, frame, targetId, remove)
                        },
                        playheadFrame = previewFrame,
                        onColorKeyframeAction = { uid, frame, r, g, b, a, remove ->
                            onSetLayerColorKeyframe(composition.id, layer.id, uid, frame, r, g, b, a, remove)
                        },
                        onApplyBool = { uid, value -> onSetLayerBoolProperty(composition.id, layer.id, uid, value) },
                        onApplyPopup = { uid, index -> onSetLayerPopupProperty(composition.id, layer.id, uid, index) },
                        onBoolKeyframeAction = { uid, frame, value, remove ->
                            onSetLayerBoolKeyframe(composition.id, layer.id, uid, frame, value, remove)
                        },
                        onPopupKeyframeAction = { uid, frame, index, remove ->
                            onSetLayerPopupKeyframe(composition.id, layer.id, uid, frame, index, remove)
                        }
                    )
                } else if (layer.source == "Shape") {
                    Text("No editable shape parameters in this layer.", color = EditorPalette.muted, fontSize = 11.sp)
                }
                if (layer.effects.isNotEmpty()) {
                    Text("EFFECTS", color = EditorPalette.violet, fontSize = 10.sp, letterSpacing = 1.1.sp)
                    CanonicalParamGroupRows(
                        groups = layer.effects,
                        section = "effect",
                        editable = editable,
                        paramDrafts = paramDrafts,
                        onParamDraftChange = { uid, value -> paramDrafts = paramDrafts + (uid to value) },
                        onApplyScalar = { uid, value -> onSetLayerScalarProperty(composition.id, layer.id, uid, value) },
                        onApplyVec = { uid, x, y, z -> onSetLayerVecProperty(composition.id, layer.id, uid, x, y, z) },
                        onApplyColor = { uid, r, g, b, a -> onSetLayerColorProperty(composition.id, layer.id, uid, r, g, b, a) },
                        onApplyGradient = { uid, gradientJson -> onSetLayerGradientProperty(composition.id, layer.id, uid, gradientJson) },
                        onApplyPath = { uid, pathJson -> onSetLayerPathProperty(composition.id, layer.id, uid, pathJson) },
                        onPathKeyframeAction = { uid, frame, pathJson, remove -> onSetLayerPathKeyframe(composition.id, layer.id, uid, frame, pathJson, remove) },
                        availableLayers = composition.layers,
                        onApplyLayerReference = { uid, targetId -> onSetLayerReferenceProperty(composition.id, layer.id, uid, targetId) },
                        onLayerReferenceKeyframeAction = { uid, frame, targetId, remove ->
                            onSetLayerReferenceKeyframe(composition.id, layer.id, uid, frame, targetId, remove)
                        },
                        playheadFrame = previewFrame,
                        onColorKeyframeAction = { uid, frame, r, g, b, a, remove ->
                            onSetLayerColorKeyframe(composition.id, layer.id, uid, frame, r, g, b, a, remove)
                        },
                        onApplyBool = { uid, value -> onSetLayerBoolProperty(composition.id, layer.id, uid, value) },
                        onApplyPopup = { uid, index -> onSetLayerPopupProperty(composition.id, layer.id, uid, index) },
                        onBoolKeyframeAction = { uid, frame, value, remove ->
                            onSetLayerBoolKeyframe(composition.id, layer.id, uid, frame, value, remove)
                        },
                        onPopupKeyframeAction = { uid, frame, index, remove ->
                            onSetLayerPopupKeyframe(composition.id, layer.id, uid, frame, index, remove)
                        }
                    )
                } else {
                    Text("No effects on this layer.", color = EditorPalette.muted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = editable, onClick = { onMoveLayer(composition.id, layer.id, -1) }) { Text("Move ↑", color = EditorPalette.muted, fontSize = 11.sp) }
                    TextButton(enabled = editable, onClick = { onMoveLayer(composition.id, layer.id, 1) }) { Text("Move ↓", color = EditorPalette.muted, fontSize = 11.sp) }
                    TextButton(
                        enabled = editable,
                        onClick = {
                            // Selection parity on delete: survivors of the
                            // selection stay selected, and when the whole
                            // selection was deleted the neighbor takes its
                            // place, so the inspector never shows a removed
                            // layer.
                            onSelectionChange(
                                com.codeair.effectcraft.data.CanonicalLayerSelection.selectionAfterDelete(
                                    effectiveSelectedIds, composition.layers, setOf(layer.id)
                                )
                            )
                            onDeleteLayer(composition.id, layer.id)
                        },
                        modifier = Modifier.semantics { contentDescription = "Remove canonical layer" }
                    ) { Text("Remove", color = Color(0xFFFF858D), fontSize = 11.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(120) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 14.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Layer name" },
                        decorationBox = { inner ->
                            Box {
                                if (nameDraft.isEmpty()) Text("Layer name", color = EditorPalette.muted)
                                inner()
                            }
                        }
                    )
                    TextButton(
                        enabled = editable && nameDraft.trim().isNotEmpty() && nameDraft.trim() != layer.name,
                        onClick = { onRenameLayer(composition.id, layer.id, nameDraft) }
                    ) { Text("Apply", color = EditorPalette.violet) }
                }
            }
            val mediaItems = project.items.filter { it.path != null }
            Text("PROJECT ASSETS · ${mediaItems.size}", color = EditorPalette.muted, fontSize = 10.sp, letterSpacing = 1.1.sp)
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                mediaItems.forEach { asset ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${asset.name} · ${asset.type}${if (asset.missing) " · MISSING" else ""}",
                                color = if (asset.missing) EditorPalette.amber else EditorPalette.text,
                                fontSize = 11.sp
                            )
                            Text(asset.path.orEmpty(), color = EditorPalette.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(enabled = canEdit, onClick = { onRelinkAsset(asset.id) }) { Text("Relink", color = EditorPalette.violet, fontSize = 10.sp) }
                    }
                }
                if (mediaItems.isEmpty()) Text("No linked media assets in this project.", color = EditorPalette.muted, fontSize = 11.sp)
            }
        }
        if (!canEdit) Text("Native editor bridge unavailable for this ABI; import/export remains read-only.", color = EditorPalette.amber, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Editable rows for one shape item or effect group: one text field plus
 * Apply per scalar parameter, one comma-separated field plus Apply per
 * vector parameter, a checkbox per bool parameter and a dropdown per popup
 * parameter. A bool/popup value change edits the static value while the
 * parameter has no keys, and authors a hold key at the playhead once it
 * does; each row also carries Add key / Remove key buttons in the same
 * style as the color rows. Parameters the native model does not expose
 * (path, gradient and layer-reference) are exposed through dedicated
 * validated editors rather than the generic numeric rows.
 */
@Composable
private fun CanonicalParamGroupRows(
    groups: List<CanonicalParamGroup>,
    section: String,
    editable: Boolean,
    paramDrafts: Map<Long, String>,
    onParamDraftChange: (Long, String) -> Unit,
    onApplyScalar: (Long, Double) -> Unit,
    onApplyVec: (Long, Double, Double, Double) -> Unit,
    onApplyColor: (Long, Double, Double, Double, Double) -> Unit,
    onApplyGradient: (Long, String) -> Unit,
    onApplyPath: (Long, String) -> Unit,
    onPathKeyframeAction: (Long, Int, String, Boolean) -> Unit,
    availableLayers: List<CanonicalLayerInfo>,
    onApplyLayerReference: (Long, Long?) -> Unit,
    onLayerReferenceKeyframeAction: (Long, Int, Long?, Boolean) -> Unit,
    playheadFrame: Int,
    onColorKeyframeAction: (Long, Int, Double, Double, Double, Double, Boolean) -> Unit,
    onApplyBool: (Long, Boolean) -> Unit,
    onApplyPopup: (Long, Int) -> Unit,
    onBoolKeyframeAction: (Long, Int, Boolean, Boolean) -> Unit,
    onPopupKeyframeAction: (Long, Int, Int, Boolean) -> Unit
) {
    var expandedPopupUid by remember { mutableStateOf<Long?>(null) }
    var expandedLayerRefUid by remember { mutableStateOf<Long?>(null) }
    // Raw per-stop text drafts for gradient rows, keyed "<uid>:<stop>" for RGBA
    // and "<uid>:<stop>:op" for opacity; validated on Apply like the color rows.
    var gradientStopDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pathDrafts by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // Per-coordinate text drafts allow transient values such as '-' while typing; commit only finite pairs.
    var pathVertexDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pathTangentDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    groups.forEach { group ->
        Text(group.name, color = EditorPalette.text, fontSize = 12.sp)
        group.scalars.forEach { param ->
            val draft = paramDrafts[param.uid] ?: param.value.toString()
            val parsed = draft.toDoubleOrNull()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                BasicTextField(
                    value = draft,
                    onValueChange = { onParamDraftChange(param.uid, it.take(32)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical $section parameter ${group.name} ${param.name}" },
                    decorationBox = { inner -> Box { if (draft.isEmpty()) Text(param.name, color = EditorPalette.muted); inner() } }
                )
                TextButton(
                    enabled = editable && parsed?.isFinite() == true,
                    onClick = { parsed?.let { onApplyScalar(param.uid, it) } },
                    modifier = Modifier.semantics { contentDescription = "Apply canonical $section parameter ${group.name} ${param.name}" }
                ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
            }
        }
        group.vecs.forEach { param ->
            val draft = paramDrafts[param.uid] ?: param.components.joinToString(", ")
            val parsed = draft.split(",").map { it.trim().toDoubleOrNull() }
            val valid = parsed.size == param.components.size && parsed.all { it?.isFinite() == true }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                BasicTextField(
                    value = draft,
                    onValueChange = { onParamDraftChange(param.uid, it.take(64)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical $section parameter ${group.name} ${param.name}" },
                    decorationBox = { inner -> Box { if (draft.isEmpty()) Text(param.name, color = EditorPalette.muted); inner() } }
                )
                TextButton(
                    enabled = editable && valid,
                    onClick = {
                        val values = parsed.map { it!! }
                        onApplyVec(param.uid, values[0], values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
                    },
                    modifier = Modifier.semantics { contentDescription = "Apply canonical $section parameter ${group.name} ${param.name}" }
                ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
            }
        }
        group.colors.forEach { param ->
            val draft = paramDrafts[param.uid] ?: param.components.joinToString(", ")
            val parsed = draft.split(",").map { it.trim().toDoubleOrNull() }
            val valid = parsed.size == 4 && parsed.all { channel -> channel?.isFinite() == true && channel in 0.0..1.0 }
            val swatchColor = if (valid) {
                Color(parsed[0]!!.toFloat(), parsed[1]!!.toFloat(), parsed[2]!!.toFloat(), parsed[3]!!.toFloat())
            } else EditorPalette.panelRaised
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(swatchColor)
                        .border(BorderStroke(1.dp, EditorPalette.border), RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "Canonical $section color preview ${group.name} ${param.name}" }
                )
                BasicTextField(
                    value = draft,
                    onValueChange = { onParamDraftChange(param.uid, it.take(96)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                    modifier = Modifier.weight(1.5f).semantics { contentDescription = "Canonical $section color parameter ${group.name} ${param.name} RGBA" },
                    decorationBox = { inner -> Box { if (draft.isEmpty()) Text("R, G, B, A", color = EditorPalette.muted); inner() } }
                )
                TextButton(
                    enabled = editable && valid,
                    onClick = {
                        val values = parsed.map { it!! }
                        onApplyColor(param.uid, values[0], values[1], values[2], values[3])
                    },
                    modifier = Modifier.semantics { contentDescription = "Apply canonical $section color parameter ${group.name} ${param.name}" }
                ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                val hasColorKey = playheadFrame in param.keyframes
                TextButton(
                    enabled = editable && (hasColorKey || valid),
                    onClick = {
                        val values = parsed.map { it ?: 0.0 }
                        onColorKeyframeAction(param.uid, playheadFrame, values[0], values[1], values[2], values[3], hasColorKey)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (hasColorKey) "Remove canonical $section color keyframe ${group.name} ${param.name} frame $playheadFrame"
                        else "Add canonical $section color keyframe ${group.name} ${param.name} frame $playheadFrame"
                    }
                ) { Text(if (hasColorKey) "Remove key" else "Add key", color = if (hasColorKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
            }
            Text("RGBA · 0–1 per channel · key at frame $playheadFrame", color = EditorPalette.muted, fontSize = 9.sp)
        }
        group.paths.forEach { param ->
            val draft = pathDrafts[param.uid] ?: param.pathJson
            val vertices = CanonicalShapePathDraft.vertices(draft).orEmpty()
            val vertexDrafts = vertices.mapIndexed { index, vertex ->
                val xText = pathVertexDrafts["${param.uid}:${index}:x"] ?: vertex.x.toString()
                val yText = pathVertexDrafts["${param.uid}:${index}:y"] ?: vertex.y.toString()
                val x = xText.toDoubleOrNull()?.takeIf { it.isFinite() }
                val y = yText.toDoubleOrNull()?.takeIf { it.isFinite() }
                Triple(xText, yText, if (x != null && y != null) CanonicalShapePathDraft.Vertex(x, y) else null)
            }
            val verticesValid = vertexDrafts.all { it.third != null }
            val editedPath = if (verticesValid) CanonicalShapePathDraft.setVertices(draft, vertexDrafts.map { it.third!! }) else null
            val pathForAction = editedPath ?: draft
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        pathDrafts = pathDrafts + (param.uid to it.take(32_768))
                        pathVertexDrafts = pathVertexDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                        pathTangentDrafts = pathTangentDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth().height(84.dp)
                        .background(EditorPalette.panelRaised, RoundedCornerShape(4.dp))
                        .padding(6.dp)
                        .semantics { contentDescription = "Canonical $section path JSON ${group.name} ${param.name}" },
                    decorationBox = { inner -> Box { if (draft.isEmpty()) Text("ShapePath JSON", color = EditorPalette.muted); inner() } }
                )
                Text("VERTICES · ${vertices.size}", color = EditorPalette.muted, fontSize = 10.sp)
                val canvasVerticesState = rememberUpdatedState(vertexDrafts.mapNotNull { it.third })
                val canvasPathState = rememberUpdatedState(pathForAction)
                var selectedCanvasVertex by remember(param.uid) { mutableIntStateOf(-1) }
                val incomingCanvasHandles = CanonicalShapePathDraft.tangentOffsets(pathForAction, "in_tangents").orEmpty()
                val outgoingCanvasHandles = CanonicalShapePathDraft.tangentOffsets(pathForAction, "out_tangents").orEmpty()
                if (verticesValid && vertices.size >= 2) {
                    Canvas(
                        modifier = Modifier.fillMaxWidth().height(144.dp)
                            .background(EditorPalette.panelRaised, RoundedCornerShape(6.dp))
                            .border(1.dp, EditorPalette.border, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = "Interactive ShapePath vertex canvas ${group.name} ${param.name}" }
                            .pointerInput(param.uid, editable, verticesValid) {
                                var dragIndex = -1
                                var lockedMapping: ShapePathCanvasMapping? = null
                                detectDragGestures(
                                    onDragStart = { pointer ->
                                        val points = canvasVerticesState.value
                                        if (!editable || points.size < 2) return@detectDragGestures
                                        val transform = shapePathCanvasMapping(points, size.width.toFloat(), size.height.toFloat())
                                        dragIndex = points.indices.minByOrNull { index ->
                                            val mapped = transform.toCanvas(points[index])
                                            val dx = mapped.x - pointer.x
                                            val dy = mapped.y - pointer.y
                                            dx * dx + dy * dy
                                        } ?: -1
                                        lockedMapping = transform
                                        selectedCanvasVertex = dragIndex
                                    },
                                    onDragEnd = { dragIndex = -1; lockedMapping = null },
                                    onDragCancel = { dragIndex = -1; lockedMapping = null }
                                ) { change, _ ->
                                    if (dragIndex >= 0 && editable) {
                                        change.consume()
                                        val transform = lockedMapping ?: return@detectDragGestures
                                        val current = canvasVerticesState.value
                                        if (dragIndex !in current.indices) return@detectDragGestures
                                        val moved = transform.fromCanvas(change.position)
                                        CanonicalShapePathDraft.moveVertex(canvasPathState.value, dragIndex, moved)?.let { updated ->
                                            pathDrafts = pathDrafts + (param.uid to updated)
                                            pathVertexDrafts = pathVertexDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                                        }
                                    }
                                }
                            }
                    ) {
                        val points = vertexDrafts.mapNotNull { it.third }
                        if (points.size >= 2) {
                            val transform = shapePathCanvasMapping(points, size.width, size.height)
                            for (index in 0 until points.lastIndex) {
                                drawLine(EditorPalette.mint, transform.toCanvas(points[index]), transform.toCanvas(points[index + 1]), strokeWidth = 2f)
                            }
                            points.forEachIndexed { index, vertex ->
                                if (index < incomingCanvasHandles.size) {
                                    val handle = incomingCanvasHandles[index]
                                    drawLine(EditorPalette.amber, transform.toCanvas(vertex), transform.toCanvas(CanonicalShapePathDraft.Vertex(vertex.x + handle.x, vertex.y + handle.y)), strokeWidth = 1f)
                                }
                                if (index < outgoingCanvasHandles.size) {
                                    val handle = outgoingCanvasHandles[index]
                                    drawLine(EditorPalette.violet, transform.toCanvas(vertex), transform.toCanvas(CanonicalShapePathDraft.Vertex(vertex.x + handle.x, vertex.y + handle.y)), strokeWidth = 1f)
                                }
                                drawCircle(
                                    color = if (index == selectedCanvasVertex) EditorPalette.amber else EditorPalette.mint,
                                    radius = if (index == selectedCanvasVertex) 6f else 4.5f,
                                    center = transform.toCanvas(vertex)
                                )
                            }
                        }
                    }
                    Text("Drag a point to edit its composition-space coordinates · selected P${selectedCanvasVertex + 1}", color = EditorPalette.muted, fontSize = 9.sp)
                }
                vertices.forEachIndexed { index, vertex ->
                    val (xText, yText, _) = vertexDrafts[index]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("P${index + 1}", color = EditorPalette.muted, fontSize = 10.sp, modifier = Modifier.width(28.dp))
                        BasicTextField(
                            value = xText,
                            onValueChange = { pathVertexDrafts = pathVertexDrafts + ("${param.uid}:${index}:x" to it.take(32)) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 11.sp),
                            modifier = Modifier.weight(1f).background(EditorPalette.panelRaised, RoundedCornerShape(4.dp)).padding(5.dp)
                                .semantics { contentDescription = "Canonical $section path vertex ${index + 1} X" },
                            decorationBox = { inner -> Box { if (xText.isEmpty()) Text("X", color = EditorPalette.muted); inner() } }
                        )
                        BasicTextField(
                            value = yText,
                            onValueChange = { pathVertexDrafts = pathVertexDrafts + ("${param.uid}:${index}:y" to it.take(32)) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 11.sp),
                            modifier = Modifier.weight(1f).background(EditorPalette.panelRaised, RoundedCornerShape(4.dp)).padding(5.dp)
                                .semantics { contentDescription = "Canonical $section path vertex ${index + 1} Y" },
                            decorationBox = { inner -> Box { if (yText.isEmpty()) Text("Y", color = EditorPalette.muted); inner() } }
                        )
                    }
                }
                // Editable Bezier handle offsets are stored positionally alongside vertices.
                listOf("in_tangents" to "IN", "out_tangents" to "OUT").forEach { (field, label) ->
                    val handleBase = editedPath ?: draft
                    val handles = CanonicalShapePathDraft.tangentOffsets(handleBase, field).orEmpty()
                    Text("$label HANDLES · relative offsets", color = EditorPalette.muted, fontSize = 9.sp)
                    handles.forEachIndexed { index, handle ->
                        val xKey = "${param.uid}:$field:$index:x"
                        val yKey = "${param.uid}:$field:$index:y"
                        val xText = pathTangentDrafts[xKey] ?: handle.x.toString()
                        val yText = pathTangentDrafts[yKey] ?: handle.y.toString()
                        val x = xText.toDoubleOrNull()?.takeIf { it.isFinite() }
                        val y = yText.toDoubleOrNull()?.takeIf { it.isFinite() }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("P${index + 1}", color = EditorPalette.muted, fontSize = 9.sp, modifier = Modifier.width(28.dp))
                            BasicTextField(
                                value = xText,
                                onValueChange = { pathTangentDrafts = pathTangentDrafts + (xKey to it.take(32)) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 10.sp),
                                modifier = Modifier.weight(1f).background(EditorPalette.panelRaised, RoundedCornerShape(4.dp)).padding(4.dp)
                                    .semantics { contentDescription = "Canonical $section path $label handle ${index + 1} X" },
                                decorationBox = { inner -> Box { if (xText.isEmpty()) Text("X", color = EditorPalette.muted); inner() } }
                            )
                            BasicTextField(
                                value = yText,
                                onValueChange = { pathTangentDrafts = pathTangentDrafts + (yKey to it.take(32)) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 10.sp),
                                modifier = Modifier.weight(1f).background(EditorPalette.panelRaised, RoundedCornerShape(4.dp)).padding(4.dp)
                                    .semantics { contentDescription = "Canonical $section path $label handle ${index + 1} Y" },
                                decorationBox = { inner -> Box { if (yText.isEmpty()) Text("Y", color = EditorPalette.muted); inner() } }
                            )
                            TextButton(
                                enabled = editable && x != null && y != null && handles.size == vertices.size,
                                onClick = {
                                    val base = editedPath ?: draft
                                    val current = CanonicalShapePathDraft.tangentOffsets(base, field) ?: return@TextButton
                                    val replacement = current.toMutableList().apply { this[index] = CanonicalShapePathDraft.Vertex(x!!, y!!) }
                                    CanonicalShapePathDraft.setTangentOffsets(base, field, replacement)?.let { updated ->
                                        pathDrafts = pathDrafts + (param.uid to updated)
                                        pathTangentDrafts = pathTangentDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                                    }
                                },
                                modifier = Modifier.semantics { contentDescription = "Set canonical $section path $label handle ${index + 1}" }
                            ) { Text("Set", color = EditorPalette.violet, fontSize = 9.sp) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = editable && editedPath != null && vertices.size < CanonicalShapePathDraft.MAX_VERTICES,
                        onClick = {
                            CanonicalShapePathDraft.appendVertex(pathForAction)?.let { updated ->
                                pathDrafts = pathDrafts + (param.uid to updated)
                                pathVertexDrafts = pathVertexDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                                pathTangentDrafts = pathTangentDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Add canonical $section path vertex ${group.name} ${param.name}" }
                    ) { Text("Add vertex", color = EditorPalette.violet, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable && editedPath != null && CanonicalShapePathDraft.canRemoveLastVertex(pathForAction),
                        onClick = {
                            CanonicalShapePathDraft.removeLastVertex(pathForAction)?.let { updated ->
                                pathDrafts = pathDrafts + (param.uid to updated)
                                pathVertexDrafts = pathVertexDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                                pathTangentDrafts = pathTangentDrafts.filterNot { entry -> entry.key.startsWith("${param.uid}:") }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Remove last canonical $section path vertex ${group.name} ${param.name}" }
                    ) { Text("Remove last", color = EditorPalette.amber, fontSize = 10.sp) }
                    TextButton(
                        enabled = editable && editedPath != null,
                        onClick = { onApplyPath(param.uid, editedPath!!) },
                        modifier = Modifier.semantics { contentDescription = "Apply canonical $section path ${group.name} ${param.name}" }
                    ) { Text("Apply path", color = EditorPalette.violet, fontSize = 10.sp) }
                    val hasPathKey = playheadFrame in param.keyframes
                    TextButton(
                        enabled = editable && (hasPathKey || editedPath != null),
                        onClick = { onPathKeyframeAction(param.uid, playheadFrame, pathForAction, hasPathKey) },
                        modifier = Modifier.semantics {
                            contentDescription = if (hasPathKey) "Remove canonical $section path keyframe ${group.name} ${param.name} frame $playheadFrame"
                            else "Add canonical $section path keyframe ${group.name} ${param.name} frame $playheadFrame"
                        }
                    ) { Text(if (hasPathKey) "Remove key" else "Add key", color = if (hasPathKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("Vertices are composition-space; handle offsets are relative. Feather stays in JSON. Key at frame $playheadFrame", color = EditorPalette.muted, fontSize = 9.sp)
            }
        }
        group.gradients.forEach { param ->
            val stripColors = param.colors.map { stop ->
                val c = stop.components
                Color(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat())
            }
            val stopRgba = param.colors.mapIndexed { index, stop ->
                val text = gradientStopDrafts["${param.uid}:$index"] ?: stop.components.joinToString(", ")
                val parsed = text.split(",").map { it.trim().toDoubleOrNull() }
                val valid = parsed.size == 4 && parsed.all { channel -> channel?.isFinite() == true && channel in 0.0..1.0 }
                Triple(text, parsed, valid)
            }
            val stopOpacity = param.opacities.mapIndexed { index, stop ->
                val text = gradientStopDrafts["${param.uid}:$index:op"] ?: stop.opacity.toString()
                val parsed = text.trim().toDoubleOrNull()
                val valid = parsed?.isFinite() == true && parsed in 0.0..1.0
                Triple(text, parsed, valid)
            }
            val allValid = stopRgba.all { it.third } && stopOpacity.all { it.third }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.weight(1.5f).height(14.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (stripColors.size > 1) Brush.linearGradient(stripColors) else SolidColor(EditorPalette.panelRaised))
                        .border(BorderStroke(1.dp, EditorPalette.border), RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "Canonical $section gradient preview ${group.name} ${param.name}" }
                )
                TextButton(
                    enabled = editable && allValid,
                    onClick = {
                        val updated = CanonicalGradientParam(
                            uid = param.uid,
                            name = param.name,
                            colors = param.colors.mapIndexed { index, stop ->
                                stop.copy(components = stopRgba[index].second.map { it!! })
                            },
                            opacities = param.opacities.mapIndexed { index, stop ->
                                stop.copy(opacity = stopOpacity[index].second!!)
                            },
                            keys = param.keys,
                            keyframes = param.keyframes
                        )
                        onApplyGradient(param.uid, updated.toJson())
                    },
                    modifier = Modifier.semantics { contentDescription = "Apply canonical $section gradient parameter ${group.name} ${param.name}" }
                ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
            }
            param.colors.forEachIndexed { index, stop ->
                val swatch = stop.components
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color(swatch[0].toFloat(), swatch[1].toFloat(), swatch[2].toFloat(), swatch[3].toFloat()))
                            .border(BorderStroke(1.dp, EditorPalette.border), RoundedCornerShape(4.dp))
                    )
                    Text("@${"%.2f".format(stop.position)}", color = EditorPalette.muted, fontSize = 11.sp)
                    BasicTextField(
                        value = stopRgba[index].first,
                        onValueChange = { gradientStopDrafts = gradientStopDrafts + ("${param.uid}:$index" to it.take(48)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical $section gradient stop ${group.name} ${param.name} $index RGBA" },
                        decorationBox = { inner -> Box { if (stopRgba[index].first.isEmpty()) Text("R, G, B, A", color = EditorPalette.muted); inner() } }
                    )
                    BasicTextField(
                        value = stopOpacity.getOrNull(index)?.first ?: "",
                        onValueChange = { gradientStopDrafts = gradientStopDrafts + ("${param.uid}:$index:op" to it.take(8)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                        modifier = Modifier.weight(0.5f).semantics { contentDescription = "Canonical $section gradient stop ${group.name} ${param.name} $index opacity" },
                        decorationBox = { inner -> Box { if ((stopOpacity.getOrNull(index)?.first ?: "").isEmpty()) Text("Op", color = EditorPalette.muted); inner() } }
                    )
                }
            }
            Text("Gradient · stops 0–1 · RGBA 0–1 per channel · opacity 0–1", color = EditorPalette.muted, fontSize = 9.sp)
        }
        group.layerRefs.forEach { param ->
            val heldLayerId = param.keys.lastOrNull { it.frame <= playheadFrame }?.layerId ?: param.layerId
            val selected = heldLayerId?.let { id -> availableLayers.firstOrNull { it.id == id } }
            val hasKeyAtPlayhead = playheadFrame in param.keyframes
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(enabled = editable, onClick = { expandedLayerRefUid = param.uid }, modifier = Modifier.semantics { contentDescription = "Canonical $section layer reference ${group.name} ${param.name}" }) {
                        Text("${param.name}: ${selected?.name ?: "None"}", color = EditorPalette.text, fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = expandedLayerRefUid == param.uid, onDismissRequest = { expandedLayerRefUid = null }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = {
                            expandedLayerRefUid = null
                            if (param.keyframes.isEmpty()) onApplyLayerReference(param.uid, null)
                            else onLayerReferenceKeyframeAction(param.uid, playheadFrame, null, false)
                        }, modifier = Modifier.semantics { contentDescription = "Canonical $section layer option ${group.name} ${param.name} None" })
                        availableLayers.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("${target.name} · ${target.id}", modifier = Modifier.semantics { contentDescription = "Canonical $section layer option ${group.name} ${param.name} ${target.name}" }, color = EditorPalette.text, fontSize = 11.sp) },
                                onClick = {
                                    expandedLayerRefUid = null
                                    if (param.keyframes.isEmpty()) onApplyLayerReference(param.uid, target.id)
                                    else onLayerReferenceKeyframeAction(param.uid, playheadFrame, target.id, false)
                                },
                                modifier = Modifier.semantics { contentDescription = "Canonical $section layer option ${group.name} ${param.name} ${target.name}" }
                            )
                        }
                    }
                }
                TextButton(
                    enabled = editable,
                    onClick = { onLayerReferenceKeyframeAction(param.uid, playheadFrame, heldLayerId, hasKeyAtPlayhead) },
                    modifier = Modifier.semantics { contentDescription = "Canonical $section layer reference key ${group.name} ${param.name} frame $playheadFrame" }
                ) { Text(if (hasKeyAtPlayhead) "Remove key" else "Key", color = EditorPalette.amber, fontSize = 10.sp) }
            }
            Text("Layer Control · discrete hold keys · same composition", color = EditorPalette.muted, fontSize = 9.sp)
        }
        group.bools.forEach { param ->
            // The checkbox shows the value held at the playhead (last hold
            // key at or before it, else the static value); toggling edits
            // the static value while the parameter has no keys, and authors
            // a hold key at the playhead once it does.
            val held = holdValueAt(param.keys, playheadFrame, if (param.value) 1.0 else 0.0) >= 0.5
            val hasBoolKey = playheadFrame in param.keyframes
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(
                    checked = held,
                    enabled = editable,
                    onCheckedChange = { checked ->
                        if (param.keys.isEmpty()) onApplyBool(param.uid, checked)
                        else onBoolKeyframeAction(param.uid, playheadFrame, checked, false)
                    },
                    modifier = Modifier.semantics { contentDescription = "Canonical $section bool parameter ${group.name} ${param.name}" }
                )
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (held) "On" else "Off", color = EditorPalette.text, fontSize = 11.sp)
                TextButton(
                    enabled = editable,
                    onClick = { onBoolKeyframeAction(param.uid, playheadFrame, held, hasBoolKey) },
                    modifier = Modifier.semantics {
                        contentDescription = if (hasBoolKey) "Remove canonical $section bool keyframe ${group.name} ${param.name} frame $playheadFrame"
                        else "Add canonical $section bool keyframe ${group.name} ${param.name} frame $playheadFrame"
                    }
                ) { Text(if (hasBoolKey) "Remove key" else "Add key", color = if (hasBoolKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
            }
            Text("Hold · ${if (held) "on" else "off"} at frame $playheadFrame", color = EditorPalette.muted, fontSize = 9.sp)
        }
        group.popups.forEach { param ->
            val heldIndex = holdValueAt(param.keys, playheadFrame, param.value.toDouble()).roundToInt()
                .coerceIn(0, (param.options.size - 1).coerceAtLeast(0))
            val heldOption = param.options.getOrNull(heldIndex) ?: ""
            val hasPopupKey = playheadFrame in param.keyframes
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(param.name, color = EditorPalette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Box {
                    TextButton(
                        enabled = editable,
                        onClick = { expandedPopupUid = param.uid },
                        modifier = Modifier.semantics { contentDescription = "Canonical $section popup parameter ${group.name} ${param.name}" }
                    ) { Text(heldOption, color = EditorPalette.text, fontSize = 11.sp) }
                    DropdownMenu(
                        expanded = expandedPopupUid == param.uid,
                        onDismissRequest = { expandedPopupUid = null }
                    ) {
                        param.options.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option,
                                        modifier = Modifier.semantics { contentDescription = "Canonical $section popup option ${group.name} ${param.name} $option" },
                                        color = EditorPalette.text,
                                        fontSize = 11.sp
                                    )
                                },
                                onClick = {
                                    expandedPopupUid = null
                                    if (param.keys.isEmpty()) onApplyPopup(param.uid, index)
                                    else onPopupKeyframeAction(param.uid, playheadFrame, index, false)
                                },
                                modifier = Modifier.semantics { contentDescription = "Canonical $section popup option ${group.name} ${param.name} $option" }
                            )
                        }
                    }
                }
                TextButton(
                    enabled = editable,
                    onClick = { onPopupKeyframeAction(param.uid, playheadFrame, heldIndex, hasPopupKey) },
                    modifier = Modifier.semantics {
                        contentDescription = if (hasPopupKey) "Remove canonical $section popup keyframe ${group.name} ${param.name} frame $playheadFrame"
                        else "Add canonical $section popup keyframe ${group.name} ${param.name} frame $playheadFrame"
                    }
                ) { Text(if (hasPopupKey) "Remove key" else "Add key", color = if (hasPopupKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
            }
            Text("Hold · $heldOption at frame $playheadFrame", color = EditorPalette.muted, fontSize = 9.sp)
        }
    }
}

private fun decodeRenderedFrame(bytes: ByteArray): Bitmap? =
    com.codeair.effectcraft.engine.RenderedFrameBitmap.decode(bytes)

@Composable
private fun CompositionSettingsPanel(
    project: ProjectDocument,
    onApply: (Int, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember(project.width) { mutableIntStateOf(project.width) }
    var height by remember(project.height) { mutableIntStateOf(project.height) }
    var fps by remember(project.frameRate) { mutableIntStateOf(project.frameRate) }
    var duration by remember(project.durationFrames) { mutableIntStateOf(project.durationFrames) }

    Column(
        modifier = modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("COMPOSITION SETTINGS", color = EditorPalette.violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Changes apply to this offline draft and participate in undo/redo.", color = EditorPalette.muted, fontSize = 12.sp)
        Text("Width · ${width} px", color = EditorPalette.text, fontSize = 12.sp)
        Slider(value = width.toFloat(), onValueChange = { width = it.roundToInt() }, valueRange = 320f..3840f, modifier = Modifier.semantics { contentDescription = "Composition width" })
        Text("Height · ${height} px", color = EditorPalette.text, fontSize = 12.sp)
        Slider(value = height.toFloat(), onValueChange = { height = it.roundToInt() }, valueRange = 320f..3840f, modifier = Modifier.semantics { contentDescription = "Composition height" })
        Text("Frame rate · ${fps} FPS", color = EditorPalette.text, fontSize = 12.sp)
        Slider(value = fps.toFloat(), onValueChange = { fps = it.roundToInt() }, valueRange = 1f..120f, modifier = Modifier.semantics { contentDescription = "Composition frame rate" })
        Text("Duration · ${duration} frames", color = EditorPalette.text, fontSize = 12.sp)
        Slider(value = duration.toFloat(), onValueChange = { duration = it.roundToInt() }, valueRange = 1f..3600f, modifier = Modifier.semantics { contentDescription = "Composition duration" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancel", color = EditorPalette.muted) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onApply(width, height, fps, duration) }) { Text("Apply settings", color = EditorPalette.violet) }
        }
    }
}

@Composable
private fun PropertyLine(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = EditorPalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = EditorPalette.muted, fontSize = 14.sp)
    }
}

@Composable
private fun PropertyValueLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = EditorPalette.muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = EditorPalette.text, fontSize = 11.sp)
    }
}

@Composable
private fun ToolBar(
    selectedTool: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectTool: (String) -> Unit,
    onOpenInspector: () -> Unit,
    onOpenCompositionSettings: () -> Unit,
    onAddLayer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Select", "Move", "Text", "Shape", "Effects").forEach { tool ->
                FilterChip(
                    selected = selectedTool == tool,
                    onClick = { onSelectTool(tool) },
                    label = { Text(tool, fontSize = 11.sp) }
                )
            }
        }

        TextButton(onClick = onUndo, enabled = canUndo) {
            Text("Undo", color = if (canUndo) EditorPalette.violet else EditorPalette.muted, fontSize = 11.sp)
        }
        TextButton(onClick = onRedo, enabled = canRedo) {
            Text("Redo", color = if (canRedo) EditorPalette.violet else EditorPalette.muted, fontSize = 11.sp)
        }
        TextButton(onClick = onOpenInspector) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = EditorPalette.violet)
            Text("Props", color = EditorPalette.violet, fontSize = 11.sp)
        }
        TextButton(onClick = onOpenCompositionSettings) {
            Text("Comp", color = EditorPalette.violet, fontSize = 11.sp)
        }
        TextButton(onClick = onAddLayer) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = EditorPalette.violet)
            Text("Layer", color = EditorPalette.violet, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TimelinePanel(
    project: ProjectDocument,
    selectedLayerId: String,
    playheadFrame: Int,
    selectedOpacityKeyframes: List<PropertyKeyframe>,
    canonicalTransformKeyframes: List<Int>,
    canonicalTransformDurationFrames: Int,
    canonicalColorKeys: List<CanonicalCurveKey> = emptyList(),
    canonicalColorDurationFrames: Int = canonicalTransformDurationFrames,
    isPlaying: Boolean,
    onSelectLayer: (String) -> Unit,
    onScrub: (Int) -> Unit,
    onMoveCanonicalTransformKeyframe: (Int, Int) -> Unit = { _, _ -> },
    onAddOpacityKeyframe: () -> Unit,
    onRemoveOpacityKeyframe: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(EditorPalette.panel)
            .border(1.dp, EditorPalette.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 13.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Layers, contentDescription = null, tint = EditorPalette.violet, modifier = Modifier.size(17.dp))
            Text(
                "TIMELINE",
                modifier = Modifier.padding(start = 7.dp),
                color = EditorPalette.text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onAddOpacityKeyframe,
                modifier = Modifier.height(28.dp).semantics { contentDescription = "Add opacity keyframe" }
            ) { Text("K+", color = EditorPalette.violet, fontSize = 9.sp) }
            TextButton(
                onClick = onRemoveOpacityKeyframe,
                modifier = Modifier.height(28.dp).semantics { contentDescription = "Remove opacity keyframe" }
            ) { Text("K−", color = EditorPalette.amber, fontSize = 9.sp) }
            Text(
                timecode(playheadFrame, project.frameRate),
                color = EditorPalette.mint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onTogglePlayback, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "Pause timeline" else "Play timeline",
                    tint = EditorPalette.text
                )
            }
        }

        Slider(
            value = playheadFrame.toFloat(),
            onValueChange = { onScrub(it.roundToInt()) },
            valueRange = 0f..(project.durationFrames - 1).coerceAtLeast(1).toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = "Timeline playhead" }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LAYER", color = EditorPalette.muted, fontSize = 8.sp, letterSpacing = 1.1.sp, modifier = Modifier.width(72.dp))
            Text("OPACITY · ${selectedOpacityKeyframes.size} KEYS", color = EditorPalette.amber, fontSize = 8.sp)
            Spacer(Modifier.width(8.dp))
            Text("00:00", color = EditorPalette.muted, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("00:${(project.durationFrames / project.frameRate).toString().padStart(2, '0')}", color = EditorPalette.muted, fontSize = 8.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            project.layers.take(4).forEach { layer ->
                LayerTrack(
                    layer = layer,
                    selected = layer.id == selectedLayerId,
                    keyframeFrames = if (layer.id == selectedLayerId) selectedOpacityKeyframes.map { it.frame } else emptyList(),
                    durationFrames = project.durationFrames,
                    onClick = { onSelectLayer(layer.id) }
                )
            }
            if (project.layers.size > 4) {
                Text("+ ${project.layers.size - 4} more layers", color = EditorPalette.muted, fontSize = 9.sp)
            }
            if (canonicalTransformKeyframes.isNotEmpty()) {
                CanonicalTransformTrack(canonicalTransformKeyframes, canonicalTransformDurationFrames, playheadFrame, onScrub, onMoveCanonicalTransformKeyframe)
            }
            if (canonicalColorKeys.isNotEmpty()) {
                CanonicalColorTrack(canonicalColorKeys, canonicalColorDurationFrames, playheadFrame, onScrub)
            }
        }
    }
}

@Composable
private fun CanonicalTransformTrack(keyframes: List<Int>, durationFrames: Int, currentFrame: Int, onScrub: (Int) -> Unit, onMoveKeyframe: (Int, Int) -> Unit = { _, _ -> }) {
    val frames = keyframes.filter { it >= 0 }.distinct().sorted()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("TRANSFORM", modifier = Modifier.width(107.dp), color = EditorPalette.mint, fontSize = 9.sp)
        Box(
            modifier = Modifier.weight(1f).height(17.dp)
                .clip(RoundedCornerShape(5.dp)).background(Color(0xFF202633))
                .semantics {
                    contentDescription = "Canonical transform keyframes: ${frames.joinToString(", ")}"
                    onClick("Seek to nearest transform keyframe") {
                        val target = frames.minByOrNull { kotlin.math.abs(it - currentFrame) } ?: currentFrame
                        onScrub(target.coerceIn(0, (durationFrames - 1).coerceAtLeast(0)))
                        true
                    }
                }
                .pointerInput(frames, durationFrames) {
                    detectTapGestures { tap ->
                        val frameRange = (durationFrames - 1).coerceAtLeast(1)
                        val tappedFrame = (tap.x / size.width.coerceAtLeast(1) * frameRange).roundToInt()
                        val nearestKey = frames.minByOrNull { kotlin.math.abs(it - tappedFrame) } ?: tappedFrame
                        onScrub(nearestKey.coerceIn(0, (durationFrames - 1).coerceAtLeast(0)))
                    }
                }
                .pointerInput(frames, durationFrames) {
                    // Dragging a marker moves that key on release; a tap is
                    // left to the tap detector above, and a drag that starts
                    // away from every marker grabs nothing.
                    var grabbedFrame: Int? = null
                    var lastX = 0f
                    detectDragGestures(
                        onDragStart = { start ->
                            grabbedFrame = com.codeair.effectcraft.data.CanonicalKeyframeDrag.grabsKey(
                                frames, start.x, size.width.toFloat(), durationFrames
                            )
                            lastX = start.x
                        },
                        onDragEnd = {
                            val source = grabbedFrame
                            grabbedFrame = null
                            if (source != null) {
                                val target = com.codeair.effectcraft.data.CanonicalKeyframeDrag.frameFromX(
                                    lastX, size.width.toFloat(), durationFrames
                                )
                                if (target != source) onMoveKeyframe(source, target)
                            }
                        },
                        onDragCancel = { grabbedFrame = null },
                        onDrag = { change, _ ->
                            lastX = change.position.x
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val frameRange = (durationFrames - 1).coerceAtLeast(1).toFloat()
                frames.forEach { frame ->
                    val x = frame.coerceIn(0, durationFrames - 1).toFloat() / frameRange * size.width
                    drawCircle(
                        color = EditorPalette.mint,
                        radius = 3.2f,
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f)
                    )
                }
            }
        }
    }
}

/**
 * Timeline COLOR lane (owner pick 2026-11-06): one dot per color keyframe
 * of the selected canonical layer, drawn as a swatch of that key's own
 * RGBA value. Tap seeks the nearest key, like the TRANSFORM lane. There is
 * no drag-to-move here yet: moving a color key in time has no native move
 * path (remove + re-add), so the lane is display + seek only.
 */
@Composable
private fun CanonicalColorTrack(keys: List<CanonicalCurveKey>, durationFrames: Int, currentFrame: Int, onScrub: (Int) -> Unit) {
    val frames = keys.sortedBy { it.frame }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("COLOR", modifier = Modifier.width(107.dp), color = EditorPalette.amber, fontSize = 9.sp)
        Box(
            modifier = Modifier.weight(1f).height(17.dp)
                .clip(RoundedCornerShape(5.dp)).background(Color(0xFF202633))
                .semantics {
                    contentDescription = "Canonical color keyframes: " + frames.joinToString(", ") {
                        "${it.frame}: ${com.codeair.effectcraft.data.rgbaToHex(it.components)}"
                    }
                    onClick("Seek to nearest color keyframe") {
                        val target = frames.minByOrNull { kotlin.math.abs(it.frame - currentFrame) }?.frame ?: currentFrame
                        onScrub(target.coerceIn(0, (durationFrames - 1).coerceAtLeast(0)))
                        true
                    }
                }
                .pointerInput(frames, durationFrames) {
                    detectTapGestures { tap ->
                        val frameRange = (durationFrames - 1).coerceAtLeast(1)
                        val tappedFrame = (tap.x / size.width.coerceAtLeast(1) * frameRange).roundToInt()
                        val nearestKey = frames.minByOrNull { kotlin.math.abs(it.frame - tappedFrame) }?.frame ?: tappedFrame
                        onScrub(nearestKey.coerceIn(0, (durationFrames - 1).coerceAtLeast(0)))
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val frameRange = (durationFrames - 1).coerceAtLeast(1).toFloat()
                frames.forEach { key ->
                    val x = key.frame.coerceIn(0, durationFrames - 1).toFloat() / frameRange * size.width
                    val rgba = key.components
                    val swatch = Color(
                        red = rgba.getOrNull(0)?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        green = rgba.getOrNull(1)?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        blue = rgba.getOrNull(2)?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        alpha = rgba.getOrNull(3)?.toFloat()?.coerceIn(0f, 1f) ?: 1f
                    )
                    drawCircle(
                        color = swatch,
                        radius = 6f,
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f)
                    )
                    drawCircle(
                        color = Color(0xFF0B0E13),
                        radius = 6f,
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                    )
                }
            }
        }
    }
}

/**
 * Curve graph editor for one canonical property track. Vector properties
 * plot their first component. Keys are drawn as dots, Bezier handles as
 * smaller dots joined to their key; dragging a key dot moves the key in
 * time, dragging a handle dot reshapes that side's Bezier ease. Commits go
 * through the native curve bridge, so the graph, the metadata and the
 * renderer always agree. This section never touches the timeline marker
 * lane, whose tap-to-seek behaviour is unchanged.
 */
@Composable
private fun CurveGraphSection(
    layer: CanonicalLayerInfo,
    composition: CanonicalCompositionInfo,
    previewFrame: Int,
    editable: Boolean,
    onMoveCurveKey: (Long, Long, String, Int, Int) -> Unit,
    onSetCurveHandle: (Long, Long, String, Int, String, Double, Double) -> Unit,
    onAddCurveKey: (Long, Long, String, Int, Double, Double, Double) -> Unit,
    onRemoveCurveKey: (Long, Long, String, Int) -> Unit
) {
    var selectedSelector by remember(layer.id) { mutableStateOf("transform:position") }
    val paramOptions = (layer.shapeItems + layer.effects).flatMap { group ->
        group.scalars.map { "param:${it.uid}" to "${group.name} · ${it.name}" } +
            group.vecs.map { "param:${it.uid}" to "${group.name} · ${it.name}" }
    }
    val options = listOf(
        "transform:position" to "Position X",
        "transform:scale" to "Scale X",
        "transform:rotation" to "Rotation",
        "transform:anchor" to "Anchor X",
        "transform:orientation" to "Orientation X",
        "transform:rotationX" to "X Rotation",
        "transform:rotationY" to "Y Rotation",
        "transform:opacity" to "Opacity"
    ) + paramOptions
    val selector = if (options.any { it.first == selectedSelector }) selectedSelector else options.first().first
    val label = options.firstOrNull { it.first == selector }?.second ?: selector
    val track = remember(layer, selector) { curveTrackFor(layer, selector) }
    val fps = (composition.frameRateNum.toDouble() / composition.frameRateDen.coerceAtLeast(1).toDouble())
        .takeIf { it > 0.0 && it.isFinite() } ?: 30.0
    val durationFrames = composition.durationFrames.coerceAtLeast(1)
    var graphWidth by remember { mutableFloatStateOf(0f) }
    var graphHeight by remember { mutableFloatStateOf(0f) }
    var dragPreview by remember(layer, selector) { mutableStateOf<CurveTrack?>(null) }
    var dragKeyFrame by remember(layer, selector) { mutableStateOf<Int?>(null) }
    var dragHandleFrame by remember(layer, selector) { mutableStateOf<Int?>(null) }
    var dragHandleSide by remember(layer, selector) { mutableStateOf<String?>(null) }
    var dragTargetFrame by remember(layer, selector) { mutableStateOf<Int?>(null) }
    var dragHandle by remember(layer, selector) { mutableStateOf<CurveHandle?>(null) }
    var selectedCurveHandle by remember(layer, selector) { mutableStateOf<Pair<Int, String>?>(null) }
    var preciseHandlePreview by remember(layer, selector) { mutableStateOf<CurveHandle?>(null) }
    val displayTrack = dragPreview ?: track

    val values = displayTrack.keys.map { it.value }
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 0.0
    val span = (maxValue - minValue).let { if (it == 0.0) maxOf(1.0, abs(maxValue) * 0.2) else it }
    val lowValue = minValue - span * 0.15
    val highValue = maxValue + span * 0.15
    fun frameToX(frame: Double): Float {
        if (graphWidth <= 0f) return 0f
        return (frame / durationFrames.toDouble()).toFloat() * graphWidth
    }
    fun valueToY(value: Double): Float {
        if (graphHeight <= 0f) return 0f
        return (1.0 - (value - lowValue) / (highValue - lowValue)).toFloat() * graphHeight
    }
    fun xToFrame(x: Float): Int {
        if (graphWidth <= 0f) return 0
        return (x / graphWidth * durationFrames).toInt().coerceIn(0, durationFrames - 1)
    }
    fun yToValue(y: Float): Double {
        if (graphHeight <= 0f) return lowValue
        return highValue - (y / graphHeight) * (highValue - lowValue)
    }
    fun handlePoint(key: CurveKey, neighbor: CurveKey, handle: CurveHandle, side: String): Offset {
        val segmentFrames = abs(neighbor.frame - key.frame).toDouble()
        val segmentSeconds = segmentFrames / fps
        return if (side == "outgoing") {
            Offset(
                frameToX(key.frame + handle.influence * segmentFrames),
                valueToY(key.value + handle.speed * segmentSeconds * handle.influence)
            )
        } else {
            Offset(
                frameToX(key.frame - handle.influence * segmentFrames),
                valueToY(key.value - handle.speed * segmentSeconds * handle.influence)
            )
        }
    }

    Text("Curve graph · $label", color = EditorPalette.text, fontSize = 12.sp)
    // A spatial track graphs one component only: the ease is a single speed
    // along the motion path (the first ease entry), and per-dimension
    // handles don't apply — the path itself is shown by the overlay.
    if (selector == "transform:position" || selector == "transform:anchor") {
        Text("Spatial track: one speed along the motion path, from the first ease entry. See the motion-path overlay for the path itself.", color = EditorPalette.muted, fontSize = 10.sp)
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).testTag("curve-selector-row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (optionSelector, optionLabel) ->
            FilterChip(
                selected = selector == optionSelector,
                onClick = { selectedSelector = optionSelector },
                enabled = editable,
                modifier = Modifier
                    .testTag("curve-selector-${optionSelector.replace(':', '-')}")
                    .semantics { contentDescription = "Curve selector $optionLabel" },
                label = { Text(optionLabel, fontSize = 10.sp) }
            )
        }
    }
    if (displayTrack.keys.isEmpty()) {
        Text("No keys on this track yet. Add one at the playhead.", color = EditorPalette.muted, fontSize = 10.sp)
    } else {
        val sampled = remember(displayTrack, fps) {
            BezierCurveEditor.sample(displayTrack, samplesPerSegment = 24, framesPerSecond = fps)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF202633))
                .onSizeChanged { size ->
                    graphWidth = size.width.toFloat()
                    graphHeight = size.height.toFloat()
                }
                .semantics {
                    contentDescription = "Curve graph for $label with keys " +
                        displayTrack.keys.joinToString(", ") { "${it.frame}: ${it.value}" }
                    // TalkBack users cannot drag Canvas points. Expose the same
                    // key-move operation as a custom action so every curve key
                    // can be moved to the shared playhead without pointer input.
                    customActions = if (editable) track.keys.flatMap { key ->
                        listOf(
                            CustomAccessibilityAction(
                                "Move key at frame ${key.frame} to playhead frame $previewFrame"
                            ) {
                                if (key.frame == previewFrame) false else {
                                    onMoveCurveKey(composition.id, layer.id, selector, key.frame, previewFrame)
                                    true
                                }
                            },
                            CustomAccessibilityAction("Delete curve key at frame ${key.frame}") {
                                onRemoveCurveKey(composition.id, layer.id, selector, key.frame)
                                true
                            }
                        )
                    } else emptyList()
                }
                .pointerInput(track, graphWidth, graphHeight, editable, previewFrame) {
                    detectTapGestures(
                        onTap = { tap ->
                            if (!editable) selectedCurveHandle = null else {
                                var nearestFrame: Int? = null
                                var nearestSide: String? = null
                                var nearestDistance = 28f
                                track.keys.forEachIndexed { index, key ->
                                    val outgoing = key.outHandle ?: if (key.outInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                                    if (outgoing != null && index < track.keys.lastIndex) {
                                        val distance = (handlePoint(key, track.keys[index + 1], outgoing, "outgoing") - tap).getDistance()
                                        if (distance < nearestDistance) {
                                            nearestDistance = distance
                                            nearestFrame = key.frame
                                            nearestSide = "outgoing"
                                        }
                                    }
                                    val incoming = key.inHandle ?: if (key.inInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                                    if (incoming != null && index > 0) {
                                        val distance = (handlePoint(key, track.keys[index - 1], incoming, "incoming") - tap).getDistance()
                                        if (distance < nearestDistance) {
                                            nearestDistance = distance
                                            nearestFrame = key.frame
                                            nearestSide = "incoming"
                                        }
                                    }
                                }
                                selectedCurveHandle = if (nearestFrame != null && nearestSide != null) nearestFrame!! to nearestSide!! else null
                            }
                        },
                        onLongPress = { press ->
                            if (editable) {
                                val nearest = track.keys.minByOrNull { key ->
                                    (Offset(frameToX(key.frame.toDouble()), valueToY(key.value)) - press).getDistance()
                                }
                                if (nearest != null &&
                                    (Offset(frameToX(nearest.frame.toDouble()), valueToY(nearest.value)) - press).getDistance() <= 28f
                                ) {
                                    onRemoveCurveKey(composition.id, layer.id, selector, nearest.frame)
                                }
                            }
                        }
                    )
                }
                .pointerInput(track, graphWidth, graphHeight) {
                    detectDragGestures(
                        onDragStart = { start ->
                            if (!editable) return@detectDragGestures
                            var bestKey: CurveKey? = null
                            var bestKeyDistance = 28f
                            track.keys.forEach { key ->
                                val point = Offset(frameToX(key.frame.toDouble()), valueToY(key.value))
                                val distance = (point - start).getDistance()
                                if (distance < bestKeyDistance) {
                                    bestKeyDistance = distance
                                    bestKey = key
                                }
                            }
                            if (bestKey != null) {
                                dragKeyFrame = bestKey!!.frame
                                dragTargetFrame = bestKey!!.frame
                                return@detectDragGestures
                            }
                            var bestHandle: CurveHandle? = null
                            var bestHandleFrame: Int? = null
                            var bestHandleSide: String? = null
                            var bestHandleDistance = 28f
                            track.keys.forEachIndexed { index, key ->
                                val outgoing = key.outHandle ?: if (key.outInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                                if (outgoing != null && index < track.keys.lastIndex) {
                                    val point = handlePoint(key, track.keys[index + 1], outgoing, "outgoing")
                                    val distance = (point - start).getDistance()
                                    if (distance < bestHandleDistance) {
                                        bestHandleDistance = distance
                                        bestHandle = outgoing
                                        bestHandleFrame = key.frame
                                        bestHandleSide = "outgoing"
                                    }
                                }
                                val incoming = key.inHandle ?: if (key.inInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                                if (incoming != null && index > 0) {
                                    val point = handlePoint(key, track.keys[index - 1], incoming, "incoming")
                                    val distance = (point - start).getDistance()
                                    if (distance < bestHandleDistance) {
                                        bestHandleDistance = distance
                                        bestHandle = incoming
                                        bestHandleFrame = key.frame
                                        bestHandleSide = "incoming"
                                    }
                                }
                            }
                            dragHandle = bestHandle
                            dragHandleFrame = bestHandleFrame
                            dragHandleSide = bestHandleSide
                        },
                        onDragEnd = {
                            val keyFrame = dragKeyFrame
                            val target = dragTargetFrame
                            if (keyFrame != null && target != null && target != keyFrame) {
                                onMoveCurveKey(composition.id, layer.id, selector, keyFrame, target)
                            }
                            val handleFrame = dragHandleFrame
                            val handleSide = dragHandleSide
                            val handle = dragHandle
                            if (handleFrame != null && handleSide != null && handle != null) {
                                onSetCurveHandle(composition.id, layer.id, selector, handleFrame, handleSide, handle.speed, handle.influence)
                            }
                            dragPreview = null
                            dragKeyFrame = null
                            dragHandleFrame = null
                            dragHandleSide = null
                            dragTargetFrame = null
                            dragHandle = null
                        },
                        onDrag = { change, _ ->
                            val keyFrame = dragKeyFrame
                            if (keyFrame != null) {
                                val target = xToFrame(change.position.x)
                                dragTargetFrame = target
                                dragPreview = runCatching { BezierCurveEditor.move(track, keyFrame, target) }.getOrNull()
                                return@detectDragGestures
                            }
                            val handleFrame = dragHandleFrame
                            val handleSide = dragHandleSide
                            if (handleFrame != null && handleSide != null) {
                                val key = track.keys.firstOrNull { it.frame == handleFrame } ?: return@detectDragGestures
                                val index = track.keys.indexOf(key)
                                val neighbor = if (handleSide == "outgoing") {
                                    track.keys.getOrNull(index + 1)
                                } else {
                                    track.keys.getOrNull(index - 1)
                                } ?: return@detectDragGestures
                                val next = BezierCurveEditor.handleFromPointer(
                                    keyFrame = key.frame,
                                    keyValue = key.value,
                                    neighborFrame = neighbor.frame,
                                    neighborValue = neighbor.value,
                                    pointerFrame = xToFrame(change.position.x).toDouble(),
                                    pointerValue = yToValue(change.position.y),
                                    framesPerSecond = fps,
                                    side = handleSide
                                )
                                dragHandle = next
                                dragPreview = runCatching {
                                    BezierCurveEditor.withHandle(track, handleFrame, handleSide, next)
                                }.getOrNull()
                            }
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for (index in 0 until sampled.lastIndex) {
                    val from = sampled[index]
                    val to = sampled[index + 1]
                    drawLine(
                        color = EditorPalette.mint,
                        start = Offset(frameToX(from.frame), valueToY(from.value)),
                        end = Offset(frameToX(to.frame), valueToY(to.value)),
                        strokeWidth = 2f
                    )
                }
                displayTrack.keys.forEachIndexed { index, key ->
                    val center = Offset(frameToX(key.frame.toDouble()), valueToY(key.value))
                    val outgoing = key.outHandle ?: if (key.outInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                    if (outgoing != null && index < displayTrack.keys.lastIndex) {
                        val point = handlePoint(key, displayTrack.keys[index + 1], outgoing, "outgoing")
                        drawLine(color = EditorPalette.violet, start = center, end = point, strokeWidth = 1.5f)
                        drawCircle(color = EditorPalette.violet, radius = 5f, center = point)
                        if (selectedCurveHandle == (key.frame to "outgoing")) {
                            drawCircle(color = Color.White, radius = 8f, center = point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                        }
                    }
                    val incoming = key.inHandle ?: if (key.inInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
                    if (incoming != null && index > 0) {
                        val point = handlePoint(key, displayTrack.keys[index - 1], incoming, "incoming")
                        drawLine(color = EditorPalette.amber, start = center, end = point, strokeWidth = 1.5f)
                        drawCircle(color = EditorPalette.amber, radius = 5f, center = point)
                        if (selectedCurveHandle == (key.frame to "incoming")) {
                            drawCircle(color = Color.White, radius = 8f, center = point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                        }
                    }
                    drawCircle(color = EditorPalette.mint, radius = 6f, center = center)
                }
            }
        }
        Text(
            "Keys · ${displayTrack.keys.joinToString(", ") { "${it.frame}: ${it.value}" }}",
            color = EditorPalette.muted,
            fontSize = 10.sp
        )
    }
    val selectedHandleSpec = selectedCurveHandle?.let { (frame, side) ->
        val index = displayTrack.keys.indexOfFirst { it.frame == frame }
        val key = displayTrack.keys.getOrNull(index)
        val neighbor = if (side == "outgoing") displayTrack.keys.getOrNull(index + 1) else displayTrack.keys.getOrNull(index - 1)
        val handle = key?.let {
            if (side == "outgoing") it.outHandle ?: if (it.outInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
            else it.inHandle ?: if (it.inInterp == CurveInterp.BEZIER) BezierCurveEditor.defaultHandle else null
        }
        if (key != null && neighbor != null && handle != null) Triple(key, neighbor, handle) else null
    }
    if (selectedCurveHandle != null && selectedHandleSpec != null) {
        val (selectedKey, selectedNeighbor, selectedHandle) = selectedHandleSpec
        val selectedSide = selectedCurveHandle!!.second
        val selectedFrame = selectedCurveHandle!!.first
        val segmentFrames = abs(selectedNeighbor.frame - selectedKey.frame).toDouble().coerceAtLeast(1.0)
        val maxSpeed = maxOf(abs(selectedNeighbor.value - selectedKey.value) * fps / segmentFrames * 2.0, 10.0).coerceAtMost(1_000_000.0)
        Text("Selected $selectedSide handle · frame $selectedFrame", color = EditorPalette.text, fontSize = 10.sp)
        Text("Speed ${String.format(Locale.US, "%.2f", selectedHandle.speed)}", color = EditorPalette.muted, fontSize = 9.sp)
        Slider(
            value = selectedHandle.speed.toFloat().coerceIn(-maxSpeed.toFloat(), maxSpeed.toFloat()),
            onValueChange = { value ->
                val next = CurveHandle(value.toDouble(), selectedHandle.influence)
                preciseHandlePreview = next
                dragPreview = runCatching { BezierCurveEditor.withHandle(displayTrack, selectedFrame, selectedSide, next) }.getOrNull()
            },
            onValueChangeFinished = {
                preciseHandlePreview?.let { next ->
                    onSetCurveHandle(composition.id, layer.id, selector, selectedFrame, selectedSide, next.speed, next.influence)
                }
                dragPreview = null
                preciseHandlePreview = null
            },
            valueRange = -maxSpeed.toFloat()..maxSpeed.toFloat(),
            modifier = Modifier.testTag("curve-handle-speed").semantics { contentDescription = "Curve handle speed" }
        )
        Text("Influence ${String.format(Locale.US, "%.2f", selectedHandle.influence)}", color = EditorPalette.muted, fontSize = 9.sp)
        Slider(
            value = selectedHandle.influence.toFloat(),
            onValueChange = { value ->
                val next = CurveHandle(selectedHandle.speed, value.toDouble())
                preciseHandlePreview = next
                dragPreview = runCatching { BezierCurveEditor.withHandle(track, selectedFrame, selectedSide, next) }.getOrNull()
            },
            onValueChangeFinished = {
                preciseHandlePreview?.let { next ->
                    onSetCurveHandle(composition.id, layer.id, selector, selectedFrame, selectedSide, next.speed, next.influence)
                }
                dragPreview = null
                preciseHandlePreview = null
            },
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.testTag("curve-handle-influence").semantics { contentDescription = "Curve handle influence" }
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            enabled = editable,
            onClick = {
                val current = curveCurrentValue(layer, selector)
                onAddCurveKey(composition.id, layer.id, selector, previewFrame, current.first, current.second, current.third)
            },
            modifier = Modifier.semantics { contentDescription = "Add $label keyframe at playhead" }
        ) { Text("Add key at playhead", color = EditorPalette.violet, fontSize = 10.sp) }
        Text(
            "Drag a key dot to move it in time; drag a handle dot to reshape its Bezier ease. Vector tracks plot the first component.",
            color = EditorPalette.muted,
            fontSize = 10.sp
        )
    }
}

private fun curveTrackFor(layer: CanonicalLayerInfo, selector: String): CurveTrack {
    fun fromCurves(curves: List<com.codeair.effectcraft.data.CanonicalCurveKey>): List<CurveKey> {
        return curves.map { key ->
            CurveKey(
                frame = key.frame,
                value = key.value,
                inHandle = if (key.inInterp == "Bezier") CurveHandle(key.inSpeed, key.inInfluence.coerceIn(0.0, 1.0)) else null,
                outHandle = if (key.outInterp == "Bezier") CurveHandle(key.outSpeed, key.outInfluence.coerceIn(0.0, 1.0)) else null,
                inInterp = when (key.inInterp) {
                    "Bezier" -> CurveInterp.BEZIER
                    "Hold" -> CurveInterp.HOLD
                    else -> CurveInterp.LINEAR
                },
                outInterp = when (key.outInterp) {
                    "Bezier" -> CurveInterp.BEZIER
                    "Hold" -> CurveInterp.HOLD
                    else -> CurveInterp.LINEAR
                },
                roving = key.roving,
                spatialIn = key.spatialIn,
                spatialOut = key.spatialOut,
                spatialAuto = key.spatialAuto
            )
        }.sortedBy { it.frame }
    }
    val keys = when (selector) {
        "transform:position" -> layer.transformCurves["position"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["position"].orEmpty().map { CurveKey(it, layer.positionX) }
        "transform:scale" -> layer.transformCurves["scale"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["scale"].orEmpty().map { CurveKey(it, layer.scaleX) }
        "transform:rotation" -> layer.transformCurves["rotation"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["rotation"].orEmpty().map { CurveKey(it, layer.rotationDegrees) }
        "transform:anchor" -> layer.transformCurves["anchor"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["anchor"].orEmpty().map { CurveKey(it, layer.anchorX) }
        "transform:orientation" -> layer.transformCurves["orientation"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["orientation"].orEmpty().map { CurveKey(it, layer.orientationX) }
        "transform:rotationX" -> layer.transformCurves["rotationX"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["rotationX"].orEmpty().map { CurveKey(it, layer.rotationXDegrees) }
        "transform:rotationY" -> layer.transformCurves["rotationY"]?.let { fromCurves(it) }
            ?: layer.transformKeyframes["rotationY"].orEmpty().map { CurveKey(it, layer.rotationYDegrees) }
        "transform:opacity" -> if (layer.opacityCurves.isNotEmpty()) fromCurves(layer.opacityCurves)
            else layer.opacityKeys.map { CurveKey(it.frame, it.value.toDouble()) }
        else -> {
            val uid = selector.removePrefix("param:").toLongOrNull()
            val scalar = (layer.shapeItems + layer.effects).flatMap { it.scalars }.firstOrNull { it.uid == uid }
            val vec = if (scalar == null) {
                (layer.shapeItems + layer.effects).flatMap { it.vecs }.firstOrNull { it.uid == uid }
            } else null
            when {
                scalar != null -> fromCurves(scalar.keys)
                vec != null -> fromCurves(vec.keys)
                else -> emptyList()
            }
        }
    }
    return CurveTrack(selector, keys)
}

private fun curveCurrentValue(layer: CanonicalLayerInfo, selector: String): Triple<Double, Double, Double> {
    return when (selector) {
        "transform:position" -> Triple(layer.positionX, layer.positionY, 0.0)
        "transform:scale" -> Triple(layer.scaleX, layer.scaleY, 0.0)
        "transform:rotation" -> Triple(layer.rotationDegrees, 0.0, 0.0)
        "transform:anchor" -> Triple(layer.anchorX, layer.anchorY, 0.0)
        "transform:orientation" -> Triple(layer.orientationX, layer.orientationY, layer.orientationZ)
        "transform:rotationX" -> Triple(layer.rotationXDegrees, 0.0, 0.0)
        "transform:rotationY" -> Triple(layer.rotationYDegrees, 0.0, 0.0)
        "transform:opacity" -> Triple(layer.opacityPercent, 0.0, 0.0)
        else -> {
            val uid = selector.removePrefix("param:").toLongOrNull()
            val scalar = (layer.shapeItems + layer.effects).flatMap { it.scalars }.firstOrNull { it.uid == uid }
            if (scalar != null) return Triple(scalar.value, 0.0, 0.0)
            val vec = (layer.shapeItems + layer.effects).flatMap { it.vecs }.firstOrNull { it.uid == uid }
            if (vec != null) {
                Triple(
                    vec.components.getOrElse(0) { 0.0 },
                    vec.components.getOrElse(1) { 0.0 },
                    vec.components.getOrElse(2) { 0.0 }
                )
            } else Triple(0.0, 0.0, 0.0)
        }
    }
}

@Composable
private fun LayerTrack(
    layer: LayerDocument,
    selected: Boolean,
    keyframeFrames: List<Int>,
    durationFrames: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0xFF20283A) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (selected) {
                    "Selected timeline layer: ${layer.name}"
                } else {
                    "Timeline layer: ${layer.name}"
                }
            }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            layer.name,
            modifier = Modifier.width(107.dp),
            color = if (selected) EditorPalette.text else EditorPalette.muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF202633))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val frameRange = (durationFrames - 1).coerceAtLeast(1).toFloat()
                keyframeFrames.forEach { frame ->
                    val x = frame.coerceIn(0, durationFrames - 1).toFloat() / frameRange * size.width
                    drawCircle(
                        color = EditorPalette.amber,
                        radius = 3.2f,
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (layer.kind == "Footage") 1f else 0.72f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) EditorPalette.violet.copy(alpha = 0.9f) else Color(0xFF62708B).copy(alpha = 0.85f))
            )
        }
    }
}

private fun timecode(frame: Int, fps: Int): String {
    val safeFps = fps.coerceAtLeast(1)
    val seconds = frame / safeFps
    val frames = frame % safeFps
    return String.format(
        Locale.US,
        "%02d:%02d:%02d:%02d",
        seconds / 3600,
        (seconds / 60) % 60,
        seconds % 60,
        frames
    )
}
