package com.codeair.effectcraft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable

/**
 * Entry animation for dialogs: the content fades and scales in over
 * 180 ms when the dialog opens.
 *
 * There is no exit animation on purpose: dismissing a dialog removes
 * it from composition at once, so an exit transition would never be
 * seen. ModalBottomSheets are not wrapped — they animate their own
 * entry and exit.
 */
@Composable
internal fun AnimatedDialogContent(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(180)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(180))
    ) {
        content()
    }
}
