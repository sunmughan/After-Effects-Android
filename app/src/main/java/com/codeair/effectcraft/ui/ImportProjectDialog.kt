package com.codeair.effectcraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.effectcraft.ui.theme.AstryxTheme

/**
 * The import picker, as an Edits bottom sheet: four tappable rows,
 * a short title and an icon each, no paragraph text. What each
 * option accepts lives on the "How to export from After Effects"
 * help sheet, not in the picker.
 *
 * - EffectCraft project: a .ecproj / .ecprojx file saved by EffectCraft.
 * - After Effects project: a raw .aep / .aepx file, imported directly
 *   by the ported official EffectCraft .aep importer. The import shows
 *   its loss report first; nothing is dropped silently.
 * - Project bundle (.zip): a .zip holding project.json plus its media.
 * - After Effects export (.zip): a .zip produced by the included
 *   ExtendScript. This stays as an interim route for projects the
 *   .aep importer cannot fully read.
 */
@Composable
fun ImportProjectDialog(
    onDismiss: () -> Unit,
    onPickEcproj: () -> Unit,
    onPickAep: () -> Unit,
    onPickZipBundle: () -> Unit,
    onPickAeExport: () -> Unit
) {
    var showAeHelp by remember { mutableStateOf(false) }
    EditsSheet(onDismissRequest = onDismiss) {
        Text(
            "Import project",
            style = AstryxTheme.Type.titleSmall,
            color = AstryxTheme.Colors.textPrimary
        )
        ImportOption(
            title = "EffectCraft project",
            icon = Icons.Outlined.Folder,
            onClick = onPickEcproj
        )
        ImportOption(
            title = "After Effects project",
            icon = Icons.Outlined.Movie,
            onClick = onPickAep
        )
        ImportOption(
            title = "Project bundle",
            icon = Icons.Outlined.Archive,
            onClick = onPickZipBundle
        )
        ImportOption(
            title = "After Effects export",
            icon = Icons.Outlined.FileUpload,
            onClick = onPickAeExport
        )
        TextButton(
            onClick = { showAeHelp = true },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(
                "How to export from After Effects",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary,
                maxLines = 1
            )
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Cancel", color = AstryxTheme.Colors.textSecondary)
        }
    }
    if (showAeHelp) {
        AeExportHelpDialog(onDismiss = { showAeHelp = false })
    }
}

/** One import route: icon + short title + chevron, no description. */
@Composable
private fun ImportOption(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp)
            .clickable { onClick() }
            .padding(horizontal = AstryxTheme.Spacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AstryxTheme.Colors.textPrimary, modifier = Modifier.size(20.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = AstryxTheme.Colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(start = AstryxTheme.Spacing.space3)
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = AstryxTheme.Colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The help sheet behind "How to export from After Effects": what each
 * import option accepts, and the ExtendScript export steps. The picker
 * itself stays free of paragraph text.
 */
@Composable
private fun AeExportHelpDialog(onDismiss: () -> Unit) {
    EditsSheet(onDismissRequest = onDismiss) {
        Text(
            "Export from After Effects",
            style = AstryxTheme.Type.titleSmall,
            color = AstryxTheme.Colors.textPrimary
        )
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
        ) {
            Text("EffectCraft project", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AstryxTheme.Colors.textPrimary)
            Text("A .ecproj or .ecprojx file saved by EffectCraft.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text(
                "After Effects project",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "A raw After Effects project, imported directly. Shows what is lost before importing.",
                fontSize = 12.sp,
                color = AstryxTheme.Colors.textSecondary
            )
            Text(
                "Project bundle",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("A .zip with project.json and its media files.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text(
                "After Effects export",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("A .zip made by the included ExtendScript (interim route).", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text(
                "How to export",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("1. In After Effects: File > Scripts > Run Script File.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text("2. Choose ExportToEffectCraft.jsx from the repo.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text("3. It writes a folder next to your .aep.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text("4. Zip that folder.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
            Text("5. Here: choose After Effects export and pick the .zip.", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Close", color = AstryxTheme.Colors.textSecondary)
        }
    }
}
