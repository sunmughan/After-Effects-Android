package com.codeair.effectcraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codeair.effectcraft.ui.theme.AstryxTheme

/**
 * Astryx BottomSheet anatomy, adapted to Compose.
 *
 * A mobile touch sheet that rises from the bottom edge, with a
 * grab handle at the top, content starting at the theme's
 * bottom-sheet padding (--spacing-4, 16 dp) below the handle,
 * a popover container, and the overlay scrim. Purpose is info
 * by default: scrim tap, swipe and back dismiss. Pass
 * dismissable = false for a required flow, where only an
 * explicit action closes the sheet.
 *
 * Every sheet in the app uses this — import, export settings,
 * pickers, media library, loss gate, onboarding — so the sheet
 * chrome is identical everywhere. Destructive confirmations
 * (delete a project, remove media) stay AlertDialogs instead:
 * Astryx reserves role=alertdialog for those. All app sheets
 * are info-purpose: scrim tap, swipe and back dismiss them, and
 * every sheet's dismiss path is a real action (Cancel, or the
 * loss gate's Cancel which abandons the import).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditsSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AstryxTheme.Colors.backgroundPopover,
        contentColor = AstryxTheme.Colors.textPrimary,
        scrimColor = AstryxTheme.Colors.overlay,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AstryxTheme.Spacing.space4),
            verticalArrangement = Arrangement.spacedBy(AstryxTheme.Spacing.space3)
        ) {
            content()
        }
    }
}
