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
    onCancel: () -> Unit
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
            Text(
                "Keeps: ${imported.compositions} composition(s), ${imported.layers} layer(s), " +
                    "${imported.footage} footage item(s), ${imported.solids} solid(s), " +
                    "${imported.keyframes} keyframe(s), ${imported.expressions} expression(s).",
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
