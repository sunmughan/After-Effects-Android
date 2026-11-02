package ai.effectcraft.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import ai.effectcraft.android.data.CanonicalProjectInfo
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.EcprojDocumentStore
import ai.effectcraft.android.engine.NativeProjectBridge
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectEditorActions
import ai.effectcraft.android.data.ProjectDocument
import kotlinx.coroutines.delay

@Composable
fun EffectCraftApp(projectStore: DraftProjectStore) {
    val context = LocalContext.current
    val ecprojStore = remember(context) { EcprojDocumentStore(context) }
    var importedEcproj by remember { mutableStateOf(ecprojStore.load()) }
    var pendingRelinkItemId by remember { mutableStateOf<Long?>(null) }
    var canonicalProjectInfo by remember { mutableStateOf<CanonicalProjectInfo?>(null) }
    var project by remember {
        mutableStateOf(projectStore.loadLatest() ?: ProjectDocument.starter())
    }
    var selectedLayerId by remember {
        mutableStateOf(project.layers.firstOrNull()?.id.orEmpty())
    }
    var playheadFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("Stored on this device") }
    var undoStack by remember { mutableStateOf(emptyList<ProjectDocument>()) }
    var redoStack by remember { mutableStateOf(emptyList<ProjectDocument>()) }

    fun applyProjectChange(next: ProjectDocument, message: String = "Unsaved changes") {
        undoStack = (undoStack + project).takeLast(50)
        redoStack = emptyList()
        project = next
        saveMessage = message
    }

    val validateEcprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val source = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Unable to read selected document")
                JSONObject(source)
                val native = NativeProjectBridge.inspectEcproj(source)
                val parsedInfo = native?.let { CanonicalProjectInfo.parse(it) }
                val status = if (parsedInfo != null) {
                    "Canonical .ecproj validated · ${parsedInfo.compositions.size} compositions"
                } else {
                    ".ecproj JSON imported · native schema validation unavailable on this ABI"
                }
                ecprojStore.save(source)
                importedEcproj = source
                canonicalProjectInfo = parsedInfo
                saveMessage = status
            }.onFailure { error -> saveMessage = "Import failed · ${error.message ?: "invalid project"}" }
        }
    }
    val exportEcprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val source = importedEcproj ?: error("No canonical .ecproj has been imported")
                context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to write selected document")
                saveMessage = "Exported canonical .ecproj"
            }.onFailure { error -> saveMessage = "Export failed · ${error.message ?: "storage error"}" }
        }
    }

    val relinkAssetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val itemId = pendingRelinkItemId
        pendingRelinkItemId = null
        if (uri != null && itemId != null) {
            val source = importedEcproj
            if (source == null) {
                saveMessage = "Import a canonical .ecproj first"
            } else {
                runCatching {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "asset"
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "asset" }
                    val directory = File(context.filesDir, "assets")
                    if (!directory.mkdirs() && !directory.isDirectory) error("Unable to create offline media directory")
                    val target = File(directory, "${itemId}_${safeName}")
                    val temporary = File(directory, "${target.name}.tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to read selected media")
                    try {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: Exception) {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    val envelope = NativeProjectBridge.relinkFootageEcproj(source, itemId, target.absolutePath)
                        ?: error("Native EffectCraft bridge unavailable on this ABI")
                    val response = JSONObject(envelope)
                    check(response.optBoolean("valid")) { response.optString("error", "Media relink failed") }
                    val updated = response.getString("project")
                    val metadata = NativeProjectBridge.inspectEcproj(updated)
                        ?: error("Unable to inspect updated canonical project")
                    val parsed = CanonicalProjectInfo.parse(metadata)
                    ecprojStore.save(updated)
                    importedEcproj = updated
                    canonicalProjectInfo = parsed
                    saveMessage = "Media copied offline · relinked"
                }.onFailure { error -> saveMessage = "Media relink failed · ${error.message ?: "storage error"}" }
            }
        }
    }

    LaunchedEffect(importedEcproj) {
        canonicalProjectInfo = importedEcproj?.let { source ->
            NativeProjectBridge.inspectEcproj(source)?.let { runCatching { CanonicalProjectInfo.parse(it) }.getOrNull() }
        }
    }

    LaunchedEffect(isPlaying, project.durationFrames) {
        while (isPlaying) {
            delay(33L)
            playheadFrame = (playheadFrame + 1) % project.durationFrames.coerceAtLeast(1)
        }
    }

    val selectedLayer = project.layers.firstOrNull { it.id == selectedLayerId }
    fun applyCanonicalEdit(envelope: String?, successMessage: String) {
        runCatching {
            val response = JSONObject(envelope ?: error("Native EffectCraft bridge unavailable on this ABI"))
            check(response.optBoolean("valid")) { response.optString("error", "Canonical edit failed") }
            val updated = response.getString("project")
            val metadata = NativeProjectBridge.inspectEcproj(updated)
                ?: error("Unable to inspect updated canonical project")
            val parsed = CanonicalProjectInfo.parse(metadata)
            ecprojStore.save(updated)
            importedEcproj = updated
            canonicalProjectInfo = parsed
            saveMessage = successMessage
        }.onFailure { error -> saveMessage = "Canonical edit failed · ${error.message ?: "invalid project"}" }
    }



    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0C11)
    ) {
        EditorScreen(
            project = project,
            selectedLayer = selectedLayer,
            selectedLayerId = selectedLayerId,
            playheadFrame = playheadFrame,
            isPlaying = isPlaying,
            saveMessage = saveMessage,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            onUndo = {
                if (undoStack.isNotEmpty()) {
                    redoStack = redoStack + project
                    project = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    if (project.layers.none { it.id == selectedLayerId }) selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                    saveMessage = "Undo · unsaved"
                }
            },
            onRedo = {
                if (redoStack.isNotEmpty()) {
                    undoStack = (undoStack + project).takeLast(50)
                    project = redoStack.last()
                    redoStack = redoStack.dropLast(1)
                    if (project.layers.none { it.id == selectedLayerId }) selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                    saveMessage = "Redo · unsaved"
                }
            },
            onValidateEcproj = { validateEcprojLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            onExportEcproj = {
                if (importedEcproj == null) saveMessage = "Import a canonical .ecproj first"
                else exportEcprojLauncher.launch("EffectCraft.ecproj")
            },
            onSave = {
                runCatching { projectStore.save(project) }
                    .onSuccess { saveMessage = "Saved locally · ${it.name}" }
                    .onFailure { saveMessage = "Save failed · ${it.message ?: "storage error"}" }
            },
            onNewProject = {
                applyProjectChange(ProjectDocument.starter(), "New offline composition")
                selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                playheadFrame = 0
                isPlaying = false
            },
            onAddLayer = {
                val layer = LayerDocument.newShape(project.layers.size + 1)
                applyProjectChange(project.copy(layers = project.layers + layer))
                selectedLayerId = layer.id
            },
            onSelectLayer = { selectedLayerId = it },
            onDeleteLayer = {
                if (project.layers.size > 1 && selectedLayer != null) {
                    applyProjectChange(project.copy(layers = project.layers.filterNot { it.id == selectedLayer.id }), "Layer removed · unsaved")
                    selectedLayerId = project.layers.first().id
                } else {
                    saveMessage = "A composition must keep at least one layer"
                }
            },
            onRenameLayer = { name ->
                val renamed = ProjectEditorActions.renameLayer(project.layers, selectedLayerId, name)
                if (renamed != project.layers) applyProjectChange(project.copy(layers = renamed), "Layer renamed · unsaved")
            },
            onVisibilityChange = { visible ->
                applyProjectChange(project.copy(
                    layers = project.layers.map { layer ->
                        if (layer.id == selectedLayerId) layer.copy(visible = visible) else layer
                    }
                ))
            },
            onMoveLayer = { offset ->
                applyProjectChange(project.copy(
                    layers = ProjectEditorActions.moveLayer(project.layers, selectedLayerId, offset)
                ))
            },
            onOpacityChange = { opacity ->
                applyProjectChange(project.copy(
                    layers = project.layers.map { layer ->
                        if (layer.id == selectedLayerId) layer.copy(opacity = opacity) else layer
                    }
                ))
            },
            onScrub = { playheadFrame = it.coerceIn(0, project.durationFrames - 1) },
            onTogglePlayback = { isPlaying = !isPlaying },
            onApplyComposition = { width, height, fps, duration ->
                if (width in 320..3840 && height in 320..3840 && fps in 1..120 && duration in 1..3600) {
                    applyProjectChange(
                        project.copy(width = width, height = height, frameRate = fps, durationFrames = duration),
                        "Composition updated · unsaved"
                    )
                    playheadFrame = playheadFrame.coerceIn(0, duration - 1)
                } else {
                    saveMessage = "Composition settings outside supported limits"
                }
            },
            canonicalProject = canonicalProjectInfo,
            canEditCanonicalProject = NativeProjectBridge.isAvailable(),
            onRenameCanonicalLayer = { compositionId, layerId, name ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.renameLayerEcproj(source, compositionId, layerId, name),
                    "Canonical layer renamed · saved locally"
                )
            },
            onAddCanonicalTextLayer = { compositionId, name ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.addTextLayerEcproj(source, compositionId, name),
                    "Canonical Text layer added · saved locally"
                )
            },
            onSetCanonicalLayerVisibility = { compositionId, layerId, visible ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerVisibilityEcproj(source, compositionId, layerId, visible),
                    "Canonical visibility updated · saved locally"
                )
            },
            onMoveCanonicalLayer = { compositionId, layerId, offset ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.moveLayerEcproj(source, compositionId, layerId, offset),
                    "Canonical layer reordered · saved locally"
                )
            },
            onDeleteCanonicalLayer = { compositionId, layerId ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.deleteLayerEcproj(source, compositionId, layerId),
                    "Canonical layer removed · saved locally"
                )
            },
            onSetCanonicalLayerOpacity = { compositionId, layerId, opacityPercent ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerOpacityEcproj(source, compositionId, layerId, opacityPercent),
                    "Canonical opacity updated · saved locally"
                )
            },
            onSetCanonicalLayerPosition = { compositionId, layerId, x, y ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPositionEcproj(source, compositionId, layerId, x, y),
                    "Canonical position updated · saved locally"
                )
            },
            onRelinkCanonicalAsset = { itemId ->
                pendingRelinkItemId = itemId
                relinkAssetLauncher.launch(arrayOf("*/*"))
            },
            onRenderCanonicalFrame = { compositionId, frame ->
                importedEcproj?.let { NativeProjectBridge.renderEcprojFrame(it, compositionId, frame, 512) }
            }
        )
    }
}
