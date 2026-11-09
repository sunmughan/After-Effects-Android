package com.codeair.effectcraft.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.effectcraft.data.aep.AepImportReport

/**
 * The .aep loss gate: shown after [ImportDocumentResolver.analyzeAep]
 * reports losses, before the user allows them. Nothing is imported
 * until the user chooses "Import with losses"; "Cancel" abandons the
 * import with nothing written. The report is the whole truth about
 * what the import keeps and what it drops.
 */
@Composable
fun AepLossDialog(
    report: AepImportReport.ImportReport,
    onAllow: () -> Unit,
    onCancel: () -> Unit
) {
    val imported = report.imported
    val totalLosses = report.losses.sumOf { it.count }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import .aep with losses?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(
                    "After Effects ${report.aeVersion.fileVersion} project" +
                        if (report.aeVersion.tested) " (tested version)" else " (untested version)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Keeps: ${imported.compositions} composition(s), ${imported.layers} layer(s), " +
                        "${imported.footage} footage item(s), ${imported.solids} solid(s), " +
                        "${imported.keyframes} keyframe(s), ${imported.expressions} expression(s).",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Loses: $totalLosses item(s).",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                for (loss in report.losses) {
                    Text(
                        "${loss.category.label}: ${loss.count}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    for (detail in loss.details.take(3)) {
                        Text("· $detail", fontSize = 12.sp)
                    }
                    if (loss.details.size > 3) {
                        Text("· …and ${loss.details.size - 3} more", fontSize = 12.sp)
                    }
                }
                if (report.missingFootage.isNotEmpty()) {
                    Text(
                        "Missing media: ${report.missingFootage.size} file(s) not found on this device.",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    for (path in report.missingFootage.take(3)) {
                        Text("· $path", fontSize = 12.sp)
                    }
                }
                Text(
                    "What is lost cannot be recovered from the imported project. " +
                        "The original .aep file is untouched.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow, modifier = Modifier.height(48.dp)) {
                Text("Import with losses")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                Text("Cancel")
            }
        }
    )
}
