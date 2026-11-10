package com.codeair.effectcraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.effectcraft.data.aep.AepImportReport
import com.codeair.effectcraft.ui.theme.AstryxTheme

/**
 * The .aep loss gate, as an Edits bottom sheet: shown after
 * [ImportDocumentResolver.analyzeAep] reports losses, before the
 * user allows them. Nothing is imported until the user chooses
 * "Import with losses"; "Cancel" — or dismissing the sheet —
 * abandons the import with nothing written. The report is the
 * whole truth about what the import keeps and what it drops.
 */
@Composable
fun AepLossDialog(
    report: AepImportReport.ImportReport,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
    onChooseFootageFolder: (() -> Unit)? = null
) {
    val imported = report.imported
    val totalLosses = report.losses.sumOf { it.count }
    EditsSheet(onDismissRequest = onCancel) {
        Text(
            "Import .aep with losses?",
            style = AstryxTheme.Type.titleSmall,
            color = AstryxTheme.Colors.textPrimary
        )
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                "After Effects ${report.aeVersion.fileVersion} project" +
                    if (report.aeVersion.tested) " (tested version)" else " (untested version)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.textPrimary
            )
            // Only the categories this file actually kept are listed, so the
            // line stays short and never claims a category with a zero count.
            val keeps = buildList {
                if (imported.compositions > 0) add("${imported.compositions} composition(s)")
                if (imported.layers > 0) add("${imported.layers} layer(s)")
                if (imported.footage > 0) add("${imported.footage} footage item(s)")
                if (imported.solids > 0) add("${imported.solids} solid(s)")
                if (imported.textLayers > 0) add("${imported.textLayers} text layer(s)")
                if (imported.shapeLayers > 0) add("${imported.shapeLayers} shape layer(s)")
                if (imported.precompLayers > 0) add("${imported.precompLayers} precomp layer(s)")
                if (imported.effects > 0) add("${imported.effects} effect(s)")
                if (imported.masks > 0) add("${imported.masks} mask(s)")
                if (imported.markers > 0) add("${imported.markers} marker(s)")
                if (imported.timeRemaps > 0) add("${imported.timeRemaps} time remap(s)")
                if (imported.keyframes > 0) add("${imported.keyframes} keyframe(s)")
                if (imported.expressions > 0) add("${imported.expressions} expression(s)")
            }
            Text(
                "Keeps: ${keeps.joinToString(", ")}.",
                fontSize = 12.sp,
                color = AstryxTheme.Colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Loses: $totalLosses item(s).",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AstryxTheme.Colors.warningText,
                modifier = Modifier.padding(top = 8.dp)
            )
            for (loss in report.losses) {
                Text(
                    "${loss.category.label}: ${loss.count}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AstryxTheme.Colors.textPrimary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                for (detail in loss.details.take(3)) {
                    Text("· $detail", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
                }
                if (loss.details.size > 3) {
                    Text("· …and ${loss.details.size - 3} more", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
                }
            }
            if (report.missingFootage.isNotEmpty()) {
                Text(
                    "Missing media: ${report.missingFootage.size} file(s) not found on this device.",
                    fontSize = 12.sp,
                    color = AstryxTheme.Colors.warningText,
                    modifier = Modifier.padding(top = 6.dp)
                )
                for (path in report.missingFootage.take(3)) {
                    Text("· $path", fontSize = 12.sp, color = AstryxTheme.Colors.textSecondary)
                }
                // The footage usually sits next to the .aep in the same
                // folder; picking that folder links it and re-runs the
                // report, so the missing list above shrinks.
                if (onChooseFootageFolder != null) {
                    TextButton(
                        onClick = onChooseFootageFolder,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Choose footage folder", color = AstryxTheme.Colors.textPrimary)
                    }
                }
            }
            Text(
                "What is lost cannot be recovered from the imported project. " +
                    "The original .aep file is untouched.",
                fontSize = 12.sp,
                color = AstryxTheme.Colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AstryxTheme.Spacing.space2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Cancel", color = AstryxTheme.Colors.textSecondary)
            }
            // Primary action: the white pill, like the Export pill.
            Surface(
                color = AstryxTheme.Colors.backgroundInverted,
                shape = RoundedCornerShape(AstryxTheme.Radius.full),
                modifier = Modifier.height(AstryxTheme.Control.heightLg)
                    .clickable { onAllow() }
                    .semantics { contentDescription = "Import with losses" }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = AstryxTheme.Spacing.space4)
                ) {
                    Text(
                        "Import with losses",
                        color = AstryxTheme.Colors.onLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
