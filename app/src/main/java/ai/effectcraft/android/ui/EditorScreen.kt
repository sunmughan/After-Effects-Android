package ai.effectcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.effectcraft.android.data.CanonicalProjectInfo
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectDocument
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

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
    isPlaying: Boolean,
    saveMessage: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onValidateEcproj: () -> Unit,
    onExportEcproj: () -> Unit,
    onNewProject: () -> Unit,
    onAddLayer: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onDeleteLayer: () -> Unit,
    onMoveLayer: (Int) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onRenameLayer: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScrub: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onApplyComposition: (Int, Int, Int, Int) -> Unit,
    canonicalProject: CanonicalProjectInfo?,
    canEditCanonicalProject: Boolean,
    onRenameCanonicalLayer: (Long, Long, String) -> Unit,
    onAddCanonicalTextLayer: (Long, String) -> Unit,
    onSetCanonicalLayerVisibility: (Long, Long, Boolean) -> Unit,
    onMoveCanonicalLayer: (Long, Long, Int) -> Unit,
    onDeleteCanonicalLayer: (Long, Long) -> Unit,
    onSetCanonicalLayerOpacity: (Long, Long, Double) -> Unit,
    onSetCanonicalLayerPosition: (Long, Long, Double, Double) -> Unit,
    onSetCanonicalLayerScale: (Long, Long, Double, Double) -> Unit,
    onSetCanonicalLayerRotation: (Long, Long, Double) -> Unit,
    onRelinkCanonicalAsset: (Long) -> Unit,
    onRenderCanonicalFrame: (Long, Long) -> ByteArray?
) {
    val selectedTool = remember { mutableStateOf("Select") }
    var showPhoneInspector by remember { mutableStateOf(false) }
    var showCompositionSettings by remember { mutableStateOf(false) }
    var showCanonicalEditor by remember { mutableStateOf(false) }

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
                onValidateEcproj = onValidateEcproj,
                onExportEcproj = onExportEcproj,
                onNewProject = onNewProject,
                hasCanonicalProject = canonicalProject != null,
                onOpenCanonicalEditor = { showCanonicalEditor = true }
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
                            .fillMaxHeight()
                    )
                    InspectorPanel(
                        modifier = Modifier
                            .width(276.dp)
                            .fillMaxHeight(),
                        layer = selectedLayer,
                        onOpacityChange = onOpacityChange,
                        onDeleteLayer = onDeleteLayer,
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
                        .padding(horizontal = 14.dp, vertical = 4.dp)
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
                isPlaying = isPlaying,
                onSelectLayer = onSelectLayer,
                onScrub = onScrub,
                onTogglePlayback = onTogglePlayback,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTabletLayout) 222.dp else 204.dp)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
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
                    onRenameLayer = onRenameCanonicalLayer,
                    onAddTextLayer = onAddCanonicalTextLayer,
                    onSetLayerVisibility = onSetCanonicalLayerVisibility,
                    onMoveLayer = onMoveCanonicalLayer,
                    onDeleteLayer = onDeleteCanonicalLayer,
                    onSetLayerOpacity = onSetCanonicalLayerOpacity,
                    onSetLayerPosition = onSetCanonicalLayerPosition,
                    onSetLayerScale = onSetCanonicalLayerScale,
                    onSetLayerRotation = onSetCanonicalLayerRotation,
                    onRelinkAsset = onRelinkCanonicalAsset,
                    onRenderFrame = onRenderCanonicalFrame,
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
                    onDeleteLayer = onDeleteLayer,
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
    onValidateEcproj: () -> Unit,
    onExportEcproj: () -> Unit,
    onNewProject: () -> Unit,
    hasCanonicalProject: Boolean,
    onOpenCanonicalEditor: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        if (maxWidth < 620.dp) {
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
                        border = androidx.foundation.BorderStroke(1.dp, Color(0xFF24453E))
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
                    IconButton(onClick = onValidateEcproj, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Validate .ecproj", tint = EditorPalette.text)
                    }
                    IconButton(onClick = onOpenCanonicalEditor, enabled = hasCanonicalProject, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit imported .ecproj", tint = if (hasCanonicalProject) EditorPalette.mint else EditorPalette.muted)
                    }
                    IconButton(onClick = onExportEcproj, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecproj", tint = EditorPalette.text)
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
                    border = androidx.foundation.BorderStroke(1.dp, Color(0xFF24453E))
                ) {
                    Text("OFFLINE", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        color = EditorPalette.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                IconButton(onClick = onValidateEcproj) { Icon(Icons.Outlined.FolderOpen, contentDescription = "Validate .ecproj", tint = EditorPalette.text) }
                IconButton(onClick = onOpenCanonicalEditor, enabled = hasCanonicalProject) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit imported .ecproj", tint = if (hasCanonicalProject) EditorPalette.mint else EditorPalette.muted)
                }
                IconButton(onClick = onExportEcproj) { Icon(Icons.Outlined.FileDownload, contentDescription = "Export .ecproj", tint = EditorPalette.text) }
                IconButton(onClick = onSave) { Icon(Icons.Outlined.Save, contentDescription = "Save draft", tint = EditorPalette.text) }
                TextButton(onClick = onNewProject) { Text("New", color = EditorPalette.violet, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun PreviewStage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(EditorPalette.panel)
            .border(1.dp, EditorPalette.border, RoundedCornerShape(20.dp))
    ) {
        Text(
            "COMPOSITION VIEWER",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 15.dp, top = 12.dp),
            color = EditorPalette.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp, bottom = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF222B3B), Color(0xFF111722), Color(0xFF19132A))
                        )
                    )
                    .border(1.dp, Color(0xFF59647A), RoundedCornerShape(13.dp))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val gridColor = Color.White.copy(alpha = 0.055f)
                    for (index in 1..5) {
                        val x = size.width * index / 6f
                        val y = size.height * index / 6f
                        drawLine(gridColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                        drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFF9C83FF).copy(alpha = 0.28f), Color.Transparent)
                        ),
                        radius = size.width * 0.46f,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.35f)
                    )
                    drawRoundRect(
                        color = Color(0xFF8C78F2).copy(alpha = 0.17f),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * 0.57f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.23f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f),
                        style = Stroke(width = 1.5f)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(43.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF9A82F4).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFFB5A4FF).copy(alpha = 0.55f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = EditorPalette.violet, fontSize = 24.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "MAKE IT MOVE.",
                        color = Color(0xFFF4F2FF),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "A new creative sequence",
                        color = Color(0xFFB7BED0),
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(30.dp))
                    Box(
                        Modifier
                            .width(80.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(EditorPalette.mint)
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    color = Color.Black.copy(alpha = 0.32f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "FRAME 001",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color.White.copy(alpha = 0.84f),
                        fontSize = 8.sp,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }

        Text(
            "1080 × 1920  ·  30 FPS",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 15.dp, bottom = 11.dp),
            color = EditorPalette.muted,
            fontSize = 9.sp
        )
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
                    modifier = Modifier.weight(1f),
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
                TextButton(onClick = { onMoveLayer(-1) }) {
                    Text("Move ↑", color = EditorPalette.muted, fontSize = 11.sp)
                }
                TextButton(onClick = { onMoveLayer(1) }) {
                    Text("Move ↓", color = EditorPalette.muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDeleteLayer) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFFF858D))
                Text("  Remove layer", color = Color(0xFFFF858D), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "INSPECTOR",
            color = EditorPalette.muted.copy(alpha = 0.65f),
            fontSize = 9.sp,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun CanonicalProjectEditor(
    project: CanonicalProjectInfo,
    canEdit: Boolean,
    onRenameLayer: (Long, Long, String) -> Unit,
    onAddTextLayer: (Long, String) -> Unit,
    onSetLayerVisibility: (Long, Long, Boolean) -> Unit,
    onMoveLayer: (Long, Long, Int) -> Unit,
    onDeleteLayer: (Long, Long) -> Unit,
    onSetLayerOpacity: (Long, Long, Double) -> Unit,
    onSetLayerPosition: (Long, Long, Double, Double) -> Unit,
    onSetLayerScale: (Long, Long, Double, Double) -> Unit,
    onSetLayerRotation: (Long, Long, Double) -> Unit,
    onRelinkAsset: (Long) -> Unit,
    onRenderFrame: (Long, Long) -> ByteArray?,
    modifier: Modifier = Modifier
) {
    var compositionId by remember { mutableStateOf<Long?>(null) }
    var layerId by remember { mutableStateOf<Long?>(null) }
    var newTextName by remember { mutableStateOf("Text") }
    val composition = project.compositions.firstOrNull { it.id == compositionId } ?: project.compositions.firstOrNull()
    val layer = composition?.layers?.firstOrNull { it.id == layerId } ?: composition?.layers?.firstOrNull()
    var nameDraft by remember(composition?.id, layer?.id, layer?.name) { mutableStateOf(layer?.name.orEmpty()) }
    var opacityDraft by remember(composition?.id, layer?.id, layer?.opacityPercent) { mutableFloatStateOf((layer?.opacityPercent ?: 100.0).toFloat()) }
    var positionXDraft by remember(composition?.id, layer?.id, layer?.positionX) { mutableStateOf((layer?.positionX ?: 0.0).toString()) }
    var positionYDraft by remember(composition?.id, layer?.id, layer?.positionY) { mutableStateOf((layer?.positionY ?: 0.0).toString()) }
    var scaleXDraft by remember(composition?.id, layer?.id, layer?.scaleX) { mutableStateOf((layer?.scaleX ?: 100.0).toString()) }
    var scaleYDraft by remember(composition?.id, layer?.id, layer?.scaleY) { mutableStateOf((layer?.scaleY ?: 100.0).toString()) }
    var rotationDraft by remember(composition?.id, layer?.id, layer?.rotationDegrees) { mutableStateOf((layer?.rotationDegrees ?: 0.0).toString()) }
    var previewFrame by remember(composition?.id) { mutableIntStateOf(0) }
    var renderedFrameBytes by remember(composition?.id) { mutableStateOf<ByteArray?>(null) }
    var previewMessage by remember(composition?.id) { mutableStateOf("Waiting for native renderer") }
    LaunchedEffect(project, composition?.id, previewFrame, canEdit) {
        if (composition != null && canEdit) {
            previewMessage = "Rendering frame ${previewFrame}…"
            renderedFrameBytes = withContext(Dispatchers.Default) { onRenderFrame(composition.id, previewFrame.toLong()) }
            previewMessage = if (renderedFrameBytes != null) "CPU frame ready · ${previewFrame}" else "Native preview unavailable"
        } else {
            renderedFrameBytes = null
            previewMessage = "ARM64 native renderer required"
        }
    }
    val previewBitmap = remember(renderedFrameBytes) { renderedFrameBytes?.let(::decodeRenderedFrame) }

    Column(modifier = modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CANONICAL .ECPROJ", color = EditorPalette.violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Native project model · ${project.compositions.size} compositions", color = EditorPalette.muted, fontSize = 12.sp)
        if (composition == null) {
            Text("No compositions found in this project.", color = EditorPalette.muted)
        } else {
            Text("Composition", color = EditorPalette.muted, fontSize = 10.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                project.compositions.forEach { item ->
                    FilterChip(
                        selected = item.id == composition.id,
                        onClick = { compositionId = item.id; layerId = item.layers.firstOrNull()?.id },
                        label = { Text(item.name, fontSize = 11.sp) }
                    )
                }
            }
            Text("${composition.width} × ${composition.height} · ${composition.layers.size} layers", color = EditorPalette.muted, fontSize = 11.sp)
            Text("CPU PREVIEW · ${composition.frameRateNum}/${composition.frameRateDen} FPS", color = EditorPalette.violet, fontSize = 10.sp, letterSpacing = 1.1.sp)
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "EffectCraft rendered composition frame",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(previewMessage, color = EditorPalette.muted, fontSize = 11.sp)
            }
            Text("Frame ${previewFrame} / ${composition.durationFrames - 1}", color = EditorPalette.muted, fontSize = 10.sp)
            Slider(
                value = previewFrame.toFloat(),
                onValueChange = { previewFrame = it.roundToInt().coerceIn(0, composition.durationFrames - 1) },
                valueRange = 0f..(composition.durationFrames - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.semantics { contentDescription = "Canonical preview frame" }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newTextName,
                    onValueChange = { newTextName = it.take(120) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
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
                    TextButton(
                        onClick = { layerId = item.id; nameDraft = item.name },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${if (item.id == layer?.id) "● " else "○ "}${item.name}  ·  ${item.source}",
                            color = if (item.id == layer?.id) EditorPalette.mint else EditorPalette.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (layer != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visible", color = EditorPalette.text, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = layer.visible,
                        onCheckedChange = { onSetLayerVisibility(composition.id, layer.id, it) },
                        modifier = Modifier.semantics { contentDescription = "Canonical layer visibility" }
                    )
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
                        enabled = canEdit && positionXDraft.toDoubleOrNull()?.isFinite() == true && positionYDraft.toDoubleOrNull()?.isFinite() == true,
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
                        enabled = canEdit && scaleXDraft.toDoubleOrNull()?.isFinite() == true && scaleYDraft.toDoubleOrNull()?.isFinite() == true,
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
                        enabled = canEdit && rotationDraft.toDoubleOrNull()?.isFinite() == true,
                        onClick = { onSetLayerRotation(composition.id, layer.id, rotationDraft.toDouble()) }
                    ) { Text("Apply", color = EditorPalette.violet, fontSize = 10.sp) }
                }
                Text("Opacity · ${opacityDraft.roundToInt()}%", color = EditorPalette.text, fontSize = 12.sp)
                Slider(
                    value = opacityDraft,
                    onValueChange = { opacityDraft = it },
                    onValueChangeFinished = { if (canEdit) onSetLayerOpacity(composition.id, layer.id, opacityDraft.toDouble()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics { contentDescription = "Canonical layer opacity" }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = canEdit, onClick = { onMoveLayer(composition.id, layer.id, -1) }) { Text("Move ↑", color = EditorPalette.muted, fontSize = 11.sp) }
                    TextButton(enabled = canEdit, onClick = { onMoveLayer(composition.id, layer.id, 1) }) { Text("Move ↓", color = EditorPalette.muted, fontSize = 11.sp) }
                    TextButton(enabled = canEdit, onClick = { onDeleteLayer(composition.id, layer.id) }) { Text("Remove", color = Color(0xFFFF858D), fontSize = 11.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(120) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (nameDraft.isEmpty()) Text("Layer name", color = EditorPalette.muted)
                                inner()
                            }
                        }
                    )
                    TextButton(
                        enabled = canEdit && nameDraft.trim().isNotEmpty() && nameDraft.trim() != layer.name,
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

private fun decodeRenderedFrame(bytes: ByteArray): Bitmap? {
    if (bytes.size < 8) return null
    val width = ByteBuffer.wrap(bytes, 0, 4).int
    val height = ByteBuffer.wrap(bytes, 4, 4).int
    if (width !in 1..2048 || height !in 1..2048 || bytes.size != 8 + width * height * 4) return null
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val offset = 8 + index * 4
        val red = bytes[offset].toInt() and 0xff
        val green = bytes[offset + 1].toInt() and 0xff
        val blue = bytes[offset + 2].toInt() and 0xff
        val alpha = bytes[offset + 3].toInt() and 0xff
        pixels[index] = android.graphics.Color.argb(alpha, red, green, blue)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

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
    isPlaying: Boolean,
    onSelectLayer: (String) -> Unit,
    onScrub: (Int) -> Unit,
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
            Text("LAYER", color = EditorPalette.muted, fontSize = 8.sp, letterSpacing = 1.1.sp, modifier = Modifier.width(112.dp))
            Text("00:00", color = EditorPalette.muted, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("00:${(project.durationFrames / project.frameRate).toString().padStart(2, '0')}", color = EditorPalette.muted, fontSize = 8.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            project.layers.take(4).forEach { layer ->
                LayerTrack(
                    layer = layer,
                    selected = layer.id == selectedLayerId,
                    onClick = { onSelectLayer(layer.id) }
                )
            }
            if (project.layers.size > 4) {
                Text("+ ${project.layers.size - 4} more layers", color = EditorPalette.muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun LayerTrack(
    layer: LayerDocument,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0xFF20283A) else Color.Transparent)
            .clickable(onClick = onClick)
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
