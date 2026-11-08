package com.codeair.effectcraft.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Batch 3: the import picker dialog. Three labelled options, each
 * honest about what it accepts — the old flow hid import behind an
 * unlabelled icon and reported a dismissal as "Project import
 * cancelled" with no way to reach a real import at all.
 *
 * - EffectCraft project: a .ecproj / .ecprojx file saved by EffectCraft.
 * - Project bundle (.zip): a .zip holding project.json plus its media.
 * - After Effects export (.zip): a .zip produced by the included
 *   ExtendScript. This is an INTERIM route only: direct .aep import
 *   is the real requirement and is being built from the official
 *   EffectCraft .aep importer (v0.21.1+); the dialog says it is
 *   coming instead of claiming .aep can never be imported.
 */
@Composable
fun ImportProjectDialog(
    onDismiss: () -> Unit,
    onPickEcproj: () -> Unit,
    onPickZipBundle: () -> Unit,
    onPickAeExport: () -> Unit
) {
    var showAeSteps by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                ImportOption(
                    title = "EffectCraft project",
                    description = "A .ecproj or .ecprojx file saved by EffectCraft.",
                    onClick = onPickEcproj
                )
                ImportOption(
                    title = "Project bundle (.zip)",
                    description = "A .zip with project.json and its media files.",
                    onClick = onPickZipBundle
                )
                ImportOption(
                    title = "After Effects export (.zip)",
                    description = "A .zip made by the included ExtendScript (interim route). Direct .aep import is coming in a later update.",
                    onClick = onPickAeExport
                )
                TextButton(
                    onClick = { showAeSteps = !showAeSteps },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (showAeSteps) "Hide After Effects export steps" else "How to export from After Effects",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                if (showAeSteps) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Text("1. In After Effects: File > Scripts > Run Script File.", fontSize = 12.sp)
                        Text("2. Choose ExportToEffectCraft.jsx from the repo.", fontSize = 12.sp)
                        Text("3. It writes a folder next to your .aep.", fontSize = 12.sp)
                        Text("4. Zip that folder.", fontSize = 12.sp)
                        Text("5. Here: choose After Effects export (.zip) and pick the .zip.", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ImportOption(title: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
