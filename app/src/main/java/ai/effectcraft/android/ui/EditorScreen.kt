package ai.effectcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectDocument
import java.util.Locale
import kotlin.math.roundToInt

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

@Composable
fun EditorScreen(
    project: ProjectDocument,
    selectedLayer: LayerDocument?,
    selectedLayerId: String,
    playheadFrame: Int,
    isPlaying: Boolean,
    saveMessage: String,
    onSave: () -> Unit,
    onNewProject: () -> Unit,
    onAddLayer: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onDeleteLayer: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScrub: (Int) -> Unit,
    onTogglePlayback: () -> Unit
) {
    val selectedTool = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Select") }

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
                onNewProject = onNewProject
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
                        onDeleteLayer = onDeleteLayer
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
                onSelectTool = { selectedTool.value = it },
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
    }
}

@Composable
private fun HeaderBar(
    projectName: String,
    saveMessage: String,
    onSave: () -> Unit,
    onNewProject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(EditorPalette.violet, Color(0xFF6851C7)))),
            contentAlignment = Alignment.Center
        ) {
            Text("E", color = Color(0xFF171326), fontWeight = FontWeight.Black, fontSize = 21.sp)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp)
        ) {
            Text(
                "EFFECTCRAFT",
                color = EditorPalette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.1.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    projectName,
                    color = EditorPalette.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("  ·  ", color = EditorPalette.border, fontSize = 12.sp)
                Text(
                    saveMessage,
                    color = EditorPalette.mint,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            color = Color(0xFF13231F),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24453E))
        ) {
            Text(
                "OFFLINE",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                color = EditorPalette.mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        IconButton(onClick = onSave) {
            Icon(Icons.Outlined.Save, contentDescription = "Save draft", tint = EditorPalette.text)
        }
        TextButton(onClick = onNewProject) {
            Text("New", color = EditorPalette.violet, fontWeight = FontWeight.SemiBold)
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
    onDeleteLayer: () -> Unit
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
            Text(
                layer.name,
                modifier = Modifier.padding(top = 6.dp),
                color = EditorPalette.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(layer.kind.uppercase(Locale.US), color = EditorPalette.violet, fontSize = 9.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(24.dp))
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
                modifier = Modifier.padding(top = 3.dp)
            )
            Spacer(Modifier.height(20.dp))
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
    onSelectTool: (String) -> Unit,
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

        TextButton(onClick = onAddLayer) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = EditorPalette.violet)
            Text("Layer", color = EditorPalette.violet, fontSize = 12.sp)
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
