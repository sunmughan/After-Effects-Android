package com.codeair.effectcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.ui.theme.AstryxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Instagram Edits Projects home: the screen the app opens on.
 * A title row (Projects / Import / New), then a two-column grid:
 * a New project tile first, then one card per saved project.
 * A card's header block uses its first layer's tint as the
 * thumbnail colour; tapping a card opens it in the editor, and
 * the delete icon asks first — deleting a saved draft is
 * irreversible.
 */
@Composable
fun ProjectsHomeScreen(
    projects: List<ProjectDocument>,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<ProjectDocument?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AstryxTheme.Colors.backgroundBody)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AstryxTheme.Spacing.space4,
                    vertical = AstryxTheme.Spacing.space2
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Projects",
                modifier = Modifier.weight(1f),
                style = AstryxTheme.Type.title,
                color = AstryxTheme.Colors.textPrimary
            )
            TextButton(
                onClick = onImportProject,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Import", color = AstryxTheme.Colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            // Edits-style New action: the white pill.
            Surface(
                color = AstryxTheme.Colors.backgroundInverted,
                shape = RoundedCornerShape(AstryxTheme.Radius.full),
                modifier = Modifier.height(AstryxTheme.Control.heightLg)
                    .semantics { contentDescription = "New project" }
                    .clickable { onNewProject() }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = AstryxTheme.Spacing.space4)
                ) {
                    Text(
                        "New",
                        color = AstryxTheme.Colors.onLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (projects.isEmpty()) {
            Text(
                "No projects yet — start a new one, or import an .aep, .ecproj or ZIP bundle.",
                modifier = Modifier.padding(
                    horizontal = AstryxTheme.Spacing.space4,
                    vertical = AstryxTheme.Spacing.space2
                ),
                style = AstryxTheme.Type.body,
                color = AstryxTheme.Colors.textSecondary
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AstryxTheme.Spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(AstryxTheme.Spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AstryxTheme.Spacing.space3)
        ) {
            item(key = "new-project-tile") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f)
                        .clip(RoundedCornerShape(AstryxTheme.Radius.container))
                        .border(1.dp, AstryxTheme.Colors.borderEmphasized, RoundedCornerShape(AstryxTheme.Radius.container))
                        .clickable { onNewProject() }
                        .semantics { contentDescription = "New project" },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            tint = AstryxTheme.Colors.textPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(AstryxTheme.Spacing.space2))
                        Text(
                            "New project",
                            style = AstryxTheme.Type.label,
                            color = AstryxTheme.Colors.textPrimary
                        )
                    }
                }
            }
            items(projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    onOpen = { onOpenProject(project.id) },
                    onDelete = { pendingDelete = project }
                )
            }
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete project?") },
            text = { Text("“${toDelete.name}” will be permanently deleted from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteProject(toDelete.id)
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text("Delete", color = AstryxTheme.Colors.errorText) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.height(48.dp)
                ) { Text("Cancel", color = AstryxTheme.Colors.textPrimary) }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectDocument,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val edited = remember(project.updatedAtEpochMillis) {
        SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(project.updatedAtEpochMillis))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(AstryxTheme.Radius.container))
            .background(AstryxTheme.Colors.backgroundCard)
            .border(1.dp, AstryxTheme.Colors.border, RoundedCornerShape(AstryxTheme.Radius.container))
            .clickable { onOpen() }
            .semantics { contentDescription = "Open project ${project.name}" }
    ) {
        // Thumbnail: the first layer's tint, with the project's
        // initial on it. No rendered frame is stored for a draft,
        // so the tint is the honest preview.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(project.layers.firstOrNull()?.tint ?: 0xFF9278F6)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                project.name.firstOrNull()?.uppercaseChar()?.toString() ?: "E",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(Modifier.padding(AstryxTheme.Spacing.space3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    modifier = Modifier.weight(1f),
                    style = AstryxTheme.Type.label,
                    color = AstryxTheme.Colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete project ${project.name}",
                        tint = AstryxTheme.Colors.textSecondary
                    )
                }
            }
            Text(
                "${project.width}×${project.height} · ${project.frameRate} FPS",
                style = AstryxTheme.Type.caption,
                color = AstryxTheme.Colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${timelineEndLabel(project.durationFrames, project.frameRate)} · ${project.layers.size} layers · $edited",
                style = AstryxTheme.Type.caption,
                color = AstryxTheme.Colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
