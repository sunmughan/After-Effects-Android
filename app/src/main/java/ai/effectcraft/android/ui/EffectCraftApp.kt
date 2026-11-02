package ai.effectcraft.android.ui

import android.content.Intent
import android.net.Uri
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
                runCatching {
                    val source = importedEcproj ?: error("Import a canonical .ecproj first")
                    val envelope = NativeProjectBridge.renameLayerEcproj(source, compositionId, layerId, name)
                        ?: error("Native EffectCraft bridge unavailable on this ABI")
                    val response = JSONObject(envelope)
                    check(response.optBoolean("valid")) { response.optString("error", "Layer rename failed") }
                    val updated = response.getString("project")
                    val metadata = NativeProjectBridge.inspectEcproj(updated)
                        ?: error("Unable to inspect updated canonical project")
                    val parsed = CanonicalProjectInfo.parse(metadata)
                    ecprojStore.save(updated)
                    importedEcproj = updated
                    canonicalProjectInfo = parsed
                    saveMessage = "Canonical layer renamed · saved locally"
                }.onFailure { error -> saveMessage = "Canonical edit failed · ${error.message ?: "invalid project"}" }
            },
            onAddCanonicalTextLayer = { compositionId, name ->
                runCatching {
                    val source = importedEcproj ?: error("Import a canonical .ecproj first")
                    val envelope = NativeProjectBridge.addTextLayerEcproj(source, compositionId, name)
                        ?: error("Native EffectCraft bridge unavailable on this ABI")
                    val response = JSONObject(envelope)
                    check(response.optBoolean("valid")) { response.optString("error", "Text layer creation failed") }
                    val updated = response.getString("project")
                    val metadata = NativeProjectBridge.inspectEcproj(updated)
                        ?: error("Unable to inspect updated canonical project")
                    val parsed = CanonicalProjectInfo.parse(metadata)
                    ecprojStore.save(updated)
                    importedEcproj = updated
                    canonicalProjectInfo = parsed
                    saveMessage = "Canonical Text layer added · saved locally"
                }.onFailure { error -> saveMessage = "Canonical edit failed · ${error.message ?: "invalid project"}" }
            }
        )
    }
}
