@Composable
private fun CanonicalTextLayerControls(
    compositionId: Long, layerId: Long, textParams: com.codeair.effectcraft.data.CanonicalTextParams,
    editable: Boolean, previewFrame: Int,
    onSetCanonicalTextLayerKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit,
    onSetTextLayerTextWholeLayer: (Long, Long, String) -> Unit,
    onSetTextLayerFont: (Long, Long, String) -> Unit,
    onSetTextLayerFontKeyframe: (Long, Long, Long, Int, String, Boolean) -> Unit,
    onSetTextLayerSize: (Long, Long, Double) -> Unit, onSetTextLayerTracking: (Long, Long, Double) -> Unit,
    onSetTextLayerTrackingKeyframe: (Long, Long, Long, Int, Double, Boolean) -> Unit
) {
    var textDraft by remember(compositionId, layerId, textParams.text) { mutableStateOf(textParams.text) }
    var textSizeDraft by remember(compositionId, layerId, textParams.size) { mutableStateOf(textParams.size.toString()) }
    var textTrackingDraft by remember(compositionId, layerId, textParams.tracking) { mutableStateOf(textParams.tracking.toString()) }
    var textFontDraft by remember(compositionId, layerId, textParams.font) { mutableStateOf(textParams.font) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Text · Source Text", color = EditorPalette.text, fontSize = 12.sp)
        // After Effects two-mode (owner pick 2026-11-06): keyframes
        // OFF means an edit changes the whole layer everywhere
        // (keys are dropped, static text set); keyframes ON means
        // an edit changes only the hold key at the playhead.
        // The mode follows the layer: ON while it has any Source
        // Text key, OFF otherwise.
        val textKeyframingOn = textParams.keys.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Source Text keyframes",
                color = EditorPalette.text, fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = textKeyframingOn,
                onCheckedChange = { on ->
                    if (!editable) return@Switch
                    if (on) onSetCanonicalTextLayerKeyframe(compositionId, layerId, textParams.uid, previewFrame, textDraft, false)
                    else onSetTextLayerTextWholeLayer(compositionId, layerId, textDraft)
                },
                modifier = Modifier.semantics { contentDescription = "Toggle Source Text keyframes" }
            )
        }
        Text(
            if (textKeyframingOn) "Keyframes on · edits change only the hold key at the playhead."
            else "Keyframes off · edits change the whole layer everywhere.",
            color = EditorPalette.muted, fontSize = 10.sp
        )
        Text("Font · ${textParams.font}", color = EditorPalette.muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicTextField(
                value = textFontDraft,
                onValueChange = { textFontDraft = it.take(128) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer font" },
                decorationBox = { inner -> Box { if (textFontDraft.isEmpty()) Text("Font family", color = EditorPalette.muted); inner() } }
            )
            TextButton(
                enabled = editable && textFontDraft.isNotBlank() && textFontDraft.length <= 128,
                onClick = { onSetTextLayerFont(compositionId, layerId, textFontDraft) },
                modifier = Modifier.semantics { contentDescription = "Apply canonical text font" }
            ) { Text("Apply font", color = EditorPalette.violet, fontSize = 10.sp) }
            val hasFontKey = textParams.fontKeys.any { key -> key.frame == previewFrame }
            TextButton(
                enabled = editable && textFontDraft.isNotBlank() && textFontDraft.length <= 128,
                onClick = { onSetTextLayerFontKeyframe(compositionId, layerId, textParams.uid, previewFrame, textFontDraft, hasFontKey) },
                modifier = Modifier.semantics {
                    contentDescription = if (hasFontKey) "Remove canonical font keyframe frame $previewFrame"
                    else "Add canonical font keyframe frame $previewFrame"
                }
            ) { Text(if (hasFontKey) "Remove key" else "Key font", color = if (hasFontKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
        }
        Text(
            "Font keys · " + if (textParams.fontKeys.isEmpty()) "none" else textParams.fontKeys.joinToString { key -> "${key.frame}: ${key.font}" },
            color = EditorPalette.amber, fontSize = 10.sp,
            modifier = Modifier.semantics { contentDescription = "Canonical font keyframes" }
        )
        BasicTextField(
            value = textDraft,
            onValueChange = { textDraft = it.take(20000) },
            textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Canonical text layer text" },
            decorationBox = { inner -> Box { if (textDraft.isEmpty()) Text("Source text", color = EditorPalette.muted); inner() } }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = editable,
                onClick = {
                    if (textKeyframingOn) onSetCanonicalTextLayerKeyframe(compositionId, layerId, textParams.uid, previewFrame, textDraft, false)
                    else onSetTextLayerTextWholeLayer(compositionId, layerId, textDraft)
                },
                modifier = Modifier.semantics { contentDescription = "Apply canonical text" }
            ) { Text("Apply text", color = EditorPalette.violet, fontSize = 11.sp) }
            val hasTextKey = textParams.keys.any { key -> key.frame == previewFrame }
            TextButton(
                enabled = editable && (hasTextKey || textDraft.isNotEmpty()),
                onClick = { onSetCanonicalTextLayerKeyframe(compositionId, layerId, textParams.uid, previewFrame, textDraft, hasTextKey) },
                modifier = Modifier.semantics {
                    contentDescription = if (hasTextKey) "Remove canonical Source Text keyframe frame $previewFrame"
                    else "Add canonical Source Text keyframe frame $previewFrame"
                }
            ) { Text(if (hasTextKey) "Remove key" else "Add key", color = if (hasTextKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
        }
        Text(
            "Source Text keys · " + if (textParams.keys.isEmpty()) "none" else textParams.keys.joinToString { key -> key.frame.toString() + ": " + key.text.take(24) },
            color = EditorPalette.amber, fontSize = 10.sp,
            modifier = Modifier.semantics { contentDescription = "Canonical Source Text keyframes" }
        )
        Text("Text · Size / Tracking", color = EditorPalette.text, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicTextField(
                value = textSizeDraft,
                onValueChange = { textSizeDraft = it.take(32) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer size" },
                decorationBox = { inner -> Box { if (textSizeDraft.isEmpty()) Text("Size", color = EditorPalette.muted); inner() } }
            )
            BasicTextField(
                value = textTrackingDraft,
                onValueChange = { textTrackingDraft = it.take(32) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = EditorPalette.text, fontSize = 12.sp),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Canonical text layer tracking" },
                decorationBox = { inner -> Box { if (textTrackingDraft.isEmpty()) Text("Tracking", color = EditorPalette.muted); inner() } }
            )
            TextButton(
                enabled = editable && textSizeDraft.toDoubleOrNull()?.isFinite() == true,
                onClick = { onSetTextLayerSize(compositionId, layerId, textSizeDraft.toDouble()) },
                modifier = Modifier.semantics { contentDescription = "Apply canonical text size" }
            ) { Text("Apply size", color = EditorPalette.violet, fontSize = 10.sp) }
            TextButton(
                enabled = editable && textTrackingDraft.toDoubleOrNull()?.isFinite() == true,
                onClick = { onSetTextLayerTracking(compositionId, layerId, textTrackingDraft.toDouble()) },
                modifier = Modifier.semantics { contentDescription = "Apply canonical text tracking" }
            ) { Text("Apply tracking", color = EditorPalette.violet, fontSize = 10.sp) }
            val hasTrackingKey = textParams.keys.any { key -> key.frame == previewFrame }
            TextButton(
                enabled = editable && textTrackingDraft.toDoubleOrNull()?.isFinite() == true,
                onClick = { onSetTextLayerTrackingKeyframe(compositionId, layerId, textParams.uid, previewFrame, textTrackingDraft.toDouble(), hasTrackingKey) },
                modifier = Modifier.semantics {
                    contentDescription = if (hasTrackingKey) "Remove canonical Source Text keyframe frame $previewFrame"
                    else "Add canonical Source Text tracking keyframe frame $previewFrame"
                }
            ) { Text(if (hasTrackingKey) "Remove key" else "Key tracking", color = if (hasTrackingKey) EditorPalette.amber else EditorPalette.violet, fontSize = 10.sp) }
        }
        Text(
            "Tracking at Source Text keys · " + if (textParams.keys.isEmpty()) "none" else textParams.keys.joinToString { "${it.frame}: ${it.tracking}" },
            color = EditorPalette.amber, fontSize = 10.sp,
            modifier = Modifier.semantics { contentDescription = "Canonical Source Text tracking keyframes" }
        )
    }
}

